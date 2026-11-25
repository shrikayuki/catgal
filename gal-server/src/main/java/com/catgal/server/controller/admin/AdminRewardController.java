package com.catgal.server.controller.admin;

import com.catgal.common.domain.R;
import com.catgal.server.service.IRewardControlService;
import com.catgal.server.service.IRewardOutboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** /admin 认证链保护所有入口。切换操作还会在持锁后重新核验操作者当前角色。 */
@RestController
@RequestMapping("/admin/rewards")
@RequiredArgsConstructor
@Tag(name = "后台管理 - 积分事务与可靠通知")
public class AdminRewardController {
    private final IRewardControlService control;
    private final IRewardOutboxService outbox;

    @GetMapping("/status") @Operation(summary = "查看切换进度与旧任务阻塞项")
    public R<Map<String, Object>> status() { return R.ok(control.status()); }
    @PostMapping("/drain") @Operation(summary = "停止新的旧流程签到、抽奖和奖池维护，允许存量任务排空")
    public R<Map<String, Object>> drain() { return R.ok(control.beginDrain()); }
    @PostMapping("/baseline") @Operation(summary = "核对下一批最多25个用户的余额和签到基线")
    public R<Map<String, Object>> baseline() { return R.ok(control.importBaseline()); }
    @PostMapping("/activate") @Operation(summary = "核对完成后激活 MySQL 事务流程，禁止回退旧写入")
    public R<Map<String, Object>> activate() { return R.ok(control.activate()); }
    @PostMapping("/cancel-drain") @Operation(summary = "激活前取消排空并恢复旧流程")
    public R<Map<String, Object>> cancel() { return R.ok(control.cancelDrain()); }
    @GetMapping("/outbox") @Operation(summary = "查看通知积压和最近30个事件")
    public R<Map<String, Object>> outbox() { return R.ok(outbox.overview()); }
    @PostMapping("/outbox/{eventId}/retry") @Operation(summary = "提前重试未完成且租约已释放的通知")
    public R<Void> retry(@PathVariable Long eventId) { outbox.retry(eventId); return R.ok(); }
}
