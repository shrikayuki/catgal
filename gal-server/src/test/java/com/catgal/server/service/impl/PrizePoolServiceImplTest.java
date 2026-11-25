package com.catgal.server.service.impl;

import com.catgal.common.autoconfigure.mq.RabbitMqHelper;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.DrawRecord;
import com.catgal.server.domain.po.PrizePool;
import com.catgal.common.constants.RedisConstant;
import com.catgal.server.mapper.DrawRecordMapper;
import com.catgal.server.mapper.PrizePoolMapper;
import com.catgal.server.service.IUserCutePointsService;
import com.catgal.server.service.RedisDirtySetProcessor;
import com.catgal.server.service.IRewardControlService;
import com.catgal.server.service.IRewardTransactionService;
import com.catgal.server.service.IConfirmedRewardPublisher;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.List;
import org.springframework.data.redis.core.HashOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrizePoolServiceImplTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final IUserCutePointsService pointsService = mock(IUserCutePointsService.class);
    private final IConfirmedRewardPublisher mqHelper = mock(IConfirmedRewardPublisher.class);
    private final IRewardControlService control = mock(IRewardControlService.class);
    private final PrizePoolMapper prizePoolMapper = mock(PrizePoolMapper.class);
    private final DrawRecordMapper drawRecordMapper = mock(DrawRecordMapper.class);
    private final RedisDirtySetProcessor dirtySetProcessor = mock(RedisDirtySetProcessor.class);
    private final PrizePoolServiceImpl service = new PrizePoolServiceImpl(
            redisTemplate, pointsService, mqHelper, prizePoolMapper, drawRecordMapper, dirtySetProcessor,
            control, mock(IRewardTransactionService.class));

    @BeforeEach void setupGate() {
        when(control.legacyDrain(any())).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    }

    @Test
    void duplicateDrawRecordMessageIsIdempotent() {
        DrawRecord message = new DrawRecord()
                .setBizId("draw-1")
                .setUserId(1L)
                .setPrizeId(2L);
        when(drawRecordMapper.insertIdempotent(any(DrawRecord.class))).thenReturn(0);

        assertDoesNotThrow(() -> service.checkRemainStockAndCreateDrawRecord(message));
        verify(drawRecordMapper).insertIdempotent(message);
    }

    @Test
    void drawRecordMessageRequiresBusinessId() {
        DrawRecord message = new DrawRecord().setUserId(1L).setPrizeId(2L);
        assertThrows(BusinessException.class,
                () -> service.checkRemainStockAndCreateDrawRecord(message));
    }

    @Test @SuppressWarnings("unchecked")
    void oldMonthDirtyPrizeUsesItsStoredMonthInsteadOfCalendarMonth() {
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hash);
        when(prizePoolMapper.selectBatchIds(anyCollection())).thenReturn(List.of(new PrizePool()
                .setId(2L).setMonth("202605").setTotalStock(10).setRemainStock(10)));
        when(hash.get("prize:202605:cache", "2")).thenReturn("{\"id\":2,\"totalStock\":10,\"remainStock\":8}");
        doAnswer(invocation -> {
            ((Consumer<List<String>>) invocation.getArgument(3)).accept(List.of("2")); return 1;
        }).when(dirtySetProcessor).processBatch(anyString(), anyInt(), eq(true), any());
        service.prizeStockToDB(RedisConstant.PRIZE_POOL_CHANGE_SET_KEY, 10);
        verify(hash).get("prize:202605:cache", "2");
        verify(prizePoolMapper).batchUpdateRemainStock(argThat(updates -> updates.size() == 1 && updates.get(0).getRemainStock() == 8));
    }
}
