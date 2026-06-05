package com.catgal.common.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "抽奖记录VO")
public class DrawRecordVO {

    /**
     * 主键
     */
    private Long id;

    /**
     * 奖品ID
     */
    private Long prizeId;

    /**
     * 中奖用户ID
     */
    private Long userId;

    /**
     * 奖品name
     */
    private String prizeName;

    /**
     * 奖品图片
     */
    private String prizeImage;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
