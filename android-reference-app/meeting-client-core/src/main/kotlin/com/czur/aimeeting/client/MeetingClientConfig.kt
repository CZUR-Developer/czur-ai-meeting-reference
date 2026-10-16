package com.czur.aimeeting.client

import com.czur.aimeeting.client.identity.IdentityProvider
import com.czur.aimeeting.client.identity.IdentitySession
import com.czur.aimeeting.client.identity.ReferenceIdentityProvider
import java.util.Optional
import java.util.concurrent.Executor

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
) {
    constructor(serverUrl: String, accessToken: String, clientIdentity: ClientIdentity,
        profile: MeetingProfile, callbackExecutor: Executor) : this(serverUrl, accessToken,
        ReferenceIdentityProvider(accessToken), clientIdentity, profile, callbackExecutor,
        MeetingArtifactPublisher.none(), null)

    /** Provider 可由页面／Service 共用，SDK 不持有 Activity 或打开登录 UI。 */
    constructor(serverUrl: String, identityProvider: IdentityProvider, clientIdentity: ClientIdentity,
        profile: MeetingProfile, callbackExecutor: Executor) : this(serverUrl, "", identityProvider,
        clientIdentity, profile, callbackExecutor, MeetingArtifactPublisher.none(), null)

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
    /** 不改变原配置，不开启录音，不发生上传；发布器只使用本场 Context。 */
    fun withArtifactPublisher(publisher: MeetingArtifactPublisher): MeetingClientConfig = MeetingClientConfig(
        serverUrl, accessToken, identityProvider, clientIdentity, profile, callbackExecutor, publisher, stoppedMeetingStore)
    /** 只注册应用私有停止记录端口，不写文件、不创建恢复任务。 */
    fun withStoppedMeetingStore(store: StoppedMeetingStore): MeetingClientConfig = MeetingClientConfig(
        serverUrl, accessToken, identityProvider, clientIdentity, profile, callbackExecutor, artifactPublisher, store)
    /** 组合层冻结本场凭据，避免账号刷新改变场内请求身份。 */
    fun forSession(session: IdentitySession): MeetingClientConfig = MeetingClientConfig(
        serverUrl, session.accessToken(), identityProvider, clientIdentity, profile, callbackExecutor,
        artifactPublisher, stoppedMeetingStore)

    companion object {
        /** 旧参考 App 薄适配，伙伴应配置自己的身份／档位。 */
        @JvmStatic fun reference(serverUrl: String, accessToken: String): MeetingClientConfig = MeetingClientConfig(
            serverUrl, accessToken, ClientIdentity.reference(), MeetingProfile.reference(), Executor { it.run() })
    }
}
