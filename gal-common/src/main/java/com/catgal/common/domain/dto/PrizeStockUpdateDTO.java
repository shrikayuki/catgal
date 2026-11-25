/*
 * 阅读说明
 * PrizeStockUpdateDTO的请求数据模型。
 * 字段上的 Bean Validation 注解在调用入口校验，权限、关联存在性与并发状态由 Service 核验。
 */
package com.catgal.common.domain.dto;

import lombok.Data;

// 单独创建一个 DTO 类
@Data
public class PrizeStockUpdateDTO {
    private Long id;
    private Integer remainStock;
}