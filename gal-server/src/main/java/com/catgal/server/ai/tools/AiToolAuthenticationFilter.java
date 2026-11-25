/*
 * 阅读说明
 * 验证 Python 回调 Java 的内部 HMAC 请求，包括时间窗口、正文摘要和 nonce 防重放。
 * 签名通过后建立内部工具调用身份，后续业务仍执行目标与可见性校验。
 */
package com.catgal.server.ai.tools;

import com.catgal.common.domain.R;
import com.catgal.server.ai.AiServiceProperties;
import com.catgal.server.ai.AiServiceRequestSigner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Authenticates the fixed Python-to-Java read-only tool boundary. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class AiToolAuthenticationFilter extends OncePerRequestFilter {
    public static final String USER_ATTRIBUTE = AiToolAuthenticationFilter.class.getName() + ".userId";
    public static final String SERVICE_ATTRIBUTE = AiToolAuthenticationFilter.class.getName() + ".service";
    private static final int MAX_BODY_BYTES = 65_536;
    private static final int MAX_NONCES = 20_000;
    private static final Set<String> PATHS = Set.of(
            "/internal/ai/tools/search-games", "/internal/ai/tools/game-detail",
            "/internal/ai/tools/my-interests", "/internal/ai/tools/validate-games");
    private static final Set<String> SERVICE_PATHS = Set.of(
            "/internal/ai/vector-catalog/pending", "/internal/ai/vector-catalog/export",
            "/internal/ai/vector-catalog/ack", "/internal/ai/vector-catalog/failed",
            "/internal/ai/vector-catalog/rebuild", "/internal/ai/vector-catalog/status");
    private final AiToolProperties tools;
    private final AiServiceProperties service;
    private final AiServiceRequestSigner signer;
    private final ObjectMapper mapper;
    private final Map<String, Long> nonces = new HashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/ai/tools/")
                && !request.getRequestURI().startsWith("/internal/ai/vector-catalog/");
    }

    @Override
    // 内部调用先核验请求头、时间窗口和正文签名，再以 nonce 防重放后设置工具身份。
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean serviceRequest = SERVICE_PATHS.contains(path);
        boolean semanticRequest = serviceRequest || "/internal/ai/tools/validate-games".equals(path);
        if (!(serviceRequest ? tools.isSemanticSearchEnabled() : tools.isEnabled())
                || (semanticRequest && !tools.isSemanticSearchEnabled())
                || service.getHmacSecret() == null || service.getHmacSecret().length() < 32) {
            reject(response, 503, "AI工具服务未启用");
            return;
        }
        if (!PATHS.contains(path) && !serviceRequest) {
            reject(response, 404, "AI工具不存在");
            return;
        }
        if (!"POST".equals(request.getMethod())) {
            reject(response, 405, "AI工具仅接受POST");
            return;
        }
        String timestamp = request.getHeader("X-Catgal-Timestamp");
        String nonce = request.getHeader("X-Catgal-Nonce");
        String signature = request.getHeader("X-Catgal-Signature");
        if (!validHeaders(timestamp, nonce, signature)) {
            reject(response, 401, "AI工具签名无效或已过期");
            return;
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            reject(response, 413, "AI工具请求过大");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            reject(response, 413, "AI工具请求过大");
            return;
        }
        String expected = signer.sign(service.getHmacSecret(), timestamp, nonce, "POST",
                request.getRequestURI(), body);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII))) {
            reject(response, 401, "AI工具签名无效或已过期");
            return;
        }
        JsonNode context = readContext(body);
        Long userId = readUser(context);
        if (context == null || (serviceRequest ? context.has("userId") : userId == null)) {
            reject(response, 400, "AI工具用户上下文无效");
            return;
        }
        int accepted = acceptNonce(nonce);
        if (accepted != 200) {
            reject(response, accepted, accepted == 409 ? "AI工具请求已使用" : "AI工具服务繁忙");
            return;
        }
        if (serviceRequest) request.setAttribute(SERVICE_ATTRIBUTE, Boolean.TRUE);
        else request.setAttribute(USER_ATTRIBUTE, userId);
        chain.doFilter(new ReplayableRequest(request, body), response);
    }

    private boolean validHeaders(String timestamp, String nonce, String signature) {
        if (timestamp == null || !timestamp.matches("[0-9]{1,12}") || nonce == null
                || !nonce.matches("[A-Za-z0-9_-]{16,128}") || signature == null
                || !signature.matches("[0-9a-f]{64}")) return false;
        long age = Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp));
        return age <= Math.max(1, Math.min(300, tools.getRequestMaxAgeSeconds()));
    }

    private JsonNode readContext(byte[] body) {
        try {
            JsonNode value = mapper.readTree(body);
            return value != null && value.isObject() ? value : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private Long readUser(JsonNode context) {
        if (context == null) return null;
        JsonNode user = context.path("userId");
        return user.isIntegralNumber() && user.canConvertToLong() && user.longValue() > 0
                ? user.longValue() : null;
    }

    // 同一 nonce 仅允许一次成功接收，存储失败时按认证失败路径处理。
    private synchronized int acceptNonce(String nonce) {
        long now = Instant.now().getEpochSecond();
        nonces.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (nonces.containsKey(nonce)) return 409;
        if (nonces.size() >= MAX_NONCES) return 503;
        // A future-dated accepted request can remain valid for two clock windows.
        nonces.put(nonce, now + 2L * Math.max(1, Math.min(300, tools.getRequestMaxAgeSeconds())) + 1);
        return 200;
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), R.error(status, message));
    }

    private static final class ReplayableRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private ReplayableRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream stream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return stream.read(); }
                @Override public boolean isFinished() { return stream.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    try {
                        if (isFinished()) listener.onAllDataRead();
                        else listener.onDataAvailable();
                    } catch (IOException exception) { listener.onError(exception); }
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
