package com.catgal.server.service;

import com.catgal.common.domain.dto.LikeDTO;
import com.catgal.server.domain.po.LikeRecord;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 点赞记录表 服务类
 * </p>
 *
 * @author rance
 * @since 2026-05-03
 */
public interface ILikeRecordService extends IService<LikeRecord> {

    Boolean like(LikeDTO dto);

    Boolean unlike(LikeDTO dto);

    Map<Long, Boolean> batchIsLiked(Long userId, String type, List<Long> targetIds);

    Map<Long, Long> getLikeCounts(List<Long> bizIds, String type);

    void fullSyncLikeRecordsToDB(String bizType, int batchSize, int batchBizSize);

    void readLikedTimesAndSendMessage(String bizType, int maxBizSize);

    void clearLikeCache(String bizType, Long bizId);
}
