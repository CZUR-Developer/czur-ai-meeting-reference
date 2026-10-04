package com.czur.aimeeting.reference.meeting;

import android.content.Context;

/**
 * 参考 App 的开会条件装配点。合作伙伴二次开发时，只需在 {@link #create(Context)}
 * 返回自己的 {@link MeetingStartCondition} 实现，不需要改动会议页面、模型运行时
 * 或标准事件链。参考版没有扫码接收流程，默认不增加额外开会条件。
 */
public final class MeetingStartConditions {
    private MeetingStartConditions() { }

    /**
     * 创建合作伙伴开会条件。传入应用 Context 便于伙伴读取自己已缓存的接收者状态；
     * 不要在这里或 {@code check()} 内同步访问网络。需要扩展时，替换下面的返回值，
     * 例如返回由伙伴自己实现并装配的接收者条件检查器。
     */
    public static MeetingStartCondition create(Context appContext) {
        return setup -> MeetingStartCondition.Decision.allow();
    }
}
