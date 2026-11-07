package com.catgal.server.service.impl;

import com.catgal.common.autoconfigure.mq.RabbitMqHelper;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.DrawRecord;
import com.catgal.server.mapper.DrawRecordMapper;
import com.catgal.server.mapper.PrizePoolMapper;
import com.catgal.server.service.IUserCutePointsService;
import com.catgal.server.service.RedisDirtySetProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrizePoolServiceImplTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final IUserCutePointsService pointsService = mock(IUserCutePointsService.class);
    private final RabbitMqHelper mqHelper = mock(RabbitMqHelper.class);
    private final PrizePoolMapper prizePoolMapper = mock(PrizePoolMapper.class);
    private final DrawRecordMapper drawRecordMapper = mock(DrawRecordMapper.class);
    private final RedisDirtySetProcessor dirtySetProcessor = mock(RedisDirtySetProcessor.class);
    private final PrizePoolServiceImpl service = new PrizePoolServiceImpl(
            redisTemplate, pointsService, mqHelper, prizePoolMapper, drawRecordMapper, dirtySetProcessor);

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
}
