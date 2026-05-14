package com.catgal.server.task;

import com.catgal.server.service.IFavoriteItemService;
import com.catgal.server.service.ILikeRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class FavoriteItemSyncTask {


    private final IFavoriteItemService itemService;
    private static final Integer BATCH_SIZE = 100;

    @Async("asyncTaskExecutor")
    @Scheduled(cron = "0/10 * * * * ?")
    public void fullSyncFavoriteItemsToDBTask(){
        log.info("===== 收藏记录同步任务开始 =====");  // 加这行
        itemService.fullSyncFavoriteItemsToDBTask(BATCH_SIZE);
        log.info("===== 收藏记录同步任务结束 =====");
    }

}
