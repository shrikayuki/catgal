package com.catgal.server.search;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.domain.query.GamePageQuery;
import com.catgal.server.domain.po.Game;
import com.catgal.server.mapper.GameMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MysqlGameSearchTest {
    @Test
    @SuppressWarnings("unchecked")
    void qualifiesOuterGameIdAndAlwaysAddsStableTieBreaker() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Game.class);
        GameMapper mapper = mock(GameMapper.class);
        when(mapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<Game> page = invocation.getArgument(0);
            Wrapper<Game> wrapper = invocation.getArgument(1);
            assertTrue(wrapper.getCustomSqlSegment().contains("gt.game_id = game.id"));
            assertTrue(wrapper.getCustomSqlSegment().contains("gc.game_id = game.id"));
            assertEquals("create_time", page.orders().get(0).getColumn());
            assertEquals("id", page.orders().get(1).getColumn());
            assertFalse(page.orders().get(1).isAsc());
            return page.setRecords(List.of(new Game().setId(3L))).setTotal(1);
        });
        GamePageQuery query = new GamePageQuery();
        query.setTagId(2);
        query.setCompanyId(4L);
        assertEquals(List.of(3L), new MysqlGameSearch(mapper).search(query).ids());
    }

    @Test
    @SuppressWarnings("unchecked")
    void keywordFallbackGroupsAllFourFieldsUnderPublishedFilter() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Game.class);
        GameMapper mapper = mock(GameMapper.class);
        when(mapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Wrapper<Game> wrapper = invocation.getArgument(1);
            String sql = wrapper.getCustomSqlSegment();
            assertTrue(sql.contains("introduction LIKE"));
            assertTrue(sql.contains("JOIN tag t ON t.id = gt.tag_id"));
            assertTrue(sql.contains("JOIN company c ON c.id = gc.company_id"));
            assertTrue(sql.contains("status ="));
            assertFalse(sql.contains("用户输入"));
            return invocation.<Page<Game>>getArgument(0).setRecords(List.of()).setTotal(0);
        });
        GamePageQuery query = new GamePageQuery();
        query.setKeyword("用户输入' OR 1=1 --");
        assertEquals(0, new MysqlGameSearch(mapper).search(query).total());
    }
}
