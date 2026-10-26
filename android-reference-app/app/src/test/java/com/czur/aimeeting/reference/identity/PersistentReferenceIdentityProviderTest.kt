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
     * 当前持久会话已经换成新令牌时，该 401 只属于旧令牌。新登录随后又核验了一次
     * 归属，仍然 401 说明归属没有得到确认，所以续办身份仍退回原场，登录本身保留。
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
            // 二次核验仍 401，归属未确认：不能把新令牌写进原场收尾记录。
            assertEquals("expired-token", identity.session()!!.accessToken())
            assertEquals("fresh-token", provider.currentIdentity().session()!!.accessToken())
        } finally {
            releaseProbe.countDown()
            caller.join(5_000)
        }
    }

    /**
     * 换账号后的迟到 401：新账号必须先核验对原会议的归属，读不到就退回原身份。
     *
     * <p>Alice 的原场核验挂起期间用户登录了 Bob，旧请求随后返回 401。此时不能把
     * Bob 的令牌直接当成原会议的续办身份：再核验对 `meetingId` 的归属返回 403，
     * 所以返回 Alice 原场身份，本次续办暂缓；Bob 的持久登录保持不变。</p>
     */
    @Test fun lateUnauthorizedProbeAfterAccountSwitchKeepsOriginalIdentity() {
        val identity = accountSwitchDuringProbe(reprobeStatus = 403)

        assertEquals(IdentityResult.Status.LOGGED_IN, identity.status())
        assertEquals(ALICE_ORIGINAL_TOKEN, identity.session()!!.accessToken())
        assertEquals("alice", identity.session()!!.displayName())
        assertEquals("bob-token", provider.currentIdentity().session()!!.accessToken())
        assertReprobeUsedCurrentSession()
    }

    /**
     * 换账号后新账号确实拥有原会议时允许替换：再核验返回 200 才用 Bob 的令牌续办。
     */
    @Test fun lateUnauthorizedProbeAfterAccountSwitchReplacesWhenNewAccountOwnsMeeting() {
        val identity = accountSwitchDuringProbe(reprobeStatus = 200)

        assertEquals(IdentityResult.Status.LOGGED_IN, identity.status())
        assertEquals("bob-token", identity.session()!!.accessToken())
        assertEquals("bob", identity.session()!!.displayName())
        assertEquals("bob-token", provider.currentIdentity().session()!!.accessToken())
        assertReprobeUsedCurrentSession()
    }

    /**
     * 再核验本身 401 说明新账号的归属仍未确认：保持 Bob 的登录，返回 Alice 原场
     * 身份，本次续办暂缓，不能把 Bob 的会话写进原场收尾记录。
     */
    @Test fun unauthorizedReprobeAfterAccountSwitchDefersWithOriginalIdentity() {
        val identity = accountSwitchDuringProbe(reprobeStatus = 401)

        assertEquals(IdentityResult.Status.LOGGED_IN, identity.status())
        assertEquals(ALICE_ORIGINAL_TOKEN, identity.session()!!.accessToken())
        assertEquals("alice", identity.session()!!.displayName())
        assertEquals("bob-token", provider.currentIdentity().session()!!.accessToken())
        assertEquals("bob", provider.currentIdentity().session()!!.displayName())
        assertReprobeUsedCurrentSession()
    }

    /**
     * 再核验挂起期间又换了一次账号：即使那次核验返回 200，发起核验的会话也已经
     * 不是当前登录，不能采用它的结果；返回 Alice 原场身份，Carol 的登录保持不变。
     */
    @Test fun accountSwitchDuringReprobeIsNotAdopted() {
        val firstProbeArrived = CountDownLatch(1)
        val releaseFirstProbe = CountDownLatch(1)
        val reprobeArrived = CountDownLatch(1)
        val releaseReprobe = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.getHeader("Authorization")) {
                    "Bearer stale-token" -> {
                        firstProbeArrived.countDown()
                        if (!releaseFirstProbe.await(5, TimeUnit.SECONDS)) {
                            throw IllegalStateException("测试未释放旧核验")
                        }
                        MockResponse().setResponseCode(401).setBody("{}")
                    }
                    "Bearer bob-token" -> {
                        reprobeArrived.countDown()
                        if (!releaseReprobe.await(5, TimeUnit.SECONDS)) {
                            throw IllegalStateException("测试未释放再核验")
                        }
                        MockResponse().setResponseCode(200).setBody("{}")
                    }
                    else -> MockResponse().setResponseCode(403).setBody("{}")
                }
            }
        }
        provider.updateSession(
            IdentitySession("stale-token", "alice"), "2099-01-01T00:00:00Z"
        )
        val result = CompletableFuture<IdentityResult>()
        val caller = Thread {
            try {
                result.complete(
                    provider.identityForFinalization(
                        "mtg_resume", IdentitySession(ALICE_ORIGINAL_TOKEN, "alice")
                    )
                )
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }
        caller.start()
        try {
            assertTrue("旧核验应已发出", firstProbeArrived.await(5, TimeUnit.SECONDS))
            provider.updateSession(
                IdentitySession("bob-token", "bob"), "2099-01-01T00:00:00Z"
            )
            releaseFirstProbe.countDown()
            assertTrue("再核验应已发出", reprobeArrived.await(5, TimeUnit.SECONDS))
            // 再核验还在路上时又换了一次账号；迟到的 200 不能采用 Carol 的会话。
            provider.updateSession(
                IdentitySession("carol-token", "carol"), "2099-01-01T00:00:00Z"
            )
            releaseReprobe.countDown()

            val identity = result.get(5, TimeUnit.SECONDS)
            assertEquals(IdentityResult.Status.LOGGED_IN, identity.status())
            assertEquals(ALICE_ORIGINAL_TOKEN, identity.session()!!.accessToken())
            assertEquals("alice", identity.session()!!.displayName())
            assertEquals("carol-token", provider.currentIdentity().session()!!.accessToken())
            // 再核验仍然用 Bob 的令牌发出，没有改用期间登录的 Carol。
            assertReprobeUsedCurrentSession()
        } finally {
            releaseFirstProbe.countDown()
            releaseReprobe.countDown()
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

    /**
     * 复现“核验挂起期间换账号”：旧会话的归属核验阻塞到主线程登录 Bob 后才返回
     * 401，随后插件必须用 Bob 的会话再核验一次 `meetingId` 归属。
     *
     * <p>调度器按 Bearer 令牌分流：旧令牌固定 401，Bob 的令牌返回 [reprobeStatus]。
     * 返回插件最终的收尾身份；请求序列由调用方用 [assertReprobeUsedCurrentSession]
     * 核对。</p>
     */
    private fun accountSwitchDuringProbe(reprobeStatus: Int): IdentityResult {
        val probeArrived = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer stale-token") {
                    return MockResponse().setResponseCode(reprobeStatus).setBody("{}")
                }
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
                result.complete(
                    provider.identityForFinalization(
                        "mtg_resume", IdentitySession(ALICE_ORIGINAL_TOKEN, "alice")
                    )
                )
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }
        caller.start()
        try {
            assertTrue("旧核验应已发出", probeArrived.await(5, TimeUnit.SECONDS))
            // 旧请求仍在路上时用户登录了另一个账号；随后旧令牌的 401 才返回。
            provider.updateSession(
                IdentitySession("bob-token", "bob"), "2099-01-01T00:00:00Z"
            )
            releaseProbe.countDown()
            return result.get(5, TimeUnit.SECONDS)
        } finally {
            releaseProbe.countDown()
            caller.join(5_000)
        }
    }

    /** 两次请求都打原会议详情，第二次必须换成 Bob 的令牌，证明用的是发起再核验时的会话。 */
    private fun assertReprobeUsedCurrentSession() {
        val first = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "缺少旧核验请求" }
        val second = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "缺少再核验请求" }
        assertEquals("GET", first.method)
        assertEquals("/v1/meetings/mtg_resume", first.path)
        assertEquals("Bearer stale-token", first.getHeader("Authorization"))
        assertEquals("GET", second.method)
        assertEquals("/v1/meetings/mtg_resume", second.path)
        assertEquals("Bearer bob-token", second.getHeader("Authorization"))
        assertEquals(2, server.requestCount)
    }

    private companion object {
        /** 原场停止记录里 Alice 留下的旧令牌，仅测试哨兵，不是真实凭据。 */
        const val ALICE_ORIGINAL_TOKEN = "alice-field-token"
    }
}
