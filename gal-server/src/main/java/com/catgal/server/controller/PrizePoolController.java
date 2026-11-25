/*
 * 阅读说明
 * 月度奖池的 HTTP 入口。
 * 路由前缀 /prize，请求绑定后委托 IPrizePoolService。
 * LoginRequired 标记的入口要求登录，上下文用户身份来自服务端认证。
 */
package com.catgal.server.controller;


import com.catgal.common.domain.R;
import com.catgal.common.domain.vo.PrizeVO;
import com.catgal.common.domain.vo.UserPrizeVO;
import com.catgal.server.service.IPrizePoolService;
import com.catgal.server.service.IRewardControlService;
import com.catgal.server.service.IRewardTransactionService;
import com.catgal.common.context.UserContext;
import com.catgal.server.mvc.annotation.LoginRequired;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.catgal.common.constants.CutePointsPrizeConstant.PRIZE_POOL_CONSUME;

/**
 * <p>
 * 奖池表 前端控制器
 * </p>
 *
 * @author rance
 * @since 2026-05-25
 */
@RestController
@RequestMapping("/prize")
@RequiredArgsConstructor
public class PrizePoolController {
    private final IPrizePoolService prizeService;
    private final IRewardTransactionService rewards;
    private final IRewardControlService control;

    @GetMapping("/new")
    @Operation(description = "查看当前(最新)奖池")
    public R<List<PrizeVO>> getCurrentPrizePool() {
        List<PrizeVO> vos = prizeService.getCurrentPrizePool();
        return R.ok(vos);
    }

    @PostMapping("/draw")
    @LoginRequired
    @Operation(description = "抽奖接口")
    public R<UserPrizeVO> drawPrizePool(@RequestHeader(value = "Idempotency-Key", required = false) String requestKey) {
        UserPrizeVO vo = prizeService.drawPrizePool(requestKey);
        return R.ok(vo);
    }

    @GetMapping("/draw/requests/{requestKey}")
    @LoginRequired
    @Operation(summary = "恢复本人抽奖请求的已提交结果")
    public R<UserPrizeVO> drawResult(@PathVariable String requestKey) {
        return R.ok(rewards.drawResult(UserContext.getUserId(), requestKey));
    }

    @GetMapping("/mode")
    @Operation(summary = "查询积分业务运行模式")
    public R<java.util.Map<String, Object>> mode() {
        return R.ok(java.util.Map.of("mode", control.mode(), "transactional", control.isActive()));
    }

    @GetMapping("/cost")
    @Operation(description = "抽奖消耗的积分")
    public R<Integer> getCost() {
        return R.ok(Math.abs(PRIZE_POOL_CONSUME));
    }

}
