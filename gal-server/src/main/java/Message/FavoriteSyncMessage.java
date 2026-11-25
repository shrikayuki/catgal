/*
 * 阅读说明
 * 收藏异步通知协议，携带用户、收藏夹和游戏定位信息。
 * 消费者根据当前关系校准，消息中的旧操作顺序由业务同步逻辑吸收。
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
@Schema(description = "收藏业务变更")
public class FavoriteSyncMessage implements Serializable {

    public static final Integer FAVORITE = 1;
    public static final Integer UNFAVORITE = 0;
    
    private Long userId;
    private Long folderId;
    private Long gameId;
    private Integer operation;  // 1-收藏，0-取消
}