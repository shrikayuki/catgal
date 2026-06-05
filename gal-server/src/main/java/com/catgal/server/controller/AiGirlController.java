package com.catgal.server.controller;


import com.catgal.common.domain.R;
import com.catgal.common.domain.vo.AIGirlVO;
import com.catgal.server.domain.po.AiGirl;
import com.catgal.server.service.IAiGirlService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;

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
    @Operation(description = "开启会话")
    public R<String> newSession(@PathVariable("girlCode") String girlCode){
        String sessionId = aiGirlService.newSession(girlCode);
        return R.ok(sessionId);
    }

    @GetMapping("/history/{girlCode}")
    @Operation(description = "根据不同美少女用户获得对应的会话id集合")
    public R<List<String>> getHistorySessionIds(@PathVariable("girlCode") String girlCode){
        List<String> sessionList = aiGirlService.getHistorySessionIds(girlCode);
        return R.ok(sessionList);
    }

    @PostMapping(value = "/chat/{girlCode}", produces = "text/html;charset=utf-8")
    @Operation(description = "和你的ai美少女聊天")
    public Flux<String> chatWithAIGirl(@PathVariable("girlCode") String girlCode, String sessionId, String userMessage){
        return aiGirlService.chatWithAIGirl(girlCode, sessionId, userMessage);
    }


}
