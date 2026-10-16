package com.czur.aimeeting.client.identity;

import java.io.IOException;

/**
 * 可替换的当前身份来源。SDK在进入页面／登录返回的刷新请求，以及每次开始会议前调用。
 *
 * <p>实现可以读取应用内会话、ContentProvider或自己的账号服务。该方法运行在会议客户端
 * 的IO队列，可以同步读取；不要在这里打开Activity、申请权限或操作View。需要登录时返回
 * {@link IdentityResult#loginRequired(String)}，由App打开对应登录入口。</p>
 *
 * <p>已登录会话必须使用本部署会议后端认可的凭据。设备系统的token与参考后端token
 * 没有天然等价关系；需要交换时由部署方实现。本地返回已登录仍需后端批准开会及能力。</p>
 */
@FunctionalInterface
public interface IdentityProvider {
    /**
     * 读取调用时的最新身份。一次成功读取形成一场会议的凭据快照，后续页面刷新不会
     * 替换正在运行会议的凭据；账号切换、退出和登录页面仍由具体实现及App负责。
     *
     * @return 已登录、需要登录或读取失败的类型化结果；不得把敏感凭据放在提示中
     * @throws IOException 读取服务不可用；SDK转换为可读的身份读取失败提示
     */
    IdentityResult currentIdentity() throws IOException;

    /**
     * 已停止原场再次确认、或宿主重开续办时，可选取得同一原账号的新会议凭据。
     *
     * <p>此方法只在原场尾部全部确认后的发布／Stop／结果读取阶段调用，不用于会中
     * 切换账号，也不会重新 Create、采音或调用模型。默认实现原样保留已冻结凭据，
     * 不调用 {@link #currentIdentity()}，因而已有伙伴实现不会追随后来登录的账号。
     * 需要续签的部署必须显式重写：先按 meetingId 读取自己的原账号归属，核对调用时
     * 仍为同一账号，再通过该部署正常认证流程取得会话；账号变化或需要登录时返回
     * 对应状态，不打开界面。SDK 不解析凭据或使用 displayName 判定账号相同。</p>
     *
     * <p>调用在唯一 IO 队列执行。成功后 SDK 同步更新原停止记录、发布上下文和后续
     * Stop／结果 HTTP 所用凭据；会议、音频、冻结用量、停止幂等键及已确认阶段不变。
     * 普通首次停止及已 READY 记录不额外调用此入口。</p>
     *
     * @param meetingId 待完成的原会议，用于部署私有的归属核对；不是新会议编号
     * @param originalIdentity 原场当前保存的访问会话，展示名可能为空，不能当作账号标识
     * @return 获准访问同一原场的会话，或未登录／读取失败状态；提示及日志不得带凭据
     * @throws IOException 正常认证暂不可用，保留原场供下次正常确认
     */
    default IdentityResult identityForFinalization(String meetingId, IdentitySession originalIdentity)
            throws IOException {
        return IdentityResult.loggedIn(originalIdentity);
    }
}
