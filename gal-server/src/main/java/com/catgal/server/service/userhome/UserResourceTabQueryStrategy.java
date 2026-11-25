/*
 * 阅读说明
 * UserResourceTabQueryStrategy 属于用户主页按 tab 分发的查询策略链。
 * 注册表选择支持目标 tab 的策略，查询结果统一组装到用户主页响应。
 */
package com.catgal.server.service.userhome;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.UserHomeQueryDTO;
import com.catgal.common.domain.vo.UserResourceVO;
import com.catgal.common.enums.UserHomeTabEnum;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.CollUtils;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.Resource;
import com.catgal.server.service.IResourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class UserResourceTabQueryStrategy implements UserHomeTabQueryStrategy {

    private final IResourceService resourceService;
    private final UserHomeGameLookup gameLookup;

    @Override
    public UserHomeTabEnum supports() {
        return UserHomeTabEnum.RESOURCE;
    }

    @Override
    public PageDTO<UserResourceVO> query(UserHomeQueryDTO query, Long userId, boolean includePrivate) {
        Page<Resource> page = resourceService.lambdaQuery()
                .eq(Resource::getUserId, userId)
                .eq(!includePrivate, Resource::getStatus, 1)
                .apply(!includePrivate, "EXISTS (SELECT 1 FROM game g WHERE g.id = resource.game_id AND g.status = 1)")
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<Resource> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        Map<Long, Game> games = gameLookup.getGameMap(records.stream()
                .map(Resource::getGameId)
                .collect(Collectors.toSet()));
        List<UserResourceVO> values = records.stream().map(resource -> {
            UserResourceVO value = BeanUtils.copyProperties(resource, UserResourceVO.class);
            // Bean拷贝会携带同名审核字段，必须在公开主页明确清空，不能只依赖前端隐藏。
            if (!includePrivate) {
                value.setRevision(null);
                value.setReviewedAt(null);
                value.setReviewReason(null);
            }
            value.setTypes(resource.getTypeList());
            value.setLanguages(resource.getLanguageList());
            value.setPlatforms(resource.getPlatformList());
            Game game = games.get(resource.getGameId());
            if (game != null) {
                value.setGameName(game.getName());
                value.setCoverUrl(com.catgal.server.storage.FileAccessPaths.gameCover(game));
            }
            return value;
        }).collect(Collectors.toList());
        return PageDTO.of(page, values);
    }
}
