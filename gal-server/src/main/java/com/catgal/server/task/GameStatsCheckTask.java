package com.catgal.server.task;

import com.catgal.server.service.IFavoriteItemService;
import com.catgal.server.service.IGameStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.catgal.common.constants.RedisConstant.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class GameStatsCheckTask {

    private final IGameStatsService statsService;
    private static final Integer BATCH_SIZE = 100;

    @Async("asyncTaskExecutor")
    @Scheduled(cron = "0/10 * * * * ?")
    public void checkFavoriteTimes() {
        log.info("===== checkFavoriteTimes 执行了 =====");
        statsService.checkFavoriteTimes(GAME_FAVORITE_COUNT_CHANGE_SET_KEY, BATCH_SIZE);
    }

    @Async("asyncTaskExecutor")
    @Scheduled(cron = "0/10 * * * * ?")
    public void checkLookTimes() {
        log.info("===== checkLookTimes 执行了 =====");
        statsService.checkFavoriteTimes(GAME_LOOK_COUNT_CHANGE_SET_KEY, BATCH_SIZE);
    }

    @Async("asyncTaskExecutor")
    @Scheduled(cron = "0/10 * * * * ?")
    public void checkCommentTimes() {
        log.info("===== checkCommentTimes 执行了 =====");
        statsService.checkFavoriteTimes(GAME_COMMENT_COUNT_CHANGE_SET_KEY, BATCH_SIZE);
    }

    @Async("asyncTaskExecutor")
    @Scheduled(cron = "0/10 * * * * ?")
    public void checkReviewTimes() {
        log.info("===== checkReviewTimes 执行了 =====");
        statsService.checkFavoriteTimes(GAME_REVIEW_COUNT_CHANGE_SET_KEY, BATCH_SIZE);
    }



}
