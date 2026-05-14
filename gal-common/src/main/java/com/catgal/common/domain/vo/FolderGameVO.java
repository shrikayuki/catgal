package com.catgal.common.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "收藏夹内游戏")
public class FolderGameVO {

    @Schema(description = "游戏ID")
    private Long gameId;

    @Schema(description = "游戏名称")
    private String gameName;

    @Schema(description = "游戏封面")
    private String gameCoverUrl;
}