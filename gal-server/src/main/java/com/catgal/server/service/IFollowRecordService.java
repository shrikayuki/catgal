/*
 * 阅读说明
 * 用户关注的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
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

    /** 先建立数据库关注关系，重复关系由业务校验和唯一约束共同处理，再维护缓存展示。 */
    Boolean follow(Long followedId);

    /** 删除当前用户指向目标的关注关系，并清理对应缓存的双向索引。 */
    Boolean unfollow(Long followedId);

    /** 粉丝列表按被关注者定位，统一分页装配用户信息。 */
    PageDTO<UserFansOrFollowsVO> queryUserFansPage(Long userId, PageQuery query);

    /** 关注列表按发起者定位，统一分页装配目标用户信息。 */
    PageDTO<UserFansOrFollowsVO> queryUserFollowsPage(Long userId, PageQuery query);

    /** 读取用户粉丝数量，缓存与数据库策略由实现承担。 */
    Integer userFansCount(Long userId);

    /** 读取用户关注数量，缓存与数据库策略由实现承担。 */
    Integer userFollowCount(Long userId);

    /** 判断指定发起用户与目标之间的关注关系。 */
    boolean isMyFollow(Long userId, Long targetId);
}
