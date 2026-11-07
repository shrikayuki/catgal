package com.catgal.server.task;

import com.catgal.server.service.IFavoriteItemService;
import com.catgal.server.service.IGameStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.catgal.common.constants.RedisConstant.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class GameStatsCheckTask {

    private final IGameStatsService statsService;
    private static final Integer BATCH_SIZE = 100;



    @Scheduled(cron = "0/30 * * * * ?")  // 每30秒同步一次所有统计数据
    public void asyncAllStats() {
        log.info("===== 开始同步所有统计数据 =====");
        long startTime = System.currentTimeMillis();

        try {
            //同步游戏资源数
            statsService.checkGameStats(GAME_STATS_COUNT_CHANGE_SET_KEY, BATCH_SIZE);

            long cost = System.currentTimeMillis() - startTime;
            log.info("===== 所有统计数据同步完成, 耗时: {} ms =====", cost);

        } catch (Exception e) {
            log.error("同步统计数据失败", e);
        }
    }

    @Scheduled(cron = "0 * * * * *")
    public void asyncResourceDownLoadStats() {
        log.info("==== 开始同步资源下载量 ====");
        long startTime = System.currentTimeMillis();

        try {
            //同步游戏资源数
            statsService.checkResourceDownLoad(RESOURCE_DOWNLOAD_COUNT_CHANGE_SET_KEY, BATCH_SIZE);

            long cost = System.currentTimeMillis() - startTime;
            log.info("===== 资源下载量同步完成, 耗时: {} ms =====", cost);

        } catch (Exception e) {
            log.error("同步统计数据失败", e);
        }
    }



}
