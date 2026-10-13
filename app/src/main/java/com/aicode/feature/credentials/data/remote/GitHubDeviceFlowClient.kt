package com.aicode.feature.credentials.data.remote

import com.aicode.core.net.AppProxy
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** GitHub 设备码流返回的设备授权信息。 */
data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int
)

/** 轮询 access token 的结果。 */
sealed interface GitHubTokenPollResult {
    data class Success(val accessToken: String) : GitHubTokenPollResult

    /** 用户尚未完成授权，按间隔继续轮询。 */
    data object Pending : GitHubTokenPollResult

    /** 轮询过快，需延长间隔后继续。 */
    data object SlowDown : GitHubTokenPollResult

    /** 设备码过期，需重新申请。 */
    data object Expired : GitHubTokenPollResult

    /** 用户拒绝授权。 */
    data object Denied : GitHubTokenPollResult

    data class Error(val code: String) : GitHubTokenPollResult
}

/** GitHub App 在当前用户下的安装情况（三态，不再用 Boolean 混「未安装」与「查不到」）。 */
sealed interface AppInstall {
    /** 已安装；[repositories] 是本次安装已授权的仓库全名（查不到时为空），[contentsWrite] 为 false 时推送会被拒。 */
    data class Installed(
        val installationId: Long,
        val repositories: List<String>,
        val contentsWrite: Boolean
    ) : AppInstall

    /** 确实未安装：应引导用户去安装页。 */
    data object NotInstalled : AppInstall

    /** 无法确认（网络/权限受限）：不据此弹「去安装」，但也不假装一切正常。 */
    data object Unknown : AppInstall
}

/** GitHub App 的 slug（安装页 URL 用），与 App 名称对应。 */
const val GITHUB_APP_SLUG = "aicode-app"

/** GitHub App 安装页：用户在此选择要授权的仓库。 */
const val GITHUB_APP_INSTALL_URL = "https://github.com/apps/$GITHUB_APP_SLUG/installations/new"

/**
 * GitHub App 设备码流（Device Authorization Grant）客户端：无浏览器场景下，让用户在浏览器
 * 输入设备码完成授权，再换取 user access token。GitHub App 不传 scope，权限由 App 注册时的
 * 细粒度权限决定，用户安装时可选择授权的仓库。
 *
 * 参考 https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app#using-the-device-flow-to-generate-a-user-access-token
 * 与其它网络组件一致，OkHttpClient 挂 [AppProxy.okHttpAuthenticator]，随全局代理配置走。
 */
@Singleton
class GitHubDeviceFlowClient @Inject constructor() {

    private companion object {
        /**
         * GitHub App 的 client_id（设备码流不需要 client_secret）。
         * 该 App 必须在设置里启用 Device Flow；权限由 App 注册的细粒度权限决定。
         */
        const val GITHUB_APP_CLIENT_ID = "Iv23likIO5f2MvtjrHZq"

        const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
        const val USER_API_URL = "https://api.github.com/user"
        const val USER_INSTALLATIONS_URL = "https://api.github.com/user/installations"
        const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

        val SHARED_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .proxyAuthenticator(AppProxy.okHttpAuthenticator)
                .build()
        }
    }

    /**
     * 申请设备码与用户验证码。失败（如 client_id 无效、未启用设备流）时抛出携带 GitHub 错误码的异常。
     */
    suspend fun requestDeviceCode(): GitHubDeviceCode = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", GITHUB_APP_CLIENT_ID)
            .build()
        val req = Request.Builder()
            .url(DEVICE_CODE_URL)
            .header("Accept", "application/json")
            .post(form)
            .build()
        SHARED_CLIENT.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val obj = JsonParser.parseString(body).asJsonObject
            obj.get("error")?.asString?.let { throw IllegalStateException(it) }
            GitHubDeviceCode(
                deviceCode = obj.get("device_code").asString,
                userCode = obj.get("user_code").asString,
                verificationUri = obj.get("verification_uri").asString,
                expiresInSeconds = obj.get("expires_in").asInt,
                intervalSeconds = obj.get("interval").asInt
            )
        }
    }

    /** 轮询用户是否已完成授权；成功时返回 access token，未完成返回 [GitHubTokenPollResult.Pending]。 */    suspend fun pollAccessToken(deviceCode: String): GitHubTokenPollResult = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", GITHUB_APP_CLIENT_ID)
            .add("device_code", deviceCode)
            .add("grant_type", DEVICE_GRANT_TYPE)
            .build()
        val req = Request.Builder()
            .url(ACCESS_TOKEN_URL)
            .header("Accept", "application/json")
            .post(form)
            .build()
        SHARED_CLIENT.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val obj = JsonParser.parseString(body).asJsonObject
            obj.get("access_token")?.asString?.takeIf { it.isNotBlank() }?.let {
                return@withContext GitHubTokenPollResult.Success(it)
            }
            when (obj.get("error")?.asString) {
                "authorization_pending" -> GitHubTokenPollResult.Pending
                "slow_down" -> GitHubTokenPollResult.SlowDown
                "expired_token", "token_expired" -> GitHubTokenPollResult.Expired
                "access_denied" -> GitHubTokenPollResult.Denied
                else -> GitHubTokenPollResult.Error(obj.get("error")?.asString ?: "unknown_error")
            }
        }
    }

    /** 用 access token 拉取登录用户名（凭据的用户名部分）。 */
    suspend fun fetchUserLogin(accessToken: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(USER_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $accessToken")
            .build()
        SHARED_CLIENT.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            JsonParser.parseString(body).asJsonObject.get("login").asString
        }
    }

    /**
     * 查询本 App 在该用户下的安装情况与权限。
     *
     * 为什么必须分三态：`/user/installations` 在「确实未安装」与「令牌权限不够/查询失败」两种情形下
     * 都会给出空结果，以前只用 Boolean 会把二者混为一谈（且调用方还把异常当成「已安装」），
     * 于是要么误报「去安装」、要么什么都不提示、也不列出已授权仓库。
     */
    suspend fun queryAppInstall(accessToken: String): AppInstall = withContext(Dispatchers.IO) {
        val resp = runCatching {
            val req = Request.Builder()
                .url(USER_INSTALLATIONS_URL)
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer $accessToken")
                .build()
            SHARED_CLIENT.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
        }.getOrNull() ?: return@withContext AppInstall.Unknown

        val (code, body) = resp
        if (code !in 200..299) return@withContext AppInstall.Unknown
        val installations = runCatching {
            JsonParser.parseString(body).asJsonObject.getAsJsonArray("installations")
        }.getOrNull() ?: return@withContext AppInstall.Unknown

        val match = installations.mapNotNull { it.asJsonObject }.firstOrNull {
            it.get("app_slug")?.asString == GITHUB_APP_SLUG
        } ?: return@withContext AppInstall.NotInstalled

        val installationId = match.get("id")?.asLong ?: return@withContext AppInstall.Unknown
        // 安装响应里带该 App 在本安装下被授予的权限；contents=write 才能推送。
        val contentsWrite = match.getAsJsonObject("permissions")
            ?.get("contents")?.asString?.equals("write", ignoreCase = true) == true
        AppInstall.Installed(
            installationId = installationId,
            repositories = fetchInstallationRepositories(accessToken, installationId),
            contentsWrite = contentsWrite
        )
    }

    /** 列出某个安装下已授权的仓库全名；查不到时返回空列表（不因此把「已安装」判成失败）。 */
    private fun fetchInstallationRepositories(accessToken: String, installationId: Long): List<String> = runCatching {
        val req = Request.Builder()
            .url("$USER_INSTALLATIONS_URL/$installationId/repositories?per_page=100")
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $accessToken")
            .build()
        SHARED_CLIENT.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            JsonParser.parseString(body).asJsonObject.getAsJsonArray("repositories")
                ?.mapNotNull { it.asJsonObject.get("full_name")?.asString }
                .orEmpty()
        }
    }.getOrDefault(emptyList())
}
