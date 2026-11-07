package com.catgal.server.config.aiConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemory implements ChatMemory {

    private final StringRedisTemplate redisTemplate;

    private static final String KEY_PREFIX = "gal:chat:memory:";
    private static final int MAX_HISTORY = 20;
    private static final long TTL_DAYS = 7;

    @Override
    public void add(@NotNull String conversationId, @NotNull List<Message> messages) {
        String key = KEY_PREFIX + conversationId;

        log.debug("写入AI短期记忆: conversationId={}, messageCount={}", conversationId, messages.size());

        for (Message msg : messages) {
            String role = msg.getMessageType().name().toLowerCase();
            String content = msg.getText();
            // 格式: user:你好 或 assistant:你好呀
            String messageStr = role + ":" + content;
            redisTemplate.opsForList().rightPush(key, messageStr);
        }

        // 只保留最近 MAX_HISTORY 条
        Long size = redisTemplate.opsForList().size(key);
        if (size != null && size > MAX_HISTORY) {
            redisTemplate.opsForList().trim(key, size - MAX_HISTORY, -1);
            log.debug("修剪AI短期记忆: originalSize={}, retained={}", size, MAX_HISTORY);
        }

        redisTemplate.expire(key, TTL_DAYS, TimeUnit.DAYS);
    }

    @Override
    public List<Message> get(@NotNull String conversationId) {
        String key = KEY_PREFIX + conversationId;
        List<String> list = redisTemplate.opsForList().range(key, 0, -1);

        if (list == null || list.isEmpty()) {
            log.debug("AI短期记忆未命中: conversationId={}", conversationId);
            return new ArrayList<>();
        }

        List<Message> messages = new ArrayList<>();
        for (String item : list) {
            int idx = item.indexOf(':');
            if (idx == -1) {
                log.warn("AI短期记忆格式错误: conversationId={}", conversationId);
                continue;
            }
            String role = item.substring(0, idx);
            String content = item.substring(idx + 1);

            if ("user".equals(role)) {
                messages.add(new UserMessage(content));
            } else if ("assistant".equals(role)) {
                messages.add(new AssistantMessage(content));
            }
        }

        log.debug("读取AI短期记忆: conversationId={}, messageCount={}", conversationId, messages.size());
        return messages;
    }

    @Override
    public void clear(@NotNull String conversationId) {
        String key = KEY_PREFIX + conversationId;
        redisTemplate.delete(key);
        log.debug("清除AI短期记忆: conversationId={}", conversationId);
    }
}
