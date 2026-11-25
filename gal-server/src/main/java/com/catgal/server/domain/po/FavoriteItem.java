/*
 * 阅读说明
 * 收藏明细的数据库映射对象，对应 favorite_item 表。
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
 * 收藏明细表
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("favorite_item")
public class FavoriteItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 收藏明细ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 收藏夹ID
     */
    private Long folderId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 游戏ID
     */
    private Long gameId;

    /**
     * 收藏时间
     */
    private LocalDateTime createTime;


}
