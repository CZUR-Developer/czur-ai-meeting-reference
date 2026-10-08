package com.czur.aimeeting.client.result;

import com.czur.aimeeting.client.MeetingClient;
import com.czur.aimeeting.client.MeetingResultContent;
import java.util.List;

/**
 * 一场真实READY结果的窄协议访问。SDK持有同场冻结身份与genTime，App／插件看不到原client。
 * 两方法异步使用SDK现有IO队列，回调使用配置的串行callbackExecutor；不能在UI等待返回。
 * 会前、会中、ENDED均不可读取结果；client/session关闭后不再交付尚未派发的回调。
 * 动作完成／用户结束结果交互时由App释放原client；只承诺当前进程，不做自动恢复或重试。
 */
public interface ResultActionAccess {
    /** 按需读取已有typed字幕／译文／总结／截图；Web或纯提示不应调用，不重新Stop。 */
    void readContent(MeetingClient.Callback<MeetingResultContent> callback);
    /**
     * 复用已有邮件端点发送同场有效期链接；收件参数由插件会前冻结，部署需真实启用邮件。
     * 失败仅返回本地错误提示，不改变READY；用户手动再执行是否重复发送由具体服务决定。
     */
    void sendEmail(List<String> recipients, String resultUrl, MeetingClient.Callback<Void> callback);
}
