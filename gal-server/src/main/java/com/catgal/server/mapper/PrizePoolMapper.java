/*
 * 阅读说明
 * 月度奖池的数据访问接口。
 * BaseMapper 提供单表 CRUD，额外 SQL 位于本接口注解或同名 mapper XML。
 */
package com.catgal.server.mapper;

import com.catgal.common.domain.dto.PrizeStockUpdateDTO;
import com.catgal.server.domain.po.PrizePool;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 奖池表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-05-25
 */
public interface PrizePoolMapper extends BaseMapper<PrizePool> {

    /** 用户行之后按奖品 ID 升序锁定当月全部奖品，库存、权重和启用状态共用一致快照。 */
    @org.apache.ibatis.annotations.Select("SELECT * FROM prize_pool WHERE month=#{month} ORDER BY id FOR UPDATE")
    List<PrizePool> lockMonth(String month);

    @org.apache.ibatis.annotations.Select("SELECT * FROM prize_pool WHERE id=#{id} FOR UPDATE")
    PrizePool lockPrize(Long id);

    void decrementPrizeStock(Long id);

    /**
     * 批量更新剩余库存
     */
    int batchUpdateRemainStock(@Param("list") List<PrizeStockUpdateDTO> list);
}
