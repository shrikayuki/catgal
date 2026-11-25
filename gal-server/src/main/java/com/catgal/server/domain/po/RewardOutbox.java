package com.catgal.server.domain.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 与积分事务一并提交的通知。租约只覆盖发送，消费者投影成功后才标为 COMPLETE。 */
@Data
@TableName("reward_outbox")
public class RewardOutbox {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long requestId;
    private String state;
    private Integer attempts;
    private LocalDateTime nextAttemptAt;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private String lastError;
    private LocalDateTime projectedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
