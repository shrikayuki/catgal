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

    List<PrizeVO> getPoolInfoByMonth(String month);

    List<PrizeVO> getCurrentPrizePool();

    UserPrizeVO drawPrizePool();

    void checkRemainStockAndCreateDrawRecord(DrawRecord message);

    void prizeStockToDB(String prizePoolChangeSetKey, Integer batchSize);
}
