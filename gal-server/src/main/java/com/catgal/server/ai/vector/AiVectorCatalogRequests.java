/*
 * 阅读说明
 * Python 向量同步调用 Java 的批量请求协议，限制批次和目标版本。
 */
package com.catgal.server.ai.vector;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AiVectorCatalogRequests {
    private AiVectorCatalogRequests() { }

    public record Pending(@Min(1) @Max(100) Integer limit) {
        public int resultLimit() { return limit == null ? 20 : limit; }
    }

    public record Export(@NotNull @Size(min = 1, max = 10) List<@NotNull @Positive Long> gameIds) { }

    public record Version(@NotNull @Positive Long gameId, @NotNull @Positive Long version) { }

    public record Batch(@NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Version> items) { }

    public enum FailureCode { EMBEDDING_UNAVAILABLE, ES_UNAVAILABLE, INDEX_PROTOCOL_ERROR }

    public record Failed(@NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Version> items,
                         @NotNull FailureCode errorCode) { }

    public record Empty() { }
}
