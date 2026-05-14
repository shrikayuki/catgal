package com.catgal.server.task;

import com.catgal.server.service.ILikeRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.catgal.common.constants.LikeBizTypeConstant.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class LikedTimesCheckTask {

    private static final List<String> BIZ_TYPES = List.of(LIKE_TYPE_COMMENT, LIKE_TYPE_REVIEW, LIKE_TYPE_RESOURCE);
    private final ILikeRecordService recordService;
    private static final int MAX_BIZ_SIZE=40;

    @Scheduled(fixedDelay = 20000)
    public void checkLikedTimes(){
        log.info("点赞数同步定时任务开启");
        for (String bizType : BIZ_TYPES) {
            recordService.readLikedTimesAndSendMessage(bizType,MAX_BIZ_SIZE);
        }
        log.info("点赞数定时任务结束");
    }
}
