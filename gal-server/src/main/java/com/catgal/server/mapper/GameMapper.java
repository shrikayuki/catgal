package com.catgal.server.mapper;

import com.catgal.server.domain.po.Game;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

/**
 * <p>
 * 游戏主表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
public interface GameMapper extends BaseMapper<Game> {

    void batchUpdateFavoriteCount(List<Game> list);
}
