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

    @Select("SELECT id FROM `user` WHERE role = 2 FOR UPDATE")
    List<Long> selectAdminIdsForUpdate();
}
