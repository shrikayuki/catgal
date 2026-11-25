/*
 * 阅读说明
 * 月度奖池的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
package com.catgal.server.service;

import com.catgal.common.domain.vo.PrizeVO;
import com.catgal.common.domain.vo.UserPrizeVO;
import com.catgal.server.domain.po.DrawRecord;
import com.catgal.server.domain.po.PrizePool;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalDate;
import java.util.List;

/**
 * <p>
 * 奖池表 服务类
 * </p>
 *
 * @author rance
 * @since 2026-05-25
 */
public interface IPrizePoolService extends IService<PrizePool> {

    /** 读取指定月份启用奖品，用于奖池缓存加载。 */
    List<PrizeVO> getPoolInfoByMonth(String month);

    /** 返回当月奖池展示数据，服务层处理抽奖内部权重字段。 */
    List<PrizeVO> getCurrentPrizePool();

    /** 按运行模式抽奖，ACTIVE 的扣款、库存、结果和可靠通知由事务服务统一提交。 */
    UserPrizeVO drawPrizePool();

    /** 新客户端回传逻辑请求 UUID，旧客户端缺失时仍允许一次抽奖。 */
    UserPrizeVO drawPrizePool(String requestKey);

    /** 消费者通过业务号幂等插入记录，重复通知复用数据库唯一约束。 */
    void checkRemainStockAndCreateDrawRecord(DrawRecord message);

    /** 领取库存脏任务后批量读取 Hash 并更新数据库，缺少库存快照时抛错保留任务。 */
    void prizeStockToDB(String prizePoolChangeSetKey, Integer batchSize);

    /** 用数据库当前启用奖品重建指定月份 Hash，管理员维护流程负责调用时机。 */
    void refreshPrizeCache(String month);
}
