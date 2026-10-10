package com.czur.reference.application.http;

/**
 * 部署方可选登录入口。共享HTTP运行时只负责匹配路径并分发，不解析账号或密码。
 * 默认核心装配不提供登录；参考应用可注入预置账号入口，生产宿主使用自己的账号链。
 */
public interface SessionLoginEndpoint {
    /** 返回此扩展唯一的HTTP路径；应与部署公开协议一致。 */
    String path();
    /** 处理已匹配路径的请求，方法及正文校验由部署方实现负责。 */
    HttpResponse handle(HttpRequest request);
}
