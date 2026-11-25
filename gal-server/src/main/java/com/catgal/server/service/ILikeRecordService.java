/*
 * 阅读说明
 * 点赞关系的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
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

    /** 以当前用户对可见目标执行点赞，重复请求按已有关系处理。 */
    Boolean like(LikeDTO dto);

    /** 以当前用户取消点赞，关系和脏任务通过原子脚本更新。 */
    Boolean unlike(LikeDTO dto);

    /** 批量读取成员与完整性标记，受损或未加载的展示查询使用数据库确认状态。 */
    Map<Long, Boolean> batchIsLiked(Long userId, String type, List<Long> targetIds);

    /** 明确区分有效零计数和缓存缺失，完整状态才可直接用于展示。 */
    Map<Long, Long> getLikeCounts(List<Long> bizIds, String type);

    /** 先验证完整快照再替换关系，数据库失败让任务保留或重新排队。 */
    void fullSyncLikeRecordsToDB(String bizType, int batchSize, int batchBizSize);

    /** 有界批次发送目标校准通知，消费者重新读取当前状态以处理乱序。 */
    void readLikedTimesAndSendMessage(String bizType, int maxBizSize);

    /**
     * 根据目标当前的完整 Redis 状态校准数据库点赞数。
     * MQ 消息负责触发校准，方法在事务内锁定目标并检查完整性，失败向上抛出供监听器重试。
     */
    void syncLikeCounts(String bizType, List<Long> targetIds);

    /** Join physical content deletion, remove persisted relationships, then clear cache after commit. */
    // 需在内容物理删除事务内调用，关系随事务提交，Redis 清理在提交后执行。
    void clearLikeCache(String bizType, Long bizId);
}
