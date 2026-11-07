package com.aicode.core.security

import com.aicode.core.util.FileLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 密钥轮换的启动调度。
 *
 * 单独成一个类而不是把逻辑塞进 Application：Application 里已经有不少启动任务，
 * 再塞一个会让人分不清哪个失败了；而这里需要「失败绝不影响 App 启动」这条保证，
 * 独立出来才好单独测。
 */
@Singleton
class KeyRotationScheduler @Inject constructor(
    private val rotationService: KeyRotationService
) {
    /**
     * 检查是否需要轮换，需要就做一次。
     *
     * 幂等：同一天最多做一次。做了之后密钥就换了 90 天，短期内不会再触发；
     * 但若轮换中途失败，第二天会再试一次——那时数据还没被破坏，重试是安全的。
     */
    suspend fun runIfNeeded() {
        try {
            if (!rotationService.rotateIfNeeded().rotated) return
            FileLogger.i(TAG, "已按到期策略完成密钥轮换")
        } catch (e: Throwable) {
            // 轮换失败绝不能让 App 起不来：凭据仍是旧密文、旧密钥仍在环里，数据一条不少。
            FileLogger.w(TAG, "密钥轮换失败，不影响 App 启动", e)
        }
    }

    /**
     * 清理超过保留期的退休密钥。
     *
     * 与轮换分开跑：刚退休的密钥要留够回退窗口，清理是另一件事。
     */
    fun purgeExpired() {
        try {
            val purged = KeyringStore.purgeRetired()
            if (purged.isNotEmpty()) {
                FileLogger.i(TAG, "已清理过期退休密钥：${purged.joinToString { it }}")
            }
        } catch (e: Throwable) {
            FileLogger.w(TAG, "清理退休密钥失败", e)
        }
    }

    private companion object {
        const val TAG = "KeyRotationScheduler"
    }
}
