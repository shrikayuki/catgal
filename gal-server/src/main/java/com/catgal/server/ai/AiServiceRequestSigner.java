/*
 * 阅读说明
 * 对时间戳、nonce、方法、路径和正文摘要计算 HMAC。
 * Java 与 Python 必须使用相同规范化顺序，签名用于校验内部请求完整性。
 */
package com.catgal.server.ai;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class AiServiceRequestSigner {

    public String sign(String secret,
                       String timestamp,
                       String nonce,
                       String method,
                       String path,
                       byte[] body) {
        try {
            String bodyHash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(body)
            );
            String canonical = String.join("\n",
                    timestamp,
                    nonce,
                    method.toUpperCase(),
                    path,
                    bodyHash
            );
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法生成AI服务请求签名", exception);
        }
    }
}
