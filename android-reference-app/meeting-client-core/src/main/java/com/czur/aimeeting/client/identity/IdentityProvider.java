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
}
