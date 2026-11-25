/*
 * 阅读说明
 * 社区用户的数据访问接口。
 * BaseMapper 提供单表 CRUD，额外 SQL 位于本接口注解或同名 mapper XML。
 * 行锁查询需加入业务事务，锁的获取顺序由上层 Service 统一约束。
 */
package com.catgal.server.mapper;

import com.catgal.server.domain.po.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 用户表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-04-30
 */
public interface UserMapper extends BaseMapper<User> {

    /** 认证每次读取当前状态，避免把密码、手机号等字段带入认证上下文。 */
    @Select("SELECT id, username, role, status, ban_reason, banned_until, auth_version FROM `user` WHERE id=#{id}")
    User selectAccountState(Long id);

    /** 在业务事务内锁定用户行，调用方持锁后校验权限、版本及目标状态。 */
    @Select("SELECT * FROM `user` WHERE id=#{id} FOR UPDATE")
    User selectForUpdate(Long id);

    /**
     * 用户角色和状态写入共用的首段锁，ID 升序固定获取顺序。
     * 包含封禁管理员，调用方再按有效状态统计可用管理员数量。
     */
    @Select("SELECT id FROM `user` WHERE role = 2 ORDER BY id FOR UPDATE")
    List<Long> selectAdminIdsForUpdate();
}
