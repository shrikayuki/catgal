/*
 * 阅读说明
 * 读取已发布游戏的外部资料链接的 HTTP 入口。
 * 路由前缀 /game，请求绑定后委托 ExternalLinkStore。
 * 参数注解决定路径、查询或正文绑定方式，业务可见性与数据组装在服务层核验。
 */
package com.catgal.server.controller;

import com.catgal.common.domain.R;
import com.catgal.server.catalog.external.ExternalLinkStore;
import com.catgal.server.catalog.external.ExternalLinkVO;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/game")
@RequiredArgsConstructor
public class GameExternalLinkController {
    private final ExternalLinkStore links;

    @GetMapping("/{gameId}/external-links")
    @Operation(summary = "读取已发布游戏的外部资料链接", description = "只读取本地已确认关联")
    public R<List<ExternalLinkVO>> links(@PathVariable long gameId) { return R.ok(links.publicLinks(gameId)); }
}
