package com.catgal.server.catalog.external;

import com.catgal.common.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalCatalogServiceTest {
    private final ExternalCatalogProvider provider = mock(ExternalCatalogProvider.class);

    private ExternalCatalogService service() {
        when(provider.source()).thenReturn(ExternalSource.VNDB);
        return new ExternalCatalogService(List.of(provider));
    }

    @Test
    void cachesSuccessfulSearchesAndDetails() {
        var service = service();
        var item = new ExternalGamePreview("vndb", "v17", "https://vndb.org/v17", "Ever17", null, null, null, null);
        when(provider.search("Ever17", 1)).thenReturn(new ExternalSearchResult(List.of(item), 1, false));
        when(provider.detail("v17")).thenReturn(item);
        service.search(ExternalSource.VNDB, " Ever17 ", 1);
        service.search(ExternalSource.VNDB, "Ever17", 1);
        service.detail(ExternalSource.VNDB, "v17");
        service.detail(ExternalSource.VNDB, "v17");
        verify(provider, times(1)).search("Ever17", 1);
        verify(provider, times(1)).detail("v17");
    }

    @Test
    void rejectsInvalidPaginationAndKeywordsBeforeCallingProvider() {
        var service = service();
        assertThrows(BusinessException.class, () -> service.search(ExternalSource.VNDB, " ", 1));
        assertThrows(BusinessException.class, () -> service.search(ExternalSource.VNDB, "test", 21));
        verify(provider, never()).search(anyString(), anyInt());
    }

    @Test
    void failuresAreRetryableAndMismatchedIdsAreRejected() {
        var service = service();
        when(provider.detail("v17")).thenThrow(ExternalCatalogHttp.unavailable())
                .thenReturn(new ExternalGamePreview("vndb", "v18", "https://vndb.org/v18", "wrong", null, null, null, null));
        assertEquals(502, assertThrows(BusinessException.class, () -> service.detail(ExternalSource.VNDB, "v17")).getHttpStatus());
        assertEquals(502, assertThrows(BusinessException.class, () -> service.detail(ExternalSource.VNDB, "v17")).getHttpStatus());
        verify(provider, times(2)).detail("v17");
    }
}
