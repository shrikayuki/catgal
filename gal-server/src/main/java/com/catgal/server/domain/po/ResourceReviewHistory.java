package com.catgal.server.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import java.time.LocalDateTime;

/** 与资源变更同事务追加。物理删除资源后仍保留，禁止写入外链和访问口令。 */
@Data
public class ResourceReviewHistory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long resourceId;
    private Long gameId;
    private Long ownerId;
    private Long actorId;
    private String actorName;
    private String action;
    private Integer oldStatus;
    private Integer newStatus;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long beforeRevision;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long afterRevision;
    private String resourceName;
    private String reason;
    private String changedFields;
    private LocalDateTime createTime;
}
