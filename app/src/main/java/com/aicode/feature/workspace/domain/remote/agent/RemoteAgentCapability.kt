package com.aicode.feature.workspace.domain.remote.agent

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aicode.core.util.FileLogger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private const val TAG = "RemoteAgentCapability"

/**
 * 远程能力分级。
 *
 * - [L1_AGENT]：agent 可启动且 hello 协议版本匹配，文件操作走自定义协议；
 * - [L2_EXEC]：agent 不可用，但远端有 find/grep，文件操作可退回「shell 命令 + 输出解析」；
 * - [L3_SFTP]：两者都不可用，只能走 SFTP。
 */
enum class RemoteAgentTier { L1_AGENT, L2_EXEC, L3_SFTP }

/** 探测目标。缓存键必须含这三项：同一台主机换用户或换根目录，能力结论不能复用。 */
data class RemoteAgentTarget(
    val host: String,
    val user: String,
    val root: String
) {
    val cacheKey: String get() = "$host|$user|$root"
}

/** 能力探测结果的持久化缝。实现失败不应影响探测本身（缓存只是优化）。 */
interface RemoteAgentTierStore {
    suspend fun load(key: String): RemoteAgentTier?
    suspend fun save(key: String, tier: RemoteAgentTier)
    suspend fun clear(key: String)
}

/** 默认不落盘：期 1 没有 DI 注入点，先只靠探测器的内存缓存。 */
object NoOpRemoteAgentTierStore : RemoteAgentTierStore {
    override suspend fun load(key: String): RemoteAgentTier? = null
    override suspend fun save(key: String, tier: RemoteAgentTier) = Unit
    override suspend fun clear(key: String) = Unit
}

/**
 * DataStore 版缓存。存字符串而非枚举序号：枚举顺序将来可能调整，序号会静默错位。
 *
 * 只应缓存 L1/L2 这类「正向能力」；L3 是探测失败的结果，一次网络抖动就该重试，
 * 不能永久钉死（见 [RemoteAgentCapabilityDetector]）。
 */
class DataStoreRemoteAgentTierStore(
    private val dataStore: DataStore<Preferences>
) : RemoteAgentTierStore {

    override suspend fun load(key: String): RemoteAgentTier? {
        val raw = dataStore.data.first()[tierKey(key)] ?: return null
        return RemoteAgentTier.entries.firstOrNull { it.name == raw }
    }

    override suspend fun save(key: String, tier: RemoteAgentTier) {
        dataStore.edit { it[tierKey(key)] = tier.name }
    }

    override suspend fun clear(key: String) {
        dataStore.edit { it.remove(tierKey(key)) }
    }

    private fun tierKey(key: String) = stringPreferencesKey("remote_agent_tier_$key")
}

/**
 * 能力探测状态机。
 *
 * 1. L1：走 [openSession] 打开会话并 [RemoteAgentSession.start]；hello 成功且 `proto == 1` 即 L1；
 * 2. `proto != 1`：按 PROTOCOL.md 第 7 节**直接**回落 L3，不尝试 L2（不兼容的 agent 存在，
 * 说明环境里本来就有 agent，降级到 shell 解析只会引入另一套不一致）；
 * 3. 其它启动/握手失败：探测 `find` + `grep`，都可用则 L2；
 * 4. 都不可用：L3。
 *
 * 探测结果缓存两层：本类内存 Map（总是生效）+ 可选 [RemoteAgentTierStore]。
 * 只把 L1/L2 写进持久层；L3 只进内存，避免一次断网把主机永久钉在 SFTP。
 * 但内存里的 L3 仍会命中，所以部署完 agent 后必须 [invalidate] 或传 `forceRefresh = true`。
 */
class RemoteAgentCapabilityDetector(
    private val openSession: suspend (RemoteAgentTarget) -> RemoteAgentSession,
    private val probeCommand: suspend (String) -> Boolean,
    private val store: RemoteAgentTierStore = NoOpRemoteAgentTierStore
) {

    private val memory = ConcurrentHashMap<String, RemoteAgentTier>()

    /**
     * 返回目标的能力等级。[forceRefresh] 为 true 时跳过两级缓存重新探测（部署新 agent 后使用）。
     */
    suspend fun detect(target: RemoteAgentTarget, forceRefresh: Boolean = false): RemoteAgentTier {
        val key = target.cacheKey
        if (!forceRefresh) {
            memory[key]?.let { return it }
            val cached = try {
                store.load(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "读取能力缓存失败，重新探测: $key", e)
                null
            }
            if (cached != null) {
                memory[key] = cached
                return cached
            }
        }

        val tier = probe(target)
        memory[key] = tier
        if (tier == RemoteAgentTier.L3_SFTP) {
            // 失败不落盘；顺手清掉可能过期的正向缓存，否则下次仍会先命中旧结论
            clearStoreBestEffort(key)
        } else {
            try {
                store.save(key, tier)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "写入能力缓存失败（忽略）: $key", e)
            }
        }
        return tier
    }

    /** 清掉某个目标的缓存（例如 agent 被卸载或重新部署后）。 */
    suspend fun invalidate(target: RemoteAgentTarget) {
        val key = target.cacheKey
        memory.remove(key)
        clearStoreBestEffort(key)
    }

    private suspend fun clearStoreBestEffort(key: String) {
        try {
            store.clear(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "清理能力缓存失败（忽略）: $key", e)
        }
    }

    private suspend fun probe(target: RemoteAgentTarget): RemoteAgentTier {
        val session = try {
            openSession(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "打开 agent 会话失败，改探 L2: ${e.message}", e)
            null
        }

        if (session != null) {
            try {
                session.start()
                val caps = session.capabilities
                if (caps == null || caps.proto != REMOTE_AGENT_PROTOCOL_VERSION) {
                    // start() 已校验 proto，这里只兜底实现异常
                    FileLogger.w(TAG, "agent 握手完成但能力位异常，按 L3 处理: proto=${caps?.proto}")
                    return RemoteAgentTier.L3_SFTP
                }
                FileLogger.d(TAG, "能力探测: L1_AGENT host=${target.host} arch=${caps.arch}")
                return RemoteAgentTier.L1_AGENT
            } catch (e: RemoteAgentProtocolMismatchException) {
                FileLogger.w(TAG, "agent 协议不兼容（proto=${e.proto}），按协议直接回落 L3")
                return RemoteAgentTier.L3_SFTP
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "agent 握手失败，改探 L2: ${e.message}", e)
            } finally {
                session.close()
            }
        }

        val execCapable = try {
            probeCommand(L2_PROBE_COMMAND)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "探测 find/grep 失败，按不可用处理: ${e.message}", e)
            false
        }
        return if (execCapable) {
            FileLogger.d(TAG, "能力探测: L2_EXEC host=${target.host}")
            RemoteAgentTier.L2_EXEC
        } else {
            FileLogger.d(TAG, "能力探测: L3_SFTP host=${target.host}")
            RemoteAgentTier.L3_SFTP
        }
    }

    private companion object {
        /** find 与 grep 都给 L2 用：缺一个就解析不全，按不可用处理。 */
        const val L2_PROBE_COMMAND =
            "command -v find >/dev/null 2>&1 && command -v grep >/dev/null 2>&1"
    }
}
