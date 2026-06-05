package com.catgal.server.controller;


import com.catgal.common.domain.R;
import com.catgal.common.domain.vo.PrizeVO;
import com.catgal.common.domain.vo.UserPrizeVO;
import com.catgal.server.service.IPrizePoolService;
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

    @GetMapping("/new")
    @Operation(description = "查看当前(最新)奖池")
    public R<List<PrizeVO>> getCurrentPrizePool() {
        List<PrizeVO> vos = prizeService.getCurrentPrizePool();
        return R.ok(vos);
    }

    @PostMapping("/draw")
    @Operation(description = "抽奖接口")
    public R<UserPrizeVO> drawPrizePool() {
        UserPrizeVO vo = prizeService.drawPrizePool();
        return R.ok(vo);
    }

    @GetMapping("/cost")
    @Operation(description = "抽奖消耗的积分")
    public R<Integer> getCost() {
        return R.ok(Math.abs(PRIZE_POOL_CONSUME));
    }

}
