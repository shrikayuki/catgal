/*
 * 阅读说明
 * 用户机器人的数据访问接口。
 * BaseMapper 提供单表 CRUD，额外 SQL 位于本接口注解或同名 mapper XML。
 * 行锁查询需加入业务事务，锁的获取顺序由上层 Service 统一约束。
 */
package com.catgal.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.catgal.server.domain.po.AiBot;
import org.apache.ibatis.annotations.Select;

public interface AiBotMapper extends BaseMapper<AiBot> {
    @Select("SELECT id,user_id,kind,status,avatar_url,portrait_url,revision,update_time "
            + "FROM ai_bot WHERE id=#{id} FOR UPDATE")
    AiBot selectForUpdate(long id);
}
