package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.FavoriteFolder;
import com.catgal.server.domain.po.FavoriteItem;
import com.catgal.server.mapper.FavoriteFolderMapper;
import com.catgal.server.mapper.FavoriteItemMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.service.IFavoriteItemService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FolderVisibilityTest {
    private final FavoriteFolderMapper folders = mock(FavoriteFolderMapper.class);
    private final FavoriteItemMapper items = mock(FavoriteItemMapper.class);
    private final FavoriteFolderServiceImpl service = new FavoriteFolderServiceImpl(folders, items,
            mock(GameMapper.class), mock(IFavoriteItemService.class), mock(StringRedisTemplate.class));

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void publishedGameConstraintIsAppliedBeforePagination() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FavoriteItem.class);
        when(folders.selectById(1L)).thenReturn(new FavoriteFolder().setId(1L).setUserId(6L).setIsPublic(1));
        when(items.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(new Page<FavoriteItem>());
        service.getGamesById(1L, new PageQuery());
        ArgumentCaptor<Wrapper<FavoriteItem>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(items).selectPage(any(Page.class), query.capture());
        assertTrue(query.getValue().getCustomSqlSegment().contains(
                "EXISTS (SELECT 1 FROM game g WHERE g.id = favorite_item.game_id AND g.status = 1)"));
    }

    @Test
    void privateFolderIsRejectedBeforeItsItemsAreQueried() {
        when(folders.selectById(1L)).thenReturn(new FavoriteFolder().setId(1L).setUserId(6L).setIsPublic(0));
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.getGamesById(1L, new PageQuery())).getHttpStatus());
        verifyNoInteractions(items);
    }
}
