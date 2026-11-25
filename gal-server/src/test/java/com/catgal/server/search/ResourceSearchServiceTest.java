package com.catgal.server.search;

import com.catgal.server.domain.po.Resource;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.mapper.ResourceMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResourceSearchServiceTest {
    private final ResourceSearchPort mysql = mock(ResourceSearchPort.class);
    private final ResourceSearchPort es = mock(ResourceSearchPort.class);
    private final ResourceMapper mapper = mock(ResourceMapper.class);
    private final SearchProperties properties = new SearchProperties();
    private final ResourceSearchService service = new ResourceSearchService(mysql, es, mapper, properties);

    private AdminPageQuery query() {
        AdminPageQuery query = new AdminPageQuery();
        query.setKeyword("本体");
        return query;
    }

    @Test
    void resourceFeatureFlagIsIndependentOfGameSearch() {
        properties.setEnabled(true);
        when(mysql.search(any())).thenReturn(new ResourceSearchPort.SearchPage(0, List.of()));
        assertEquals(0, service.search(query()).getTotal());
        verifyNoInteractions(es, mapper);
    }

    @Test
    void retainsAllReviewStatesAndHitOrder() {
        properties.setResourceEnabled(true);
        when(es.search(any())).thenReturn(new ResourceSearchPort.SearchPage(3, List.of(3L, 2L, 1L)));
        when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                new Resource().setId(1L).setStatus(0), new Resource().setId(3L).setStatus(2),
                new Resource().setId(2L).setStatus(1)));
        var page = service.search(query());
        assertEquals(List.of(3L, 2L, 1L), page.getRecords().stream().map(Resource::getId).toList());
        assertEquals(List.of(2, 1, 0), page.getRecords().stream().map(Resource::getStatus).toList());
        verifyNoInteractions(mysql);
    }

    @Test
    void concurrentModerationTriggersWholePageFallback() {
        properties.setResourceEnabled(true);
        AdminPageQuery query = query();
        query.setStatus(2);
        when(es.search(any())).thenReturn(new ResourceSearchPort.SearchPage(1, List.of(1L)));
        when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(new Resource().setId(1L).setStatus(1)));
        when(mysql.search(any())).thenReturn(new ResourceSearchPort.SearchPage(0, List.of()));
        assertTrue(service.search(query).getRecords().isEmpty());
        verify(mysql).search(query);
    }

    @Test
    void rechecksPublisherAndGameFiltersAgainstDatabase() {
        properties.setResourceEnabled(true);
        AdminPageQuery query = query();
        query.setUserId(7L);
        query.setGameId(8L);
        when(es.search(any())).thenReturn(new ResourceSearchPort.SearchPage(1, List.of(1L)));
        when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                new Resource().setId(1L).setUserId(7L).setGameId(99L)));
        when(mysql.search(any())).thenReturn(new ResourceSearchPort.SearchPage(0, List.of()));
        assertEquals(0, service.search(query).getTotal());
        verify(mysql).search(query);
    }

    @Test
    void transportFailuresUseMysqlAndCooldown() {
        properties.setResourceEnabled(true);
        when(es.search(any())).thenThrow(new IllegalStateException("private upstream response"));
        when(mysql.search(any())).thenReturn(new ResourceSearchPort.SearchPage(0, List.of()));
        service.search(query());
        service.search(query());
        verify(es, times(1)).search(any());
        verify(mysql, times(2)).search(any());
    }

    @Test
    void emptyIndexPageChecksDatabaseForNewlyPublishedOrRenamedResources() {
        properties.setResourceEnabled(true);
        when(es.search(any())).thenReturn(new ResourceSearchPort.SearchPage(0, List.of()));
        when(mysql.search(any())).thenReturn(new ResourceSearchPort.SearchPage(1, List.of(9L)));
        when(mapper.selectBatchIds(List.of(9L))).thenReturn(List.of(new Resource().setId(9L).setStatus(2)));
        var page = service.search(query());
        assertEquals(1, page.getTotal());
        assertEquals(List.of(9L), page.getRecords().stream().map(Resource::getId).toList());
        verify(mysql).search(any());
    }

    @Test
    void genuinelyEmptySearchReturnsEmptyAfterDatabaseConfirmation() {
        properties.setResourceEnabled(true);
        when(es.search(any())).thenReturn(new ResourceSearchPort.SearchPage(0, List.of()));
        when(mysql.search(any())).thenReturn(new ResourceSearchPort.SearchPage(0, List.of()));
        assertEquals(0, service.search(query()).getTotal());
        verify(mysql).search(any());
        verifyNoInteractions(mapper);
    }

    @Test
    void respectsSafePageSizeAndRoutesDeepPagesToMysql() {
        properties.setResourceEnabled(true);
        AdminPageQuery query = query();
        query.setPageNo(501);
        query.setPageSize(200);
        when(mysql.search(any())).thenReturn(new ResourceSearchPort.SearchPage(0, List.of()));
        assertEquals(100, service.search(query).getSize());
        verifyNoInteractions(es);
    }
}
