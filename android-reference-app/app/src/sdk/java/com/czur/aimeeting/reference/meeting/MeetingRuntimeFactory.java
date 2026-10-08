package com.czur.aimeeting.reference.meeting;

import android.content.Context;
import com.czur.aimeeting.client.MeetingClient;
import com.czur.aimeeting.client.MeetingClientConfig;
import com.czur.aimeeting.sdk.AiMeetingClient;

/**
 * 构建期装配完整 SDK，消费App集中登记的Adapter工厂；模型材料和音源仍由SDK处理。
 * 页面及前台服务通过公共 MeetingClient 接口使用，不直接依赖模型运行时。
 */
public final class MeetingRuntimeFactory {
    private MeetingRuntimeFactory() {}

    public static MeetingClient create(Context context, MeetingClientConfig config) {
        return new AiMeetingClient(context, config, MeetingAppAssembly.modelAdapters());
    }

    /** App 负责申请 RECORD_AUDIO 和启动 microphone 前台服务，SDK 不弹权限框。 */
    public static boolean requiresMicrophone() { return true; }
}
