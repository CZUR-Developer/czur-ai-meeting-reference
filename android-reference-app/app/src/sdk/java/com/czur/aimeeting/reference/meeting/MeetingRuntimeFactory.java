package com.czur.aimeeting.reference.meeting;

import android.content.Context;
import com.czur.aimeeting.client.MeetingClient;
import com.czur.aimeeting.client.MeetingClientConfig;
import com.czur.aimeeting.sdk.AiMeetingClient;

/**
 * 构建期装配完整 SDK；模型材料、默认 Adapter 和音源均由 SDK 内部处理。
 * 页面及前台服务通过公共 MeetingClient 接口使用，不直接依赖模型运行时。
 */
public final class MeetingRuntimeFactory {
    private MeetingRuntimeFactory() {}

    public static MeetingClient create(Context context, MeetingClientConfig config) {
        return new AiMeetingClient(context, config);
    }

    /** App 负责申请 RECORD_AUDIO 和启动 microphone 前台服务，SDK 不弹权限框。 */
    public static boolean requiresMicrophone() { return true; }
}
