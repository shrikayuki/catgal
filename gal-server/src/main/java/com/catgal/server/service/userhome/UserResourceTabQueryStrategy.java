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
            value.setTypes(resource.getTypeList());
            value.setLanguages(resource.getLanguageList());
            value.setPlatforms(resource.getPlatformList());
            Game game = games.get(resource.getGameId());
            if (game != null) {
                value.setGameName(game.getName());
                value.setCoverUrl(game.getCoverUrl());
            }
            return value;
        }).collect(Collectors.toList());
        return PageDTO.of(page, values);
    }
}
