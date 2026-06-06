package com.catgal.common.domain.dto;

import lombok.Data;

// 单独创建一个 DTO 类
@Data
public class PrizeStockUpdateDTO {
    private Long id;
    private Integer remainStock;
}