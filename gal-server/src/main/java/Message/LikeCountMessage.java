/*
 * 阅读说明
 * 点赞计数通知协议，定位需要校准的目标集合。
 * 消费时读取当前完整状态，通知中携带的历史计数仅具有兼容意义。
 */
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

    @Schema(description = "发送时的点赞数快照，消费时根据目标当前完整状态校准", example = "128")
    private Long likeCount;
}
