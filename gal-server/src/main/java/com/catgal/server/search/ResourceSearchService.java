/*
 * 阅读说明
 * 资源审核检索编排，按开关使用 ES，并回库装配、校验与故障回退。
 */
package com.catgal.server.search;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.Resource;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.mapper.ResourceMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ResourceSearchService {
    private final ResourceSearchPort mysql;
    private final ResourceSearchPort elasticsearch;
    private final ResourceMapper mapper;
    private final SearchProperties properties;
    private volatile Instant retryAfter = Instant.EPOCH;

    public ResourceSearchService(@Qualifier("mysqlResourceSearch") ResourceSearchPort mysql,
                                 @Qualifier("elasticsearchResourceSearch") ResourceSearchPort elasticsearch,
                                 ResourceMapper mapper, SearchProperties properties) {
        this.mysql = mysql;
        this.elasticsearch = elasticsearch;
        this.mapper = mapper;
        this.properties = properties;
    }

    // ES 查询仅定位候选，查询窗口、完整响应和回库检查失败时回退 MySQL。
    public Page<Resource> search(AdminPageQuery query) {
        if (query.getKeyword() != null && query.getKeyword().length() > 200) {
            throw BusinessException.badRequest("资源搜索关键字不能超过200个字符");
        }
        Page<Resource> page = query.toSafePage();
        if (properties.isResourceEnabled() && StringUtils.isNotBlank(query.getKeyword())
                && page.getCurrent() * page.getSize() <= 10_000 && Instant.now().isAfter(retryAfter)) {
            try {
                ResourceSearchPort.SearchPage hits = elasticsearch.search(query);
                // 新发布/改名可能仍处于Outbox同步或ES刷新窗口，空页也须回库确认。
                // 命中完整候选时沿用ES分页及数据库现态装配，避免扩大正常查询的回库开销。
                if (!hits.ids().isEmpty()) {
                    List<Resource> records = load(hits.ids(), query);
                    if (records.size() == hits.ids().size()) {
                        return page.setTotal(hits.total()).setRecords(records);
                    }
                    log.warn("Resource index filters are stale, falling back to MySQL");
                }
            } catch (RuntimeException exception) {
                retryAfter = Instant.now().plus(properties.getFailureCooldown());
                log.warn("Resource search falling back to MySQL, failureType={}", exception.getClass().getSimpleName());
            }
        }
        ResourceSearchPort.SearchPage hits = mysql.search(query);
        return page.setTotal(hits.total()).setRecords(load(hits.ids(), query));
    }

    private List<Resource> load(List<Long> ids, AdminPageQuery query) {
        if (ids.isEmpty()) return List.of();
        Map<Long, Resource> resources = mapper.selectBatchIds(ids).stream()
                .filter(resource -> query.getStatus() == null || Objects.equals(resource.getStatus(), query.getStatus()))
                .filter(resource -> query.getGameId() == null || Objects.equals(resource.getGameId(), query.getGameId()))
                .filter(resource -> query.getUserId() == null || Objects.equals(resource.getUserId(), query.getUserId()))
                .collect(Collectors.toMap(Resource::getId, Function.identity()));
        return ids.stream().map(resources::get).filter(Objects::nonNull).toList();
    }
}
