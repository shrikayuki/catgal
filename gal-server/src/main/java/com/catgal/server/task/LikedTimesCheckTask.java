/*
 * 阅读说明
 * 触发点赞数量校准通知，具体消息发送与完整性验证由点赞服务处理。
 */
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
            try {
                recordService.readLikedTimesAndSendMessage(bizType, MAX_BIZ_SIZE);
            } catch (RuntimeException failure) {
                // 单个业务类型有积压或发布失败时，处理器保留该批任务，其他类型仍继续本轮同步。
                log.warn("点赞计数同步批次失败, bizType={}", bizType, failure);
            }
        }
        log.info("点赞数定时任务结束");
    }
}
