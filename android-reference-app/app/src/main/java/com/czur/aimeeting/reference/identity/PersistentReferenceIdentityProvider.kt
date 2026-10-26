package com.czur.aimeeting.reference.identity

import android.content.Context
import android.content.SharedPreferences
import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.client.identity.IdentitySession
import com.czur.aimeeting.client.identity.LoginIdentityProvider
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * 参考 App 的默认登录插件。
 *
 * 该实现只保存后端发回的访问令牌、显示名称和到期时间，不保存密码，也不把
 * 登录协议、账号输入框或 Activity 引入 SDK。SharedPreferences 只是参考实现的
 * 可替换存储；伙伴可以将同一 [LoginIdentityProvider] 接口接到 ContentProvider、
 * 系统账号或自己的凭据仓库，而不修改会议编排。
 *
 * [identityForFinalization] 的归属核验复用后端会议详情：以当前会话读取原
 * `meetingId`，只有该账号确实能读到原会议才替换旧令牌；换账号时返回原身份，
 * 且不改写本地登录记录。
 */
class PersistentReferenceIdentityProvider(
    context: Context,
    private val backendUrl: () -> String = { MeetingAppAssembly.serverUrl() },
) : LoginIdentityProvider {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME, Context.MODE_PRIVATE
    )

    @Volatile
    private var session: StoredSession? = readStoredSession()

    /** 只返回当前有效会话；令牌到期后清理本地记录并回到正常登录入口。 */
    override fun currentIdentity(): IdentityResult {
        val current = session ?: return IdentityResult.loginRequired("请先登录")
        if (current.expiresAt.isNotBlank() && isExpired(current.expiresAt)) {
            clearSession()
            return IdentityResult.loginRequired("登录已过期，请重新登录")
        }
        return IdentityResult.loggedIn(IdentitySession(current.accessToken, current.displayName))
    }

    /** 登录成功后保存服务端返回的短期会话；密码从未进入本类。 */
    @Synchronized
    override fun updateSession(session: IdentitySession, expiresAt: String?) {
        val value = StoredSession(session.accessToken(), session.displayName(), expiresAt.orEmpty())
        this.session = value
        preferences.edit()
            .putString(KEY_ACCESS_TOKEN, value.accessToken)
            .putString(KEY_DISPLAY_NAME, value.displayName)
            .putString(KEY_EXPIRES_AT, value.expiresAt)
            .apply()
    }

    /** 退出时删除令牌和关联的显示信息，部署地址仍由当前 APK 的编译配置提供。 */
    @Synchronized
    override fun clearSession() {
        session = null
        preferences.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    /**
     * 同账号收尾续签：当前登录确实拥有原会议时才用新令牌替换旧会话。
     *
     * <p>原场停止记录里只留下已过期的旧令牌，无法再用它证明归属；这里改用当前
     * 新会话读取后端会议详情 `/v1/meetings/{meetingId}`。会议按主体隔离，能读到
     * 就说明当前账号就是原会议所有者，可以安全替换；403/404 表示换账号或会议
     * 已不存在，返回原身份并保留原状态，让 Stop 继续用旧令牌失败而不会串号。
     * 当前会话自身 401 只作废“发起核验时的那一份”并要求重新登录：核验是网络
     * 调用，返回时用户可能已重新登录，迟到的 401 不能清掉新会话。网络或存储异常
     * 直接抛出，由上层保留原场等待下次确认，不把失败伪装成成功。</p>
     *
     * <p>本方法不打开 UI、不建新场、不改会议归属；只在尾部发布／Stop／结果阶段
     * 由同一 IO 队列调用。</p>
     */
    @Throws(IOException::class)
    override fun identityForFinalization(
        meetingId: String,
        originalIdentity: IdentitySession,
    ): IdentityResult {
        require(meetingId.isNotBlank()) { "meetingId must not be blank" }
        val current = session ?: return IdentityResult.loginRequired("请先登录")
        if (current.expiresAt.isNotBlank() && isExpired(current.expiresAt)) {
            clearSession()
            return IdentityResult.loginRequired("登录已过期，请重新登录")
        }
        if (current.accessToken == originalIdentity.accessToken()) {
            // 凭据未变化，无需额外网络核验，原状态保持不变。
            return IdentityResult.loggedIn(originalIdentity)
        }
        return when (ownershipOf(meetingId, current.accessToken)) {
            Ownership.OWNED -> IdentityResult.loggedIn(
                IdentitySession(current.accessToken, current.displayName)
            )
            // 换账号：保留原状态，调用方继续用旧令牌失败，不能借用他人会话。
            Ownership.NOT_OWNER -> IdentityResult.loggedIn(originalIdentity)
            Ownership.UNAUTHORIZED -> expireProbedSession(current)
        }
    }

    /**
     * 401 只作废发起核验时的那份持久会话。
     *
     * <p>核验期间用户可能已经重新登录。若当前会话已换成另一份令牌，这个 401
     * 属于旧令牌，属于迟到响应：忽略即可，并返回当前登录状态，不能把刚完成的
     * 新登录清掉。检查与清理在同一把锁内，避免与 [updateSession] 交错。</p>
     */
    @Synchronized
    private fun expireProbedSession(probed: StoredSession): IdentityResult {
        val current = session ?: return IdentityResult.loginRequired("请先登录")
        if (current.accessToken != probed.accessToken) {
            // 新的持久会话已经建立：旧请求的 401 与它无关。
            if (current.expiresAt.isNotBlank() && isExpired(current.expiresAt)) {
                clearSession()
                return IdentityResult.loginRequired("登录已过期，请重新登录")
            }
            return IdentityResult.loggedIn(IdentitySession(current.accessToken, current.displayName))
        }
        clearSession()
        return IdentityResult.loginRequired("登录已失效，请重新登录")
    }

    private fun ownershipOf(meetingId: String, accessToken: String): Ownership {
        val base = backendUrl().trimEnd('/')
        if (base.isEmpty()) throw IOException("后端地址未配置")
        val encoded = URLEncoder.encode(meetingId, StandardCharsets.UTF_8.name())
        val connection = URL("$base/v1/meetings/$encoded").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            return when (connection.responseCode) {
                200 -> Ownership.OWNED
                403, 404 -> Ownership.NOT_OWNER
                401 -> Ownership.UNAUTHORIZED
                else -> throw IOException(
                    "核验会议归属失败，HTTP ${connection.responseCode}"
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private enum class Ownership { OWNED, NOT_OWNER, UNAUTHORIZED }

    private fun readStoredSession(): StoredSession? {
        val token = preferences.getString(KEY_ACCESS_TOKEN, null)?.trim().orEmpty()
        if (token.isEmpty()) return null
        return StoredSession(
            token,
            preferences.getString(KEY_DISPLAY_NAME, "") ?: "",
            preferences.getString(KEY_EXPIRES_AT, "") ?: ""
        )
    }

    private fun isExpired(value: String): Boolean = try {
        !Instant.parse(value).isAfter(Instant.now())
    } catch (_: RuntimeException) {
        // 参考服务端只发 RFC3339；格式异常按登录失效处理，不让页面因旧缓存崩溃。
        true
    }

    private data class StoredSession(
        val accessToken: String,
        val displayName: String,
        val expiresAt: String,
    )

    companion object {
        private const val PREFERENCES_NAME = "czur_reference_identity"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_EXPIRES_AT = "expires_at"
    }
}
