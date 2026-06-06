package com.catgal.server.config.aiConfig;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RedisChatMemory implements ChatMemory {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String KEY_PREFIX = "gal:chat:memory:";
    private static final int MAX_HISTORY = 20;
    private static final long TTL_DAYS = 7;

    @Override
    public void add(@NotNull String conversationId, @NotNull List<Message> messages) {
        String key = KEY_PREFIX + conversationId;

        log.info("=== add ===");
        log.info("conversationId: {}", conversationId);
        log.info("新增消息数: {}", messages.size());

        for (Message msg : messages) {
            String role = msg.getMessageType().name().toLowerCase();
            String content = msg.getText();
            // 格式: user:你好 或 assistant:你好呀
            String messageStr = role + ":" + content;
            redisTemplate.opsForList().rightPush(key, messageStr);
            log.info("追加消息: {}", messageStr);
        }

        // 只保留最近 MAX_HISTORY 条
        Long size = redisTemplate.opsForList().size(key);
        if (size != null && size > MAX_HISTORY) {
            redisTemplate.opsForList().trim(key, size - MAX_HISTORY, -1);
            log.info("修剪列表, 原大小={}, 保留最近{}条", size, MAX_HISTORY);
        }

        redisTemplate.expire(key, TTL_DAYS, TimeUnit.DAYS);
        log.info("add完成, key={}", key);
    }

    @Override
    public List<Message> get(@NotNull String conversationId) {
        String key = KEY_PREFIX + conversationId;
        List<String> list = redisTemplate.opsForList().range(key, 0, -1);

        if (list == null || list.isEmpty()) {
            log.info("get: key={}, 无历史消息", key);
            return new ArrayList<>();
        }

        List<Message> messages = new ArrayList<>();
        for (String item : list) {
            int idx = item.indexOf(':');
            if (idx == -1) {
                log.warn("格式错误: {}", item);
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

        log.info("get完成: key={}, 读取{}条消息", key, messages.size());
        return messages;
    }

    @Override
    public void clear(@NotNull String conversationId) {
        String key = KEY_PREFIX + conversationId;
        redisTemplate.delete(key);
        log.info("clear完成: key={}", key);
    }
}