package com.czur.aimeeting.client;

/** 部署方配置的客户端身份，不属于系统账号或模型厂商身份。 */
public record ClientIdentity(String appId, String appVersion, String contractVersion, String deviceId) {
    public static ClientIdentity reference() {
        return new ClientIdentity("com.czur.aimeeting.reference", "0.1.0", "v1", "reference-android");
    }
}
