package com.catgal.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.catgal.server.domain.po.RewardRequest;
import com.catgal.common.domain.vo.SignVO;
import com.catgal.common.domain.vo.UserPrizeVO;

/** 积分、流水、库存、结果、Outbox 的单数据库事务入口。 */
public interface IRewardTransactionService extends IService<RewardRequest> {
    void signIn(Long userId);
    SignVO signRecords(Long userId);
    UserPrizeVO draw(Long userId, String requestKey);
    UserPrizeVO drawResult(Long userId, String requestKey);
}
