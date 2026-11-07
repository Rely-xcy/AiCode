package com.aicode.core.security

import com.aicode.core.util.FileLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 密钥轮换：把全部密文从旧密钥换到新密钥。
 *
 * 顺序很关键，不能写成「边解边写」：
 * 1. 先把所有密文用**旧密钥**解出来，全部收进内存；
 * 2. 全部成功后才 [KeyringStore.rotate] 生成新密钥（此时旧密钥仍可解密，只是状态变了）；
 * 3. 再用**新密钥**逐项加密写回，每项写完立刻回读校验；
 * 4. 任何一步失败就整体放弃——旧密文还在原地，旧密钥还在密钥环里，数据一条不少。
 *
 * 为什么不能边解边写：写到一半失败会留下「一半新密文一半旧密文」的混合状态，
 * 而此时若旧密钥已被清理，这部分数据就再也读不出来了。
 *
 * 密文本身在哪、怎么落盘，由 [CredentialStore] 提供——core 层不认识 Room 与各个
 * repository，反向依赖会让分层检查直接拦下。
 *
 * 全部入口都是 suspend：读写凭据要经过 Room 的 suspend DAO，用 [Mutex] 而不是
 * synchronized 串行化——后者在协程里会阻塞线程，且持锁跨挂起点会死锁。
 */
@Singleton
class KeyRotationService @Inject constructor(
    private val store: CredentialStore
) {
    private val mutex = Mutex()

    /** 一次轮换的结果统计。 */
    data class Result(
        val rotated: Boolean,
        val migratedItems: Int,
        val skippedItems: Int,
        val failure: String? = null
    )

    /**
     * 到期自动轮换：剩余天数不足 [ROTATE_AHEAD_DAYS] 才动。
     *
     * 用户手动点「更换密钥」走 [rotate]，不受这个门槛限制。
     */
    suspend fun rotateIfNeeded(): Result {
        val entry = KeyringStore.activeEntry()
            ?: return Result(rotated = false, migratedItems = 0, skippedItems = 0)
        val remaining = entry.expiresAt - System.currentTimeMillis()
        val daysLeft = remaining / DAY_MS
        if (remaining > 0 && daysLeft > ROTATE_AHEAD_DAYS) {
            return Result(rotated = false, migratedItems = 0, skippedItems = 0)
        }
        return rotate()
    }

    /** 强制轮换（用户手动触发）。 */
    suspend fun rotate(): Result = mutex.withLock {
        if (KeyringStore.activeEntry() == null) {
            return Result(rotated = false, migratedItems = 0, skippedItems = 0, failure = "没有可用的密钥")
        }

        // 第一步：全部解密到内存。解不开的项跳过并计数，不中断整轮——
        // 那条本来就是坏数据（密钥早于本次轮换就已丢失），不该挡住其它数据的轮换。
        val pending = mutableListOf<PendingWrite>()
        val skippedRef = intArrayOf(0)
        val collectFailure = runCatching {
            store.collectEncryptedFields(
                skipped = { count -> skippedRef[0] = count }
            ) { label, _, writeBack ->
                pending += PendingWrite(label, writeBack)
            }
        }.getOrElse { e ->
            FileLogger.w(TAG, "轮换中止：收集密文失败", e)
            return Result(
                rotated = false,
                migratedItems = 0,
                skippedItems = skippedRef[0],
                failure = "收集密文失败：${e.message ?: e::class.simpleName}"
            )
        }
        if (collectFailure != null) {
            FileLogger.w(TAG, "轮换中止：$collectFailure")
            return Result(
                rotated = false,
                migratedItems = 0,
                skippedItems = skippedRef[0],
                failure = collectFailure
            )
        }
        if (pending.isEmpty()) {
            return Result(rotated = false, migratedItems = 0, skippedItems = skippedRef[0])
        }

        // 第二步：换新密钥。旧密钥此时仍留在密钥环里（状态变 retired），
        // 后续步骤失败时它依然能解开尚未写回的密文。
        val (newEntry, _) = KeyringStore.rotate()
        FileLogger.i(TAG, "已生成新密钥 ${newEntry.id}，开始重加密 ${pending.size} 项")

        // 第三步：用新密钥逐项写回，每项写完立刻回读校验。
        var migrated = 0
        for (item in pending) {
            val ok = runCatching { item.writeBack() }.getOrElse { e ->
                FileLogger.w(TAG, "重加密失败：${item.label}", e)
                false
            }
            if (!ok) {
                FileLogger.w(TAG, "轮换未完成：${item.label} 写回失败，其余项已用新密钥")
                return Result(
                    rotated = true,
                    migratedItems = migrated,
                    skippedItems = skippedRef[0],
                    failure = "${item.label} 写回失败"
                )
            }
            migrated++
        }

        FileLogger.i(TAG, "轮换完成：$migrated 项已换到新密钥 ${newEntry.id}")
        Result(rotated = true, migratedItems = migrated, skippedItems = skippedRef[0])
    }

    /** 一条待写回的密文：明文已在手，只差用新密钥加密落盘。 */
    private class PendingWrite(
        val label: String,
        val writeBack: suspend () -> Boolean
    )

    companion object {
        private const val TAG = "KeyRotationService"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** 剩余天数小于这个值就触发自动轮换。 */
        const val ROTATE_AHEAD_DAYS = 7L
    }
}

/**
 * 密文存储的抽象：core 层不认识 Room 与各个 repository，只管「密文在哪、怎么换掉」。
 *
 * 实现方在收集阶段用旧密钥解密每项，解不开的跳过；解密成功的把明文交出去，
 * 同时提供一个写回回调——回调内部会用当前 active 密钥加密并落盘，再回读校验。
 */
interface CredentialStore {
    /**
     * 遍历本域全部密文字段。
     *
     * 实现方负责把解不开的项计入 [skipped]，不要抛异常中断整轮。
     *
     * @param onField 每条成功解密的字段：标签、明文、写回回调（回调内部自己加密落盘并校验）
     * @param skipped 解不开而被跳过的项数
     * @return 非 null 表示整体失败（整轮放弃），null 表示继续
     */
    suspend fun collectEncryptedFields(
        skipped: (Int) -> Unit = {},
        onField: (label: String, plaintext: String, writeBack: suspend () -> Boolean) -> Unit
    ): String?
}
