package com.catgal.server.catalog.external;

import com.catgal.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalCatalogProviderTest {
    private final ExternalCatalogHttp http = mock(ExternalCatalogHttp.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsUnsupportedSourcesAndUntrustedIds() {
        assertThrows(BusinessException.class, () -> ExternalSource.parse("http://localhost"));
        assertThrows(BusinessException.class, () -> ExternalSource.BANGUMI.normalizeId("../100"));
        assertThrows(BusinessException.class, () -> ExternalSource.VNDB.normalizeId("https://vndb.org/v17"));
        assertThrows(BusinessException.class, () -> ExternalSource.VNDB.normalizeId("r17"));
        assertEquals("https://vndb.org/v17", ExternalSource.VNDB.pageUrl("v17"));
    }

    @Test
    void bangumiUsesChineseTitleAndRejectsNonGameSubjects() throws Exception {
        when(http.request(ExternalSource.BANGUMI, "100", null, 1)).thenReturn(mapper.readTree("""
                {"id":100,"type":4,"name":"Original","name_cn":"中文名","summary":"<script>demo</script>",
                 "date":"2026-11-07","images":{"large":"https://lain.bgm.tv/pic/cover/l/a.jpg"}}
                """));
        var provider = new BangumiCatalogProvider(http);
        var preview = provider.detail("100");
        assertEquals("中文名", preview.title());
        assertEquals("Original", preview.originalTitle());
        assertEquals("<script>demo</script>", preview.description());
        assertEquals("https://bgm.tv/subject/100", preview.url());
        when(http.request(ExternalSource.BANGUMI, "101", null, 1)).thenReturn(mapper.readTree("{\"id\":101,\"type\":2}"));
        assertEquals(400, assertThrows(BusinessException.class, () -> provider.detail("101")).getHttpStatus());
    }

    @Test
    void searchHandlesEmptyResultsAndMorePages() throws Exception {
        when(http.request(ExternalSource.BANGUMI, null, "game", 1)).thenReturn(mapper.readTree("{\"total\":11,\"data\":[]}"));
        assertTrue(new BangumiCatalogProvider(http).search("game", 1).hasMore());
        when(http.request(ExternalSource.VNDB, null, "game", 1)).thenReturn(mapper.readTree("{\"more\":false,\"results\":[]}"));
        assertFalse(new VndbCatalogProvider(http).search("game", 1).hasMore());
    }

    @Test
    void vndbRetainsPartialDatesAndSuppressesFlaggedImages() throws Exception {
        when(http.request(ExternalSource.VNDB, "v17", null, 1)).thenReturn(mapper.readTree("""
                {"results":[{"id":"v17","title":"Ever17","alttitle":"エバーセブンティーン","released":"2002-08",
                  "description":"[b]text[/b]","image":{"url":"https://t.vndb.org/cv/1.jpg","sexual":1,"violence":0}}]}
                """));
        var preview = new VndbCatalogProvider(http).detail("v17");
        assertEquals("2002-08", preview.releaseDate());
        assertEquals("[b]text[/b]", preview.description());
        assertNull(preview.coverUrl());
    }

    @Test
    void missingOrMalformedVndbDataIsReported() throws Exception {
        when(http.request(ExternalSource.VNDB, "v17", null, 1)).thenReturn(mapper.readTree("{\"results\":[]}"));
        assertEquals(404, assertThrows(BusinessException.class, () -> new VndbCatalogProvider(http).detail("v17")).getHttpStatus());
        when(http.request(ExternalSource.VNDB, "v17", null, 1)).thenReturn(mapper.readTree("{}"));
        assertEquals(502, assertThrows(BusinessException.class, () -> new VndbCatalogProvider(http).detail("v17")).getHttpStatus());
    }

    @Test
    void acceptsOnlyKnownHttpsImageHosts() {
        assertNull(ExternalCatalogHttp.safeImage("http://lain.bgm.tv/cover.jpg"));
        assertNull(ExternalCatalogHttp.safeImage("https://t.vndb.org.evil.example/a.jpg"));
        assertNull(ExternalCatalogHttp.safeImage("https://user@t.vndb.org/a.jpg"));
        assertNull(ExternalCatalogHttp.safeImage("https://127.0.0.1/a.jpg"));
        assertEquals("https://t.vndb.org/cv/1.jpg", ExternalCatalogHttp.safeImage("https://t.vndb.org/cv/1.jpg"));
    }
}
