/*
 * 阅读说明
 * 发送消息并返回结构化 SSE的 HTTP 入口。
 * 路由前缀 /ai-girl，请求绑定后委托 IAiGirlService。
 * LoginRequired 标记的入口要求登录，上下文用户身份来自服务端认证。
 */
package com.catgal.server.controller;


import com.catgal.common.domain.R;
import com.catgal.common.domain.vo.AIGirlVO;
import com.catgal.server.ai.AiChatBody;
import com.catgal.server.ai.AiChatEvent;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import com.catgal.server.mvc.annotation.LoginRequired;
import com.catgal.server.service.IAiGirlService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Flux;

import java.util.List;

/**
 * <p>
 * AI老婆角色表 前端控制器
 * </p>
 *
 * @author rance
 * @since 2026-06-01
 */
@RestController
@RequestMapping("/ai-girl")
@RequiredArgsConstructor
public class AiGirlController {

    private final IAiGirlService aiGirlService;

    @GetMapping("/list")
    @Operation(description = "查询ai美少女列表, 你可以从列表选择聊天")
    public R<List<AIGirlVO>> selectAllGirl(){
        List<AIGirlVO> vos = aiGirlService.selectAllGirl();
        return R.ok(vos);
    }

    @PostMapping("/new/{girlCode}")
    @LoginRequired
    @Operation(description = "开启会话")
    public R<String> newSession(@PathVariable("girlCode") String girlCode){
        String sessionId = aiGirlService.newSession(girlCode);
        return R.ok(sessionId);
    }

    @GetMapping("/history/{girlCode}")
    @LoginRequired
    @Operation(description = "根据不同美少女用户获得对应的会话id集合")
    public R<List<String>> getHistorySessionIds(@PathVariable("girlCode") String girlCode){
        List<String> sessionList = aiGirlService.getHistorySessionIds(girlCode);
        return R.ok(sessionList);
    }

    @PostMapping(value = "/chat/{girlCode}", produces = "text/html;charset=utf-8")
    @LoginRequired
    @Operation(description = "和你的ai美少女聊天")
    public Flux<String> chatWithAIGirl(@PathVariable("girlCode") String girlCode, String sessionId, String userMessage){
        return aiGirlService.chatWithAIGirl(girlCode, sessionId, userMessage);
    }

    @PostMapping(value = "/chat/{girlCode}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @LoginRequired
    @Operation(summary = "发送消息并返回结构化 SSE", description = "meta、delta、done 或 error。客户端断开即取消生成")
    public Flux<ServerSentEvent<AiChatEvent>> streamChat(@PathVariable String girlCode,
                                                       @Valid @RequestBody AiChatBody body,
                                                       HttpServletResponse response) {
        Flux<AiChatEvent> events = aiGirlService.streamChat(girlCode, body.sessionId(), body.content());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("X-Content-Type-Options", "nosniff");
        return events.map(event -> ServerSentEvent.builder(event).event(event.type()).build());
    }


}
