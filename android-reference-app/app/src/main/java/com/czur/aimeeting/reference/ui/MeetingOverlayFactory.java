package com.czur.aimeeting.reference.ui;

import android.content.Context;
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.reference.meeting.MeetingSetup;

/**
 * App 的构建期窗口装配点。工厂只创建显示宿主，会议、音源、模型和收口仍由原 Service 持有。
 * 默认实现由互斥 source-set 选择；伙伴在 MeetingAppAssembly 注册自己的工厂即可替换。
 * 创建和全部 Window 调用在主线程；传入 Service Context，不保留 Activity。
 */
@FunctionalInterface
public interface MeetingOverlayFactory {
    /** 三个按钮回调已绑定同一 Service；工厂不改停止／交换语义，不申请权限。 */
    Window create(Context context, Runnable onOpenApp, Runnable onSwapColumns, Runnable onStop);

    /** 最小显示句柄；提醒、权限、前台服务及屏采仍由 App 的原宿主管理。 */
    interface Window {
        /** 初始快照只确定显示模式；无悬浮窗权限返回 false，不弹设置或重开会议。 */
        boolean show(MeetingDisplaySnapshot initial);
        /** 当前是否实际挂载，仅供宿主显示按钮状态。 */
        boolean isShowing();
        /** 完整替换同场显示输入；快照已经配对、路由并冻结，窗口不处理原始模型事件。 */
        void render(MeetingDisplaySnapshot display, MeetingSetup.Mode mode, boolean summaryEnabled,
                String timeText, boolean canStop, int audioLevel);
        /** 释放自身窗口；不是会议停止，宿主结束／退出时调用，可重复调用。 */
        void dismiss();
    }
}
