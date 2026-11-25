/*
 * 阅读说明
 * 浏览器聊天正文，携带会话标识与消息内容，角色标识由对应入口提供。
 */
package com.catgal.server.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AiChatBody(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String sessionId,
        @NotBlank @Size(max = 4000) String content
) { }
