package com.catgal.server.service.impl;

import cn.hutool.core.util.IdUtil;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.vo.AIGirlVO;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.config.repository.ChatHistoryRepository;
import com.catgal.server.domain.po.AiGirl;
import com.catgal.server.mapper.AiGirlMapper;
import com.catgal.server.mapper.UserSessionMapper;
import com.catgal.server.service.IAiGirlService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.server.task.AiGirlCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * <p>
 * AI老婆角色表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-06-01
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiGirlServiceImpl extends ServiceImpl<AiGirlMapper, AiGirl> implements IAiGirlService {

    private final AiGirlCacheService aiGirlCacheService;
    private final ChatClient chatClient;
    private final ChatHistoryRepository redisChatHistoryRepository;
    private final UserSessionMapper userSessionMapper;

    @Override
    public List<AIGirlVO> selectAllGirl() {
        List<AiGirl> allGirls = aiGirlCacheService.getAllGirls();
        return BeanUtils.copyList(allGirls, AIGirlVO.class);
    }

    @Override
    public Flux<String> chatWithAIGirl(String girlCode, String sessionId, String userMessage) {
        log.info("sessionId:{}, userMessage:{}", sessionId, userMessage);
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new RuntimeException("用户未登录");
        }
        AiGirl girl = aiGirlCacheService.getByCode(girlCode);
        if (girl == null) {
            throw new RuntimeException("你老婆没了");
        }

        String girlSystemPrompt = girl.getSystemPrompt();

        return chatClient.prompt(girlSystemPrompt).
                user(userMessage)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, sessionId))
                .stream()
                .content();
    }

    @Override
    public List<String> getHistorySessionIds(String girlCode) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new RuntimeException("用户不存在");
        }

        // 1. 先从 Redis 获取
        List<String> historySessionIds = redisChatHistoryRepository.getHistorySessionIds(girlCode, userId);

        // 2. Redis 没有，查数据库
        if (CollUtils.isEmpty(historySessionIds)) {
            List<String> sessionIds = userSessionMapper.selectByUserAndGirlCode(userId, girlCode);

            // 3. 同步到 Redis（下次就不用查库了）
            if (CollUtils.isNotEmpty(sessionIds)) {
                for (String sessionId : sessionIds) {
                    redisChatHistoryRepository.save(girlCode, userId, sessionId);
                }
                return new ArrayList<>(sessionIds);
            }
            return CollUtils.emptyList();
        }

        return historySessionIds;
    }

    @Override
    public String newSession(String girlCode) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new RuntimeException("用户未登录");
        }
        //雪花算法生成唯一会话id
        String sessionId = IdUtil.getSnowflakeNextIdStr();
        redisChatHistoryRepository.save(girlCode, userId, sessionId);
        return sessionId;

    }
}
