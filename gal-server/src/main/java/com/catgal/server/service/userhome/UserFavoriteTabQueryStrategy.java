/*
 * 阅读说明
 * UserFavoriteTabQueryStrategy 属于用户主页按 tab 分发的查询策略链。
 * 注册表选择支持目标 tab 的策略，查询结果统一组装到用户主页响应。
 */
package com.catgal.server.service.userhome;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.UserHomeQueryDTO;
import com.catgal.common.domain.vo.UserFavoriteFolderVO;
import com.catgal.common.enums.UserHomeTabEnum;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.CollUtils;
import com.catgal.server.domain.po.FavoriteFolder;
import com.catgal.server.service.IFavoriteFolderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class UserFavoriteTabQueryStrategy implements UserHomeTabQueryStrategy {

    private final IFavoriteFolderService favoriteFolderService;

    @Override
    public UserHomeTabEnum supports() {
        return UserHomeTabEnum.FAVORITE;
    }

    @Override
    public PageDTO<UserFavoriteFolderVO> query(UserHomeQueryDTO query, Long userId, boolean includePrivate) {
        Page<FavoriteFolder> page = favoriteFolderService.lambdaQuery()
                .eq(FavoriteFolder::getUserId, userId)
                .eq(!includePrivate, FavoriteFolder::getIsPublic, 1)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<FavoriteFolder> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        Map<Long, Integer> counts = favoriteFolderService.getGameCountsFromFolders(
                records.stream().map(FavoriteFolder::getId).toList());
        List<UserFavoriteFolderVO> values = records.stream().map(folder -> {
            UserFavoriteFolderVO value = BeanUtils.copyProperties(folder, UserFavoriteFolderVO.class);
            Integer gameCount = counts.get(folder.getId());
            if (gameCount != null) {
                value.setGameCount(gameCount);
            }
            return value;
        }).collect(Collectors.toList());
        return PageDTO.of(page, values);
    }
}
