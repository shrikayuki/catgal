package com.catgal.server.service.userhome;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.domain.query.UserHomeQueryDTO;
import com.catgal.server.domain.po.FavoriteFolder;
import com.catgal.server.mapper.FavoriteFolderMapper;
import com.catgal.server.service.IFavoriteFolderService;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserFavoriteTabQueryStrategyTest {
    private final IFavoriteFolderService folders = mock(IFavoriteFolderService.class);
    private final FavoriteFolderMapper mapper = mock(FavoriteFolderMapper.class);
    private final UserFavoriteTabQueryStrategy strategy = new UserFavoriteTabQueryStrategy(folders);

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FavoriteFolder.class);
        when(folders.lambdaQuery()).thenReturn(new LambdaQueryChainWrapper<>(mapper));
    }

    @Test
    void overlaysOneBatchAndRetainsDatabaseCountsForCacheMisses() {
        Page<FavoriteFolder> page = new Page<>();
        page.setRecords(List.of(new FavoriteFolder().setId(1L).setGameCount(7),
                new FavoriteFolder().setId(2L).setGameCount(3)));
        when(mapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        when(folders.getGameCountsFromFolders(List.of(1L, 2L))).thenReturn(Map.of(1L, 9));
        var result = strategy.query(new UserHomeQueryDTO(), 6L, false);
        assertEquals(9, result.getList().get(0).getGameCount());
        assertEquals(3, result.getList().get(1).getGameCount());
        verify(folders, times(1)).getGameCountsFromFolders(List.of(1L, 2L));
        verify(folders, never()).getGameCountFromfolder(anyLong());
        ArgumentCaptor<Wrapper<FavoriteFolder>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(Page.class), wrapper.capture());
        assertTrue(wrapper.getValue().getCustomSqlSegment().contains("is_public"));
    }

    @Test
    void emptyPageAvoidsCacheLookup() {
        when(mapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(new Page<FavoriteFolder>());
        assertTrue(strategy.query(new UserHomeQueryDTO(), 6L, false).getList().isEmpty());
        verify(folders, never()).getGameCountsFromFolders(anyCollection());
    }
}
