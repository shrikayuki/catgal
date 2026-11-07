package com.catgal.server.controller;

import com.catgal.server.mvc.annotation.LoginRequired;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
@LoginRequired
@ConditionalOnProperty(prefix = "catgal.ai", name = "test-endpoint-enabled", havingValue = "true")
public class aiChatTestController {

    private final ChatClient chatClient;

    @RequestMapping(value ="/chat", produces = "text/html;charset=utf-8")
    public Flux<String> aiChat(String prompt){
        return chatClient.prompt().user(prompt).stream().content();
    }
}
