package com.catgal.common.enums;

import lombok.Getter;

@Getter
public enum PointsType {

    // ==================== 获取 ====================
    SIGN_IN             (1,  "每日签到",        true),
    CONTINUOUS_SIGN     (2,  "连续签到奖励",     true),
    LOTTERY_WIN         (3,  "抽奖中奖",         true),
    ADMIN_GRANT         (4,  "管理员发放",       true),
    EXCHANGE_REFUND     (5,  "兑换失败退回",     true),

    // ==================== 消耗 ====================
    LOTTERY_COST        (10, "抽奖消耗",        false),
    EXCHANGE_COST       (11, "兑换道具",        false),
    ADMIN_DEDUCT        (12, "管理员扣减",      false),

    ;

    private final Integer code;
    private final String desc;
    private final boolean earn;   // true=获得, false=消耗

    PointsType(Integer code, String desc, boolean earn) {
        this.code = code;
        this.desc = desc;
        this.earn = earn;
    }

}
