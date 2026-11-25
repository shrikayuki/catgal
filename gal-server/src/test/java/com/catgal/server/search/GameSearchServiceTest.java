package com.catgal.server.search;

import com.catgal.common.domain.query.GamePageQuery;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.Game;
import com.catgal.server.mapper.GameMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameSearchServiceTest {
    private final GameSearchPort mysql = mock(GameSearchPort.class);
    private final GameSearchPort es = mock(GameSearchPort.class);
    private final GameMapper mapper = mock(GameMapper.class);
    private final SearchProperties properties = new SearchProperties();
    private final GameSearchService service = new GameSearchService(mysql, es, mapper, properties);

    private GamePageQuery query() {
        GamePageQuery query = new GamePageQuery();
        query.setName("夏");
        return query;
    }

    @Test
    void disabledSearchWorksWithoutEsOrOutbox() {
        when(mysql.search(any())).thenReturn(new GameSearchPort.SearchPage(0, List.of()));
        assertEquals(0, service.search(query()).total());
        verifyNoInteractions(es, mapper);
    }

    @Test
    void preservesEsOrderingWhenDatabaseReturnsDifferentOrder() {
        properties.setEnabled(true);
        when(es.search(any())).thenReturn(new GameSearchPort.SearchPage(2, List.of(9L, 2L)));
        when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                new Game().setId(2L).setStatus(1), new Game().setId(9L).setStatus(1)));
        assertEquals(List.of(9L, 2L), service.search(query()).games().stream().map(Game::getId).toList());
        verifyNoInteractions(mysql);
    }

    @Test
    void unavailableEsFallsBackAndCooldownAvoidsRepeatedTimeouts() {
        properties.setEnabled(true);
        when(es.search(any())).thenThrow(new IllegalStateException("private response"));
        when(mysql.search(any())).thenReturn(new GameSearchPort.SearchPage(0, List.of()));
        service.search(query());
        service.search(query());
        verify(es, times(1)).search(any());
        verify(mysql, times(2)).search(any());
    }

    @Test
    void unpublishedHitFallsBackToAnEntireMysqlPage() {
        properties.setEnabled(true);
        when(es.search(any())).thenReturn(new GameSearchPort.SearchPage(1, List.of(9L)));
        when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(new Game().setId(9L).setStatus(2)));
        when(mysql.search(any())).thenReturn(new GameSearchPort.SearchPage(0, List.of()));
        assertTrue(service.search(query()).games().isEmpty());
        verify(mysql).search(any());
    }

    @Test
    void emptyEsResultIsNotTreatedAsAnOutage() {
        properties.setEnabled(true);
        when(es.search(any())).thenReturn(new GameSearchPort.SearchPage(0, List.of()));
        assertEquals(0, service.search(query()).total());
        verifyNoInteractions(mysql);
    }

    @Test
    void browseAndDeepPagesUseMysql() {
        properties.setEnabled(true);
        when(mysql.search(any())).thenReturn(new GameSearchPort.SearchPage(0, List.of()));
        service.search(new GamePageQuery());
        GamePageQuery deep = query();
        deep.setPageNo(501);
        service.search(deep);
        verifyNoInteractions(es);
    }

    @Test
    void rejectsOversizeRequestsBeforeQueryingEitherBackend() {
        GamePageQuery query = query();
        query.setPageSize(101);
        assertThrows(BusinessException.class, () -> service.search(query));
        query.setPageSize(20);
        query.setPageNo(Integer.MAX_VALUE);
        when(mysql.search(any())).thenReturn(new GameSearchPort.SearchPage(0, List.of()));
        assertDoesNotThrow(() -> service.search(query));
    }
}
