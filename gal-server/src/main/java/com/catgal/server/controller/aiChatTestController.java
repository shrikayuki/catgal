/*
 * 阅读说明
 * aiChatTest的 HTTP 入口。
 * 路由前缀 /ai，请求绑定后委托 AiServiceClient、CurrentUserProvider。
 * LoginRequired 标记的入口要求登录，上下文用户身份来自服务端认证。
 */
package com.catgal.server.controller;

import com.catgal.server.ai.AiChatCommand;
import com.catgal.server.ai.AiServiceClient;
import com.catgal.server.mvc.annotation.LoginRequired;
import com.catgal.server.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
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

    private final AiServiceClient aiServiceClient;
    private final CurrentUserProvider currentUserProvider;

    @RequestMapping(value ="/chat", produces = "text/html;charset=utf-8")
    public Flux<String> aiChat(String prompt){
        return aiServiceClient.streamChat(new AiChatCommand(
                currentUserProvider.requireUserId(),
                "diagnostic",
                "diagnostic",
                prompt,
                "你是Catgal内部AI连通性测试助手。"
        ));
    }
}
