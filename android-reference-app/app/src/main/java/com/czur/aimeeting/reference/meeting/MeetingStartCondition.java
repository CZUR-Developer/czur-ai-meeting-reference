package com.czur.aimeeting.reference.meeting;

/**
 * 合作伙伴可扩展的会前开会条件。
 *
 * <p>参考 App 已经检查登录、模型能力和所选结果方式的基本输入；本接口只添加
 * 合作伙伴自己的条件，例如“外部接收者已登记”。检查发生在申请录屏权限、启动
 * 会议 Service 和向后端创建会议之前，因此拒绝不会启动模型或产生本场计费。
 * 后端的 Create/Start 权益决定仍然有效，本接口不能替代后端鉴权。</p>
 *
 * <p>{@link #check(MeetingSetup)} 在 Android 主线程调用，只读取已经准备好的
 * 本地状态。需要请求合作伙伴服务器时，应由合作伙伴代码提前异步获取、更新本地
 * 状态，再由此处判断；不要在本方法内发起网络请求或等待扫码轮询。</p>
 */
@FunctionalInterface
public interface MeetingStartCondition {
    /**
     * 检查用户刚刚确认的本场会前设置。每次点击“开始”都会重新调用；返回拒绝时，
     * {@code message} 直接显示在参考 App 的状态栏，会议保持在会前页面。
     */
    Decision check(MeetingSetup setup);

    /** 开会条件的用户可见结果；默认参考实现始终返回 {@link #allow()}。 */
    record Decision(boolean allowed, String message) {
        public static Decision allow() {
            return new Decision(true, "");
        }

        public static Decision block(String message) {
            return new Decision(false, message);
        }
    }
}
