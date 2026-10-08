package com.czur.aimeeting.client.result;

import com.czur.aimeeting.client.MeetingOptions;
import java.util.Objects;

/**
 * 本场结果处理选择：一个稳定字符串动作ID，与是否主动交付的产品策略相互独立。
 * 本对象不持有邮箱、文件路径或平台页面；动作参数由插件在会前准备时冻结。
 * 不主动交付只保留既有Stop后ENDED行为，不表示删除服务端结果或改变保存／计费。
 */
public record ResultOptions(Policy policy, String actionId) {
    public enum Policy { READY, NO_ACTIVE_DELIVERY }

    public ResultOptions {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(actionId, "actionId");
        if (policy == Policy.READY && actionId.isBlank())
            throw new IllegalArgumentException("正常结果需指定动作ID");
    }

    public static ResultOptions action(String actionId) { return new ResultOptions(Policy.READY, actionId); }
    public static ResultOptions noActiveDelivery() {
        return new ResultOptions(Policy.NO_ACTIVE_DELIVERY, "");
    }

    /** 旧枚举只在此转换；新增伙伴动作使用自己的ID，不扩充固定枚举。 */
    public static ResultOptions fromLegacy(MeetingOptions.ResultDelivery delivery) {
        return switch (delivery) {
            case WEB -> action("web");
            case LOCAL -> action("local.pdf");
            case EMAIL -> action("email");
            case TRACELESS -> noActiveDelivery();
        };
    }

    /**
     * 供保留旧访问器的构造器使用。伙伴ID没有对应旧枚举时仅返回旧WEB占位值，
     * 不能据此判定实际执行了Web或通知成功；实际动作始终以actionId与注册表为准。
     */
    public MeetingOptions.ResultDelivery legacyDelivery() {
        if (policy == Policy.NO_ACTIVE_DELIVERY) return MeetingOptions.ResultDelivery.TRACELESS;
        return switch (actionId) {
            case "local.pdf" -> MeetingOptions.ResultDelivery.LOCAL;
            case "email" -> MeetingOptions.ResultDelivery.EMAIL;
            default -> MeetingOptions.ResultDelivery.WEB;
        };
    }
}
