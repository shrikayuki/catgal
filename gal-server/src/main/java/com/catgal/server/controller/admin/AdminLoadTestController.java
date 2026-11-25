/*
 * 阅读说明
 * 查看只读并发测试场景和执行上限、查看当前进程最近20次并发测试、异步创建只读并发测试的 HTTP 入口。
 * 路由前缀 /admin/load-tests，请求绑定后委托 LoadTestService。
 * 管理员访问由 /admin 拦截器链执行，复杂状态与事务规则位于被调用的 Service。
 */
package com.catgal.server.controller.admin;

import com.catgal.common.context.UserContext;
import com.catgal.common.domain.R;
import com.catgal.server.loadtest.LoadTestService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/load-tests")
@RequiredArgsConstructor
public class AdminLoadTestController {
    private final LoadTestService tests;

    @GetMapping("/scenarios")
    @Operation(summary = "查看只读并发测试场景和执行上限")
    public R<LoadTestService.Catalog> scenarios() { return R.ok(tests.catalog()); }

    @GetMapping
    @Operation(summary = "查看当前进程最近20次并发测试")
    public R<List<LoadTestService.View>> list() { return R.ok(tests.list()); }

    @PostMapping
    @Operation(summary = "异步创建只读并发测试", description = "固定本地目标，同一进程仅运行一个任务，默认关闭")
    public R<LoadTestService.View> start(@Valid @RequestBody LoadTestService.Start request) {
        return R.ok(tests.start(request, UserContext.getUserId()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查看实时并发测试指标")
    public R<LoadTestService.View> get(@PathVariable String id) { return R.ok(tests.get(id)); }

    @PostMapping("/{id}/stop")
    @Operation(summary = "停止发起新测试请求", description = "在途请求最多等待4秒结束，期间保留任务互斥")
    public R<LoadTestService.View> stop(@PathVariable String id) { return R.ok(tests.cancel(id)); }
}
