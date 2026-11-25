/*
 * 阅读说明
 * 给 Python 智能体提供受控游戏查询、详情校验和兴趣读取。
 * 工具返回结果前按当前数据库发布状态和账号状态核验，限制查询数量和输出长度。
 */
package com.catgal.server.ai.tools;

import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.GamePageQuery;
import com.catgal.common.domain.vo.GameVO;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.catalog.GameRelationsService;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.GameReview;
import com.catgal.server.service.IGameService;
import com.catgal.server.search.MysqlGameSearch;
import lombok.RequiredArgsConstructor;
import com.catgal.server.service.IUserAccountStatusService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-only tools reuse the catalog query service and never increment browse counters. */
@Service
@RequiredArgsConstructor
public class AiCatalogToolService {
    private final IGameService games;
    private final GameRelationsService relations;
    private final AiInterestReader interests;
    private final IUserAccountStatusService accountStatus;
    private final MysqlGameSearch mysqlSearch;

    public record SearchResult(List<AiToolGame> items, boolean hasMore) { }
    public record ValidationResult(List<AiToolGame> items) { }
    public record ReviewInterest(AiToolGame game, Integer score, Integer recommendLevel, Integer playStatus) { }
    public record InterestResult(List<AiToolGame> favorites, List<ReviewInterest> reviews,
                                  boolean sampled, String sourceStatus) { }

    public SearchResult search(AiToolRequests.Search request) {
        requireActiveUser(request.userId());
        int limit = requireLimit(request.resultLimit());
        GamePageQuery query = new GamePageQuery();
        query.setPageNo(1);
        query.setPageSize(limit);
        query.setKeyword(request.keyword());
        query.setTagId(request.tagId());
        query.setCompanyId(request.companyId());
        query.setReleaseYear(request.releaseYear());
        query.setLanguage(request.language());
        query.setPlatform(request.platform());
        query.setResourceType(request.resourceType());
        PageDTO<GameVO> page = games.pageGames(query);
        List<Long> ids = page.getList() == null ? List.of() : page.getList().stream()
                .map(GameVO::getId).limit(limit).toList();
        Map<Long, AiToolGame> current = load(ids);
        return new SearchResult(ids.stream().map(current::get).filter(java.util.Objects::nonNull).toList(),
                page.getTotal() != null && page.getTotal() > limit);
    }

    public AiToolGame detail(AiToolRequests.Detail request) {
        requireActiveUser(request.userId());
        AiToolGame game = load(List.of(request.gameId())).get(request.gameId());
        if (game == null) throw BusinessException.notFound("游戏不存在或未发布");
        return game;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    // 模型或向量提供的候选 ID 先验证数量和版本，再按当前公开目录条件回库筛选。
    public ValidationResult validate(AiToolRequests.Validate request) {
        requireActiveUser(request.userId());
        int limit = requireLimit(request.resultLimit());
        Map<Long, Long> versions = new LinkedHashMap<>();
        for (AiToolRequests.Candidate candidate : request.candidates()) {
            Long previous = versions.putIfAbsent(candidate.gameId(), candidate.version());
            if (previous != null && !previous.equals(candidate.version()))
                throw BusinessException.badRequest("同一游戏不能提交不同源版本");
        }
        GamePageQuery query = new GamePageQuery();
        query.setTagId(request.tagId());
        query.setCompanyId(request.companyId());
        query.setReleaseYear(request.releaseYear());
        query.setLanguage(request.language());
        query.setPlatform(request.platform());
        query.setResourceType(request.resourceType());
        List<Long> current = mysqlSearch.filterCurrentCandidates(versions, query);
        List<Long> ranked = versions.keySet().stream().filter(current::contains).limit(limit).toList();
        Map<Long, AiToolGame> values = load(ranked);
        return new ValidationResult(ranked.stream().map(values::get).filter(java.util.Objects::nonNull).toList());
    }

    // 身份由内部工具认证上下文传入，兴趣读取仅限该用户并限制样本数量。
    public InterestResult interests(AiToolRequests.Interests request) {
        requireActiveUser(request.userId());
        int limit = requireLimit(request.resultLimit());
        AiInterestReader.Snapshot snapshot = interests.read(request.userId(), limit);
        LinkedHashSet<Long> ids = new LinkedHashSet<>(snapshot.favoriteIds());
        snapshot.reviews().stream().map(GameReview::getGameId).filter(java.util.Objects::nonNull).forEach(ids::add);
        if (ids.isEmpty()) return new InterestResult(List.of(), List.of(), snapshot.sampled(), snapshot.sourceStatus());
        // Read current publication state before allowing source IDs into the prompt.
        List<Game> published = games.listByIds(ids).stream().filter(this::published).toList();
        Map<Long, Game> indexed = published.stream().collect(Collectors.toMap(Game::getId, Function.identity()));
        List<Long> visibleFavorites = snapshot.favoriteIds().stream().filter(indexed::containsKey).limit(limit).toList();
        List<GameReview> visibleReviews = snapshot.reviews().stream().filter(review -> indexed.containsKey(review.getGameId()))
                .limit(limit).toList();
        LinkedHashSet<Long> visibleIds = new LinkedHashSet<>(visibleFavorites);
        visibleReviews.forEach(review -> visibleIds.add(review.getGameId()));
        Map<Long, AiToolGame> values = assemble(visibleIds.stream().map(indexed::get).toList());
        return new InterestResult(visibleFavorites.stream().map(values::get).toList(), visibleReviews.stream()
                .map(review -> new ReviewInterest(values.get(review.getGameId()), review.getScore(),
                        review.getRecommendLevel(), review.getPlayStatus())).toList(), snapshot.sampled(), snapshot.sourceStatus());
    }

    private void requireActiveUser(Long userId) {
        // 内部 HMAC 工具也共用实时状态规则，定时封禁到期后可继续进行兴趣查询。
        accountStatus.requireActiveUser(userId);
    }

    private int requireLimit(int limit) {
        if (limit < 1 || limit > 10) throw BusinessException.badRequest("AI工具结果数量须在1到10之间");
        return limit;
    }

    private Map<Long, AiToolGame> load(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return assemble(games.listByIds(ids).stream().filter(this::published).toList());
    }

    private boolean published(Game game) { return game != null && Integer.valueOf(1).equals(game.getStatus()); }

    private Map<Long, AiToolGame> assemble(List<Game> source) {
        if (source.isEmpty()) return Map.of();
        List<GameVO> values = new ArrayList<>();
        for (Game game : source) {
            GameVO value = new GameVO();
            value.setId(game.getId());
            values.add(value);
        }
        relations.attach(values);
        Map<Long, GameVO> metadata = values.stream().collect(Collectors.toMap(GameVO::getId, Function.identity()));
        Map<Long, AiToolGame> result = new LinkedHashMap<>();
        for (Game game : source) {
            GameVO value = metadata.get(game.getId());
            result.put(game.getId(), new AiToolGame(String.valueOf(game.getId()), clip(game.getName(), 200),
                    clip(game.getIntroduction(), 1000), game.getReleaseDate(), game.getRating(),
                    names(value.getTags()), names(value.getCompanies())));
        }
        return result;
    }

    private List<String> names(List<String> values) {
        return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull)
                .limit(20).map(value -> clip(value, 80)).toList();
    }

    private String clip(String value, int maximum) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), maximum));
    }
}
