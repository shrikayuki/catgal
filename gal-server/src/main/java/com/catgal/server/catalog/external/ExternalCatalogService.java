/*
 * 阅读说明
 * 根据来源选择 Provider，缓存有界搜索与详情结果供管理员预览。
 */
package com.catgal.server.catalog.external;

import com.catgal.common.exception.BusinessException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ExternalCatalogService {
    private final Map<ExternalSource, ExternalCatalogProvider> providers = new EnumMap<>(ExternalSource.class);
    private final Cache<SearchKey, ExternalSearchResult> searches = Caffeine.newBuilder()
            .maximumSize(300).expireAfterWrite(Duration.ofMinutes(2)).build();
    private final Cache<ItemKey, ExternalGamePreview> items = Caffeine.newBuilder()
            .maximumSize(300).expireAfterWrite(Duration.ofMinutes(5)).build();

    public ExternalCatalogService(List<ExternalCatalogProvider> providers) {
        for (ExternalCatalogProvider provider : providers) {
            if (this.providers.put(provider.source(), provider) != null) throw new IllegalStateException("Duplicate catalog provider");
        }
    }

    public ExternalSearchResult search(ExternalSource source, String keyword, int pageNo) {
        String normalized = keyword == null ? "" : keyword.strip();
        if (normalized.isEmpty() || normalized.length() > 100 || pageNo < 1 || pageNo > 20) {
            throw BusinessException.badRequest("关键字须为1到100字符，页码须为1到20");
        }
        return searches.get(new SearchKey(source, normalized, pageNo), key -> provider(source).search(key.keyword(), key.pageNo()));
    }

    public ExternalGamePreview detail(ExternalSource source, String id) {
        String normalized = source.normalizeId(id);
        return items.get(new ItemKey(source, normalized), key -> {
            ExternalGamePreview item = provider(source).detail(key.id());
            if (!source.key().equals(item.provider()) || !normalized.equals(item.externalId())) throw ExternalCatalogHttp.unavailable();
            return item;
        });
    }

    private ExternalCatalogProvider provider(ExternalSource source) {
        ExternalCatalogProvider provider = providers.get(source);
        if (provider == null) throw new BusinessException(503, 503, "资料来源暂未配置");
        return provider;
    }

    private record SearchKey(ExternalSource source, String keyword, int pageNo) { }
    private record ItemKey(ExternalSource source, String id) { }
}
