package com.czur.aimeeting.reference.identity

import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.client.identity.IdentitySession
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    /**
     * 旧核验的 401 晚到不能清掉期间完成的新登录：核验按发起时的会话判定，
     * 当前持久会话已经换成新令牌时忽略迟到结果并返回当前状态。
     */
    @Test fun lateUnauthorizedProbeDoesNotClearNewlyCompletedLogin() {
        val probeArrived = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                probeArrived.countDown()
                if (!releaseProbe.await(5, TimeUnit.SECONDS)) {
                    throw IllegalStateException("测试未释放旧核验")
                }
                return MockResponse().setResponseCode(401).setBody("{}")
            }
        }
        provider.updateSession(
            IdentitySession("stale-token", "alice"), "2099-01-01T00:00:00Z"
        )
        val result = CompletableFuture<IdentityResult>()
        val caller = Thread {
            try {
                result.complete(provider.identityForFinalization(
                    "mtg_resume", IdentitySession("expired-token", "")
                ))
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }
        caller.start()
        try {
            assertTrue("旧核验应已发出", probeArrived.await(5, TimeUnit.SECONDS))
            // 旧请求仍在路上时用户重新登录；随后旧令牌的 401 才返回。
            provider.updateSession(
                IdentitySession("fresh-token", "alice"), "2099-01-01T00:00:00Z"
            )
            releaseProbe.countDown()

            val identity = result.get(5, TimeUnit.SECONDS)
            assertEquals(IdentityResult.Status.LOGGED_IN, identity.status())
            assertEquals("fresh-token", identity.session()!!.accessToken())
            assertEquals("fresh-token", provider.currentIdentity().session()!!.accessToken())
        } finally {
            releaseProbe.countDown()
            caller.join(5_000)
        }
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
