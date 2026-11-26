package com.czur.aimeeting.client

import com.czur.aimeeting.client.identity.IdentityProvider
import com.czur.aimeeting.client.identity.IdentitySession
import com.czur.aimeeting.client.identity.ReferenceIdentityProvider
import java.io.IOException
import java.util.Optional
import java.util.concurrent.Executor
import java.util.function.BooleanSupplier

/**
 * 部署配置不含模型 endpoint。每场从身份来源冻结凭据，刷新不更改进行中会议。
 * callbackExecutor 必须串行，Android 通常传主线程；不输出配置字段以免泄露 token。
 */
class MeetingClientConfig private constructor(
    private val serverUrl: String,
    private val accessToken: String,
    private val identityProvider: IdentityProvider,
    private val clientIdentity: ClientIdentity,
    private val profile: MeetingProfile,
    private val callbackExecutor: Executor,
    private val artifactPublisher: MeetingArtifactPublisher,
    private val stoppedMeetingStore: StoppedMeetingStore?,
    private val meetingRecoveryStore: MeetingRecoveryStore?,
    private val requestAllowed: BooleanSupplier,
) {
    constructor(serverUrl: String, accessToken: String, clientIdentity: ClientIdentity,
        profile: MeetingProfile, callbackExecutor: Executor) : this(serverUrl, accessToken,
        ReferenceIdentityProvider(accessToken), clientIdentity, profile, callbackExecutor,
        MeetingArtifactPublisher.none(), null, null, BooleanSupplier { true })

    /** Provider 可由页面／Service 共用，SDK 不持有 Activity 或打开登录 UI。 */
    constructor(serverUrl: String, identityProvider: IdentityProvider, clientIdentity: ClientIdentity,
        profile: MeetingProfile, callbackExecutor: Executor) : this(serverUrl, "", identityProvider,
        clientIdentity, profile, callbackExecutor, MeetingArtifactPublisher.none(), null, null,
        BooleanSupplier { true })

    fun serverUrl(): String = serverUrl
    /** 旧固定凭据或场内快照；新 App 用 refreshIdentity 读取身份。 */
    fun accessToken(): String = accessToken
    fun identityProvider(): IdentityProvider = identityProvider
    fun clientIdentity(): ClientIdentity = clientIdentity
    fun profile(): MeetingProfile = profile
    fun callbackExecutor(): Executor = callbackExecutor
    fun artifactPublisher(): MeetingArtifactPublisher = artifactPublisher
    /** 未装配保持原内存路径。 */
    fun stoppedMeetingStore(): Optional<StoppedMeetingStore> = Optional.ofNullable(stoppedMeetingStore)
    /** 未装配原场现场端口时不写恢复记录，保持旧内存行为。 */
    fun meetingRecoveryStore(): Optional<MeetingRecoveryStore> = Optional.ofNullable(meetingRecoveryStore)
    /** 不改变原配置，不开启录音，不发生上传；发布器只使用本场 Context。 */
    fun withArtifactPublisher(publisher: MeetingArtifactPublisher): MeetingClientConfig = MeetingClientConfig(
        serverUrl, accessToken, identityProvider, clientIdentity, profile, callbackExecutor, publisher,
        stoppedMeetingStore, meetingRecoveryStore, requestAllowed)
    /** 只注册应用私有停止记录端口，不写文件、不创建恢复任务。 */
    fun withStoppedMeetingStore(store: StoppedMeetingStore): MeetingClientConfig = MeetingClientConfig(
        serverUrl, accessToken, identityProvider, clientIdentity, profile, callbackExecutor, artifactPublisher,
        store, meetingRecoveryStore, requestAllowed)
    /**
     * 只注册应用私有原场现场端口：开会时随采音起点落盘，正常结束或确认续办后清理。
     * 不开启录音、不上传、不触发自动恢复；恢复由宿主在开机或启动时逐场调用。
     */
    fun withMeetingRecoveryStore(store: MeetingRecoveryStore): MeetingClientConfig = MeetingClientConfig(
        serverUrl, accessToken, identityProvider, clientIdentity, profile, callbackExecutor, artifactPublisher,
        stoppedMeetingStore, store, requestAllowed)
    /** 组合层冻结本场凭据，避免账号刷新改变场内请求身份。 */
    fun forSession(session: IdentitySession): MeetingClientConfig = MeetingClientConfig(
        serverUrl, session.accessToken(), identityProvider, clientIdentity, profile, callbackExecutor,
        artifactPublisher, stoppedMeetingStore, meetingRecoveryStore, requestAllowed)

    /** A recovery client only uses the original account and deployment while still authorized. */
    fun withRequestGuard(allowed: BooleanSupplier): MeetingClientConfig = MeetingClientConfig(
        serverUrl, accessToken, identityProvider, clientIdentity, profile, callbackExecutor,
        artifactPublisher, stoppedMeetingStore, meetingRecoveryStore, allowed)

    @Throws(IOException::class)
    internal fun checkRequestAllowed() {
        if (!requestAllowed.asBoolean) throw IOException("原场身份或部署已改变")
    }

    companion object {
        /** 旧参考 App 薄适配，伙伴应配置自己的身份／档位。 */
        @JvmStatic fun reference(serverUrl: String, accessToken: String): MeetingClientConfig = MeetingClientConfig(
            serverUrl, accessToken, ClientIdentity.reference(), MeetingProfile.reference(), Executor { it.run() })
    }
}
