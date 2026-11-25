/*
 * 阅读说明
 * AI 角色模板的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
package com.catgal.server.service;

import com.catgal.common.domain.vo.AIGirlVO;
import com.catgal.server.ai.AiChatEvent;
import com.catgal.server.domain.po.AiGirl;
import com.baomidou.mybatisplus.extension.service.IService;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;

/**
 * <p>
 * AI老婆角色表 服务类
 * </p>
 *
 * @author rance
 * @since 2026-06-01
 */
public interface IAiGirlService extends IService<AiGirl> {

    /** 返回可用角色展示列表，具体可见范围由角色服务核验。 */
    List<AIGirlVO> selectAllGirl();

    /** 兼容旧文本流入口，角色与本人会话通过业务验证后交给 Python 生成。 */
    Flux<String> chatWithAIGirl(String girlCode, String sessionId, String userMessage);
    /** 返回结构化聊天事件流，事件类型区分元信息、文本、成功完成和失败。 */
    Flux<AiChatEvent> streamChat(String girlCode, String sessionId, String userMessage);

    /** 查询当前用户与指定角色的会话标识历史。 */
    List<String> getHistorySessionIds(String girlCode);

    /** 为当前用户和可用角色创建会话，返回服务端生成的会话标识。 */
    String newSession(String girlCode);
}
