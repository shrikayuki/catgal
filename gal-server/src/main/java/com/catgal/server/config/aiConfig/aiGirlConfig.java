package com.catgal.server.config.aiConfig;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class aiGirlConfig {

    @Bean
    public ChatClient chatClient(ChatModel chatModel, MessageChatMemoryAdvisor chatMemoryAdvisor) {
        return ChatClient
                .builder(chatModel)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        chatMemoryAdvisor
                )
                .build();
    }
}
