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

    void decrementPrizeStock(Long id);

    /**
     * 批量更新剩余库存
     */
    int batchUpdateRemainStock(@Param("list") List<PrizeStockUpdateDTO> list);
}
