package com.catgal.server.mapper;

import com.catgal.server.domain.po.AiGirl;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.checkerframework.checker.nullness.qual.PolyNull;

/**
 * <p>
 * AI老婆角色表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-06-01
 */
public interface AiGirlMapper extends BaseMapper<AiGirl> {

    /**
     * 根据角色编码查询角色（指定字段，不查大字段）
     */
    @Select("SELECT id, girl_code, name, avatar_url, system_prompt, like_count, status, create_time, update_time " +
            "FROM ai_girl WHERE girl_code = #{girlCode} AND status = 1")
    AiGirl selectByGirlCode(@Param("girlCode") String girlCode);

}
