package com.catgal.server.domain.po;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 单行切换屏障。业务持共享锁，切换持排他锁，防止旧写入跨越激活边界。 */
@Data
public class RewardRuntime {
    private Integer id;
    private String mode;
    private Long baselineCursor;
    private Boolean baselineComplete;
    private LocalDate baselineDate;
    private Long updatedBy;
    private LocalDateTime updateTime;
    private LocalDateTime activatedAt;
}
