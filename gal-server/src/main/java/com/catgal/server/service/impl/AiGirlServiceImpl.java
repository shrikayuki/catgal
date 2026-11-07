package com.catgal.server.service.impl;

import cn.hutool.core.util.IdUtil;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.vo.AIGirlVO;
import com.catgal.common.exception.BusinessException;
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
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.regex.Pattern;

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

    private static final int MAX_USER_MESSAGE_LENGTH = 4_000;
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]{1,64}");

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
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw BusinessException.unauthorized("用户未登录");
        }
        validateIdentifier(girlCode, "角色编码");
        validateIdentifier(sessionId, "会话ID");
        validateUserMessage(userMessage);
        requireOwnedSession(userId, girlCode, sessionId);
        AiGirl girl = requireEnabledGirl(girlCode);

        String girlSystemPrompt = girl.getSystemPrompt();
        if (StringUtils.isBlank(girlSystemPrompt)) {
            throw BusinessException.internalError("角色提示词未配置");
        }
        String conversationId = buildConversationId(userId, girlCode, sessionId);
        log.info("AI聊天请求: userId={}, girlCode={}, sessionId={}, messageLength={}",
                userId, girlCode, sessionId, userMessage.length());

        return chatClient.prompt(girlSystemPrompt).
                user(userMessage)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }

    @Override
    public List<String> getHistorySessionIds(String girlCode) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw BusinessException.unauthorized("用户不存在");
        }
        validateIdentifier(girlCode, "角色编码");

        // 1. 先从 Redis 获取
        List<String> historySessionIds = redisChatHistoryRepository.getHistorySessionIds(girlCode, userId);

        // 2. Redis 没有，查数据库
        if (CollUtils.isEmpty(historySessionIds)) {
            List<String> sessionIds = userSessionMapper.selectByUserAndGirlCode(userId, girlCode);

            // 3. 同步到 Redis（下次就不用查库了）
            if (CollUtils.isNotEmpty(sessionIds)) {
                for (int index = sessionIds.size() - 1; index >= 0; index--) {
                    redisChatHistoryRepository.save(girlCode, userId, sessionIds.get(index));
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
            throw BusinessException.unauthorized("用户未登录");
        }
        validateIdentifier(girlCode, "角色编码");
        requireEnabledGirl(girlCode);
        //雪花算法生成唯一会话id
        String sessionId = IdUtil.getSnowflakeNextIdStr();
        redisChatHistoryRepository.save(girlCode, userId, sessionId);
        return sessionId;

    }

    private AiGirl requireEnabledGirl(String girlCode) {
        AiGirl girl = aiGirlCacheService.getByCode(girlCode);
        if (girl == null || !Objects.equals(girl.getStatus(), 1)) {
            throw BusinessException.notFound("AI角色不存在或未启用");
        }
        return girl;
    }

    private void requireOwnedSession(Long userId, String girlCode, String sessionId) {
        List<String> cachedSessionIds = redisChatHistoryRepository.getHistorySessionIds(girlCode, userId);
        if (cachedSessionIds.contains(sessionId)) {
            return;
        }

        List<String> persistedSessionIds = userSessionMapper.selectByUserAndGirlCode(userId, girlCode);
        if (persistedSessionIds.contains(sessionId)) {
            redisChatHistoryRepository.save(girlCode, userId, sessionId);
            return;
        }

        throw BusinessException.notFound("会话不存在");
    }

    private void validateIdentifier(String value, String fieldName) {
        if (StringUtils.isBlank(value) || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw BusinessException.badRequest(fieldName + "格式不正确");
        }
    }

    private void validateUserMessage(String userMessage) {
        if (StringUtils.isBlank(userMessage)) {
            throw BusinessException.badRequest("消息不能为空");
        }
        if (userMessage.length() > MAX_USER_MESSAGE_LENGTH) {
            throw BusinessException.badRequest("消息长度不能超过" + MAX_USER_MESSAGE_LENGTH + "个字符");
        }
    }

    private String buildConversationId(Long userId, String girlCode, String sessionId) {
        return "user:" + userId + ":girl:" + girlCode + ":session:" + sessionId;
    }
}
