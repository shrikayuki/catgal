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
        List<UserFavoriteFolderVO> values = records.stream().map(folder -> {
            UserFavoriteFolderVO value = BeanUtils.copyProperties(folder, UserFavoriteFolderVO.class);
            Integer gameCount = favoriteFolderService.getGameCountFromfolder(folder.getId());
            if (gameCount != -1) {
                value.setGameCount(gameCount);
            }
            return value;
        }).collect(Collectors.toList());
        return PageDTO.of(page, values);
    }
}
