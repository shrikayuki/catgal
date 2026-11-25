/*
 * 阅读说明
 * 游戏与会社关联的数据库映射对象，对应 game_company 表。
 * MyBatis-Plus 通过字段与映射注解读写记录，Controller 使用专用视图承载展示字段。
 */
package com.catgal.server.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 游戏-会社关联表
 * </p>
 *
 * @author rance
 * @since 2026-05-30
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("game_company")
public class GameCompany implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 关联ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 游戏ID
     */
    private Long gameId;

    /**
     * 会社ID
     */
    private Long companyId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;


}
