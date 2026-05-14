package com.catgal.server.mapper;

import com.catgal.server.domain.po.FavoriteItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.Set;

/**
 * <p>
 * 收藏明细表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
public interface FavoriteItemMapper extends BaseMapper<FavoriteItem> {

    void deleteByFolderIds(@Param("folderIds") Set<Long> folderIds);
}
