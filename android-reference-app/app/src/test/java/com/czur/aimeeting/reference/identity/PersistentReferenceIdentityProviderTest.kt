package com.czur.aimeeting.reference.identity

import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.client.identity.IdentitySession
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * 默认登录插件的收尾续签：按原 meetingId 核验当前账号归属，同账号才替换令牌。
 *
 * <p>HTTP 只由测试进程内的 MockWebServer 返回固定状态，不连接部署服务；存储使用
 * Robolectric 的真实 SharedPreferences。测试不保存或打印任何真实凭据。</p>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersistentReferenceIdentityProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: PersistentReferenceIdentityProvider

    @Before fun prepare() {
        server = MockWebServer()
        server.start()
        provider = PersistentReferenceIdentityProvider(
            RuntimeEnvironment.getApplication()
        ) { server.url("/").toString() }
        provider.clearSession()
    }

    @After fun close() {
        provider.clearSession()
        server.close()
    }

    @Test fun expiredOriginalTokenIsReplacedByNewLoginOfSameAccount() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        provider.updateSession(
            IdentitySession("fresh-token", "alice"), "2099-01-01T00:00:00Z"
        )

        val identity = provider.identityForFinalization(
            "mtg_resume", IdentitySession("expired-token", "")
        )

        assertEquals(IdentityResult.Status.LOGGED_IN, identity.status())
        assertEquals("fresh-token", identity.session()!!.accessToken())
        assertEquals("alice", identity.session()!!.displayName())
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/meetings/mtg_resume", request.path)
        assertEquals("Bearer fresh-token", request.getHeader("Authorization"))
    }

    @Test fun changedAccountKeepsOriginalIdentityInsteadOfReplacingToken() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
        provider.updateSession(
            IdentitySession("other-account-token", "bob"), "2099-01-01T00:00:00Z"
        )

        val identity = provider.identityForFinalization(
            "mtg_resume", IdentitySession("expired-token", "")
        )

        // 换账号：不替换旧令牌，继续按原身份失败，避免把他人的新会话用于原场。
        assertEquals(IdentityResult.Status.LOGGED_IN, identity.status())
        assertEquals("expired-token", identity.session()!!.accessToken())
        assertEquals("", identity.session()!!.displayName())
        assertEquals(1, server.requestCount)
    }

    @Test fun unchangedTokenSkipsOwnershipProbe() {
        provider.updateSession(
            IdentitySession("same-token", "alice"), "2099-01-01T00:00:00Z"
        )

        val identity = provider.identityForFinalization(
            "mtg_resume", IdentitySession("same-token", "")
        )

        assertEquals("same-token", identity.session()!!.accessToken())
        assertEquals(0, server.requestCount)
    }

    @Test fun invalidCurrentSessionClearsLoginAndRequiresRelogin() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        provider.updateSession(
            IdentitySession("stale-token", "alice"), "2099-01-01T00:00:00Z"
        )

        val identity = provider.identityForFinalization(
            "mtg_resume", IdentitySession("expired-token", "")
        )

        assertEquals(IdentityResult.Status.LOGIN_REQUIRED, identity.status())
        assertEquals(IdentityResult.Status.LOGIN_REQUIRED, provider.currentIdentity().status())
    }

    @Test fun ownershipProbeFailurePropagatesSoOriginalCheckpointIsKept() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        provider.updateSession(
            IdentitySession("fresh-token", "alice"), "2099-01-01T00:00:00Z"
        )

        assertThrows(IOException::class.java) {
            provider.identityForFinalization(
                "mtg_resume", IdentitySession("expired-token", "")
            )
        }
        // 失败不清理现有登录，也不改写原场记录；上层保留下次重试。
        assertEquals(IdentityResult.Status.LOGGED_IN, provider.currentIdentity().status())
    }
}
