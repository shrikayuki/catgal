package com.catgal.server.task;

import com.catgal.server.service.ILikeRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.catgal.common.constants.LikeBizTypeConstant.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class LikeRecordSyncTask {

    private final ILikeRecordService likeService;
    private static final int BATCH_SIZE = 500;      // 每批处理500条
    private static final int BATCH_BIZ_SIZE = 50;   // 每批处理50个bizId
    private static final List<String> BIZ_LIST = List.of(LIKE_TYPE_COMMENT, LIKE_TYPE_REVIEW, LIKE_TYPE_RESOURCE);
    /**
     * 全量同步点赞记录
     */
    @Async("asyncTaskExecutor")
    @Scheduled(cron = "0/10 * * * * ?")
    public void fullSyncLikeRecordsToDBTask() {
        log.info("同步点赞记录");
        for (String bizType : BIZ_LIST) {
            likeService.fullSyncLikeRecordsToDB(bizType, BATCH_SIZE, BATCH_BIZ_SIZE);
        }
    }
}
