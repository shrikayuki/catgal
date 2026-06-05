package com.catgal.server.mapper;

import com.catgal.server.domain.po.UserSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Set;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-06-01
 */
public interface UserSessionMapper extends BaseMapper<UserSession> {
    /**
     * 查询用户的所有会话ID（返回 List<String>）
     */
    @Select("SELECT session_id FROM user_session WHERE user_id = #{userId} AND girl_code = #{girlCode} AND status = 1 ORDER BY create_time DESC")
    List<String> selectByUserAndGirlCode(@Param("userId") Long userId, @Param("girlCode") String girlCode);
}
