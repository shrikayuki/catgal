/*
 * 阅读说明
 * 签到记录的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
package com.catgal.common.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "签到记录")
public class SignVO {
    private List<Long> signedDays;
    private Integer continuousDays;
}



