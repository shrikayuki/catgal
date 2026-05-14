package com.catgal.server.domain.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("game_tag")
public class GameTag {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long gameId;

    private Integer tagId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}