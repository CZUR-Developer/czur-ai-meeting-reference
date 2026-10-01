package com.czur.reference.application.http;

/**
 * HTTP Authorization 的 RFC 6750 Bearer 形状检查，仅供同包入口复用。
 *
 * <p>只处理 {@code "Bearer" 1*SP b64token} 语法，不验证 Token 的签名、过期、
 * 受众或权限。有效时返回不含前缀的原始 Token，调用方必须将其视为秘密并尽快
 * 创建短生命周期凭据；无效时返回 null，让各路由映射自己的固定 401 响应，
 * 不在异常或日志中回显输入。该类不持有状态，也不是认证框架。</p>
 */
final class BearerAuthorization {
    private BearerAuthorization() {
    }

    /** 有效 Token 长度上限，与已验收的 Create 入站行为一致。 */
    static final int MAX_TOKEN_CHARS = 8192;

    /**
     * 从单值 Authorization 中提取 Bearer token68；无效、缺失时返回 null。
     *
     * <p>scheme 大小写不敏感，允许多个 ASCII SP；HTAB、token 内空格、
     * 非 token68 字符和非尾部的 {@code =} 均拒绝。返回值可能包含秘密，
     * 调用方不得日志输出或保存在长生命周期对象中。</p>
     */
    static String tokenOrNull(String authorization) {
        if (authorization == null || authorization.length() < 8
                || !authorization.regionMatches(true, 0, "Bearer", 0, 6)
                || authorization.charAt(6) != ' ') {
            return null;
        }
        int tokenStart = 7;
        while (tokenStart < authorization.length() && authorization.charAt(tokenStart) == ' ') {
            tokenStart++;
        }
        String token = authorization.substring(tokenStart);
        if (token.isEmpty() || token.length() > MAX_TOKEN_CHARS) {
            return null;
        }
        boolean padding = false;
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            if (character == '=') {
                padding = true;
            } else if (padding || !isTokenCharacter(character)) {
                return null;
            }
        }
        if (padding && token.charAt(0) == '=') {
            return null;
        }
        return token;
    }

    private static boolean isTokenCharacter(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9') || value == '-' || value == '.'
                || value == '_' || value == '~' || value == '+' || value == '/';
    }
}
