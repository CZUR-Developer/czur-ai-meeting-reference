package com.czur.aimeeting.reference.identity

import android.content.Context
import android.content.SharedPreferences
import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.client.identity.IdentitySession
import com.czur.aimeeting.client.identity.LoginIdentityProvider
import java.time.Instant

/**
 * 参考 App 的默认登录插件。
 *
 * 该实现只保存后端发回的访问令牌、显示名称和到期时间，不保存密码，也不把
 * 登录协议、账号输入框或 Activity 引入 SDK。SharedPreferences 只是参考实现的
 * 可替换存储；伙伴可以将同一 [LoginIdentityProvider] 接口接到 ContentProvider、
 * 系统账号或自己的凭据仓库，而不修改会议编排。
 */
class PersistentReferenceIdentityProvider(context: Context) : LoginIdentityProvider {
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
    override fun updateSession(session: IdentitySession, expiresAt: String?) {
        val value = StoredSession(session.accessToken(), session.displayName(), expiresAt.orEmpty())
        this.session = value
        preferences.edit()
            .putString(KEY_ACCESS_TOKEN, value.accessToken)
            .putString(KEY_DISPLAY_NAME, value.displayName)
            .putString(KEY_EXPIRES_AT, value.expiresAt)
            .apply()
    }

    /** 退出时删除令牌和关联的显示信息，保留后端连接设置方便再次登录。 */
    override fun clearSession() {
        session = null
        preferences.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

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
