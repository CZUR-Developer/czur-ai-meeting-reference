package com.czur.reference.application.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 纯 Bearer 形状边界；不验证身份，也不创建凭据。 */
class BearerAuthorizationTest {
    @Test
    void acceptsThePreviouslySupportedToken68AndAsciiSpaces() {
        assertEquals("opaque.private+token==", BearerAuthorization.tokenOrNull(
                "Bearer opaque.private+token=="));
        assertEquals("AZaz09-._~+/=", BearerAuthorization.tokenOrNull(
                "bEaReR  AZaz09-._~+/="));
        assertEquals("a".repeat(BearerAuthorization.MAX_TOKEN_CHARS),
                BearerAuthorization.tokenOrNull("Bearer "
                        + "a".repeat(BearerAuthorization.MAX_TOKEN_CHARS)));
    }

    @Test
    void rejectsMissingMalformedOrOversizedTokenWithoutReturningInput() {
        for (String value : new String[]{"", "Basic abc", "Bearer", "Bearer ",
                "Bearer\tabc", "Bearer  a b", "Bearer a@b", "Bearer a=b",
                "Bearer ===", "Bearer a,b", "Bearer a%b", "Bearer a\"b",
                "Bearer aé", "Bearer " + "a".repeat(BearerAuthorization.MAX_TOKEN_CHARS + 1)}) {
            assertNull(BearerAuthorization.tokenOrNull(value));
        }
        assertNull(BearerAuthorization.tokenOrNull(null));
    }
}
