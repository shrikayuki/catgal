/*
 * 阅读说明
 * 内部游戏查询、详情、候选校验和兴趣读取的请求协议与数量校验。
 */
package com.catgal.server.ai.tools;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AiToolRequests {
    private AiToolRequests() { }

    public record Search(@NotNull @Positive Long userId, @Size(max = 200) String keyword,
                          @Positive Integer tagId, @Positive Long companyId,
                          @Min(1) @Max(9998) Integer releaseYear,
                          @Size(max = 20) String language, @Size(max = 20) String platform,
                          @Size(max = 20) String resourceType, @Min(1) @Max(10) Integer limit) {
        public int resultLimit() { return limit == null ? 5 : limit; }
    }

    public record Detail(@NotNull @Positive Long userId, @NotNull @Positive Long gameId) { }

    public record Candidate(@NotNull @Positive Long gameId, @NotNull @Positive Long version) { }

    public record Validate(@NotNull @Positive Long userId,
                           @NotNull @Size(max = 50) List<@NotNull @Valid Candidate> candidates,
                           @Positive Integer tagId, @Positive Long companyId,
                           @Min(1) @Max(9998) Integer releaseYear,
                           @Size(max = 20) String language, @Size(max = 20) String platform,
                           @Size(max = 20) String resourceType, @Min(1) @Max(10) Integer limit) {
        public int resultLimit() { return limit == null ? 5 : limit; }
    }

    public record Interests(@NotNull @Positive Long userId, @Min(1) @Max(10) Integer limit) {
        public int resultLimit() { return limit == null ? 5 : limit; }
    }
}
