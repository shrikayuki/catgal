package com.catgal.server.task;

import com.catgal.common.constants.RedisConstant;
import com.catgal.server.service.IPrizePoolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class PrizeStockTask {

    private static final Integer BATCH_SIZE = 100;
    private final IPrizePoolService prizePoolService;

    @Scheduled(fixedRate = 1000000000)
    public void prizeStockToDB(){
        log.info("--------奖池库存同步中----------");
        prizePoolService.prizeStockToDB(RedisConstant.PRIZE_POOL_CHANGE_SET_KEY, BATCH_SIZE);
        log.info("------------奖池库存同步结束--------------");
    }

}
