package com.catgal.server.domain.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("game_company")
public class GameCompany {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long gameId;

    private Long companyId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}