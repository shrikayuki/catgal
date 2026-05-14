package Message;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "点赞数变更消息")
public class LikeCountMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "业务类型：comment/review/resource", example = "comment")
    private String bizType;

    @Schema(description = "业务ID", example = "100")
    private Long bizId;

    @Schema(description = "最新点赞数", example = "128")
    private Long likeCount;
}
