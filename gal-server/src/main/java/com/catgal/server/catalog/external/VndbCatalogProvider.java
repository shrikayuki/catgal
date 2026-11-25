/*
 * 阅读说明
 * VNDB 外部条目适配器，限制查询字段并统一转换为本地预览协议。
 */
package com.catgal.server.catalog.external;

import com.catgal.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import static com.catgal.server.catalog.external.ExternalCatalogHttp.*;

@Component
@RequiredArgsConstructor
public class VndbCatalogProvider implements ExternalCatalogProvider {
    private final ExternalCatalogHttp http;
    public ExternalSource source() { return ExternalSource.VNDB; }

    public ExternalSearchResult search(String keyword, int pageNo) {
        JsonNode response = http.request(source(), null, keyword, pageNo);
        if (!response.path("results").isArray()) throw unavailable();
        List<ExternalGamePreview> items = new ArrayList<>();
        for (JsonNode row : response.path("results")) items.add(map(row));
        return new ExternalSearchResult(items, pageNo, response.path("more").asBoolean());
    }

    public ExternalGamePreview detail(String id) {
        String normalized = source().normalizeId(id);
        JsonNode response = http.request(source(), normalized, null, 1);
        if (!response.path("results").isArray()) throw unavailable();
        if (response.path("results").isEmpty()) throw BusinessException.notFound("VNDB条目不存在或不可访问");
        ExternalGamePreview result = map(response.path("results").get(0));
        if (!result.externalId().equals(normalized)) throw unavailable();
        return result;
    }

    private ExternalGamePreview map(JsonNode row) {
        String id = source().normalizeId(text(row, "id"));
        String title = text(row, "title");
        if (title == null || title.isBlank() || title.length() > 512) throw unavailable();
        JsonNode image = row.path("image");
        // Only display upstream images explicitly rated safe for both dimensions.
        String cover = image.path("sexual").isNumber() && image.path("violence").isNumber()
                && image.path("sexual").asDouble() == 0 && image.path("violence").asDouble() == 0
                ? safeImage(text(image, "url")) : null;
        return new ExternalGamePreview(source().key(), id, source().pageUrl(id), title, text(row, "alttitle"),
                cover, text(row, "description"), text(row, "released"));
    }
}
