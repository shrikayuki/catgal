/*
 * 阅读说明
 * 管理员操作审计的数据访问接口。
 * BaseMapper 提供单表 CRUD，额外 SQL 位于本接口注解或同名 mapper XML。
 */
package com.catgal.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.catgal.server.domain.po.AdminOperationLog;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AdminOperationLogMapper extends BaseMapper<AdminOperationLog> {
    // target_id 是通用字符串目标列，revision 使用该用户最新审计主键作为版本锚点。
    record UserRevision(String targetId, Long revision) { }

    /** 按页聚合取最新日志编号，避免用户列表为每行单独查审计表。绑定字符串保留 target_id 的索引比较语义。 */
    @Select({"<script>", "SELECT target_id AS targetId, MAX(id) AS revision FROM admin_operation_log",
            "WHERE module='user' AND target_type='user' AND target_id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "GROUP BY target_id", "</script>"})
    List<UserRevision> userRevisions(@Param("ids") Collection<String> ids);
}
