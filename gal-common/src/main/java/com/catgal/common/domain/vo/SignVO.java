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



