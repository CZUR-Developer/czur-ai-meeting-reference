package com.czur.aimeeting.client.identity

import java.io.IOException

/**
 * 可替换身份来源，页面刷新／登录返回及开会前在 IO 队列读取。可读应用会话、ContentProvider
 * 或自有账号服务，不在此打开 Activity、申请权限或操作 View。需要登录返回类型化状态，
 * App 自行打开入口。设备 token 与部署后端 token 无天然等价，交换由部署方实现。
 */
fun interface IdentityProvider {
    /** 最新身份成功后冻结为单场快照；提示不包含凭据。读取服务故障可抛 IOException。 */
    @Throws(IOException::class)
    fun currentIdentity(): IdentityResult

    /**
     * 可选同账号收尾续签，只在尾部确认后的发布／Stop／结果阶段调用，不建新场或切账号。
     * 默认保留原凭据，不读 currentIdentity；重写者先按原 meetingId 核对同一账号归属，
     * 再走正常认证。成功同步更新原记录、上下文及 HTTP，音频／用量／Stop 键均保持。
     * IO 队列运行，不打开 UI；未登录返回对应状态，异常保留原场供下次确认。
     */
    @Throws(IOException::class)
    fun identityForFinalization(meetingId: String, originalIdentity: IdentitySession): IdentityResult =
        IdentityResult.loggedIn(originalIdentity)
}
