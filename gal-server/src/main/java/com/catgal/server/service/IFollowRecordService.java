package com.catgal.server.service;

import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.vo.UserFansOrFollowsVO;
import com.catgal.server.domain.po.FollowRecord;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 关注记录表 服务类
 * </p>
 *
 * @author rance
 * @since 2026-06-05
 */
public interface IFollowRecordService extends IService<FollowRecord> {

    Boolean follow(Long followedId);

    Boolean unfollow(Long followedId);

    PageDTO<UserFansOrFollowsVO> queryUserFansPage(Long userId, PageQuery query);

    PageDTO<UserFansOrFollowsVO> queryUserFollowsPage(Long userId, PageQuery query);

    Integer userFansCount(Long userId);

    Integer userFollowCount(Long userId);

    boolean isMyFollow(Long userId, Long targetId);
}
