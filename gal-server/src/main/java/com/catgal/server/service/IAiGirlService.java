package com.catgal.server.service;

import com.catgal.common.domain.vo.AIGirlVO;
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

    List<AIGirlVO> selectAllGirl();

    Flux<String> chatWithAIGirl(String girlCode, String sessionId, String userMessage);

    List<String> getHistorySessionIds(String girlCode);

    String newSession(String girlCode);
}
