package com.catgal.server.catalog;

import com.catgal.common.domain.vo.GameVO;
import com.catgal.server.service.ICompanyService;
import com.catgal.server.service.ITagService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameRelationsServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ICompanyService companies = mock(ICompanyService.class);
    private final ITagService tags = mock(ITagService.class);
    private final GameRelationsService service = new GameRelationsService(jdbc, companies, tags);

    @Test
    void replacesOnlyChangedEdges() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(9L))).thenReturn(List.of(1L, 2L));
        service.replace(CatalogKind.COMPANY, 9L, Set.of(2L, 3L));
        verify(companies).lockEntries(Set.of(2L, 3L));
        verify(jdbc).batchUpdate(startsWith("DELETE"), eq(List.of(1L)), eq(1), any(ParameterizedPreparedStatementSetter.class));
        verify(jdbc).batchUpdate(startsWith("INSERT"), eq(List.of(3L)), eq(1), any(ParameterizedPreparedStatementSetter.class));
    }

    @Test
    void sameEdgesDoNotGenerateRedundantWrites() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(9L))).thenReturn(List.of(1L, 2L));
        service.replace(CatalogKind.TAG, 9L, Set.of(1L, 2L));
        verify(tags).lockEntries(Set.of(1L, 2L));
        verify(jdbc, never()).batchUpdate(anyString(), anyCollection(), anyInt(), any(ParameterizedPreparedStatementSetter.class));
    }

    @Test
    void fillsAWholePageWithTwoQueries() {
        List<GameVO> games = java.util.stream.LongStream.rangeClosed(1, 40).mapToObj(id -> {
            GameVO game = new GameVO();
            game.setId(id);
            return game;
        }).toList();
        service.attach(games);
        verify(jdbc, times(2)).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        assertTrue(games.stream().allMatch(game -> game.getTags().isEmpty() && game.getCompanies().isEmpty()));
    }
}
