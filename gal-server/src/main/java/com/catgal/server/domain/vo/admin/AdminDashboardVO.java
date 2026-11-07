package com.catgal.server.domain.vo.admin;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminDashboardVO {
    private Long userCount;
    private Long creatorCount;
    private Long adminCount;
    private Long gameCount;
    private Long publishedGameCount;
    private Long offlineGameCount;
    private Long pendingResourceCount;
    private Long activeResourceCount;
    private Long hiddenCommentCount;
    private Long hiddenReviewCount;
    private Long currentMonthPrizeCount;
    private Long drawRecordCount;
    private Long todayDrawCount;
    private Boolean redisAvailable;
    private Long pendingRedisOperations;
}
