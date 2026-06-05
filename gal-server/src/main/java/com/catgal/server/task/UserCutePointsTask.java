package com.catgal.server.task;

import com.catgal.common.constants.RedisConstant;
import com.catgal.server.service.IUserCutePointsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class UserCutePointsTask {

    private final IUserCutePointsService userCutePointsService;
    private static final Integer BATCH_SIZE = 100;

    @Scheduled(fixedRate = 60000)
    public void userCutePointsToDB() {
        log.info("userCutePointsToDB开始执行");
        userCutePointsService.userCutePointsToDB(RedisConstant.CUTE_POINTS_CHANGE_SET_KEY, BATCH_SIZE);
        log.info("userCutePointsToDB结束");
    }
}
