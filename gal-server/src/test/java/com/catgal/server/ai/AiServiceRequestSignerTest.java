package com.catgal.server.ai;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiServiceRequestSignerTest {

    @Test
    void matchesPythonHmacContract() {
        AiServiceRequestSigner signer = new AiServiceRequestSigner();

        String signature = signer.sign(
                "test-secret-that-is-long-enough-for-hmac",
                "1794042000",
                "nonce-12345678",
                "POST",
                "/internal/v1/chat/stream",
                "{\"message\":\"你好\"}".getBytes(StandardCharsets.UTF_8)
        );

        assertEquals("4233f297cee84b7ed7eb4f8187f455fd81b067752d9b3b18bc931110f19b9c92",
                signature);
    }
}
