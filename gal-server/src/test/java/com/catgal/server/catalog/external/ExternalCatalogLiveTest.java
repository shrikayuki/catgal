package com.catgal.server.catalog.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CATGAL_EXTERNAL_API_TEST", matches = "1")
class ExternalCatalogLiveTest {
    private ExternalCatalogHttp http() {
        String proxy = System.getenv("CATALOG_EXTERNAL_PROXY_URL");
        if (proxy == null) proxy = System.getenv("HTTPS_PROXY");
        return new ExternalCatalogHttp(new ExternalCatalogConfiguration().externalCatalogWebClient(proxy == null ? "" : proxy),
                "shrikayuki/catgal/0.1 (https://github.com/shrikayuki/catgal)", "");
    }

    @Test
    void searchesAndPreviewsBangumiGames() {
        var provider = new BangumiCatalogProvider(http());
        var search = provider.search("Ever17", 1);
        assertFalse(search.items().isEmpty());
        var detail = provider.detail(search.items().get(0).externalId());
        assertEquals("bangumi", detail.provider());
        assertFalse(detail.title().isBlank());
    }

    @Test
    void searchesAndPreviewsVndbVisualNovels() {
        var provider = new VndbCatalogProvider(http());
        var search = provider.search("Ever17", 1);
        assertFalse(search.items().isEmpty());
        var detail = provider.detail("v17");
        assertEquals("v17", detail.externalId());
        assertFalse(detail.title().isBlank());
    }
}
