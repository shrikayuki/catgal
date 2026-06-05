package Message;

import com.catgal.common.enums.PointsType;
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
@Schema(description = "用户萌萌点变更消息")
public class UserCutePointsMessage implements Serializable {
    @Schema(description = "用户id")
    private Long userId;
    @Schema(description = "业务类型")
    private PointsType pointsType;
    @Schema(description = "积分变化")
    private Integer cutePointsChange;
}
