package com.catgal.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.catgal.server.domain.po.Resource;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 游戏资源表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
public interface ResourceMapper extends BaseMapper<Resource> {

    int batchUpdateResourceStats(@Param("list") List<Resource> resourcesToUpdate);
}
