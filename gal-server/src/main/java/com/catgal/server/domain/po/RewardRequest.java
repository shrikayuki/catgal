package com.catgal.server.domain.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 一个已经确定结果的逻辑请求。失败结果也提交，重试同一请求号不会重新抽取。 */
@Data
@TableName("reward_request")
public class RewardRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String action;
    private String requestKey;
    private String bizId;
    private LocalDate businessDate;
    private String status;
    private String resultJson;
    private String rejectionReason;
    private Boolean legacyImported;
    private LocalDateTime createTime;
}
