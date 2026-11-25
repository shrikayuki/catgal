/*
 * 阅读说明
 * Bangumi 外部条目适配器，转换搜索与详情响应为本地预览协议。
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
public class BangumiCatalogProvider implements ExternalCatalogProvider {
    private final ExternalCatalogHttp http;
    public ExternalSource source() { return ExternalSource.BANGUMI; }

    public ExternalSearchResult search(String keyword, int pageNo) {
        JsonNode response = http.request(source(), null, keyword, pageNo);
        if (!response.path("data").isArray()) throw unavailable();
        List<ExternalGamePreview> items = new ArrayList<>();
        for (JsonNode row : response.path("data")) {
            if (row.path("type").asInt() == 4) items.add(map(row));
        }
        return new ExternalSearchResult(items, pageNo, response.path("total").asLong() > pageNo * 10L);
    }

    public ExternalGamePreview detail(String id) {
        JsonNode row = http.request(source(), source().normalizeId(id), null, 1);
        if (row.path("type").asInt() != 4) throw BusinessException.badRequest("只能关联Bangumi游戏类条目");
        return map(row);
    }

    private ExternalGamePreview map(JsonNode row) {
        String id = source().normalizeId(row.path("id").asText());
        String original = text(row, "name");
        String chinese = text(row, "name_cn");
        String title = chinese == null || chinese.isBlank() ? original : chinese;
        if (title == null || title.isBlank() || title.length() > 512) throw unavailable();
        return new ExternalGamePreview(source().key(), id, source().pageUrl(id), title, original,
                safeImage(text(row.path("images"), "large")), text(row, "summary"), text(row, "date"));
    }
}
