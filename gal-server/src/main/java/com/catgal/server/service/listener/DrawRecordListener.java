/*
 * 阅读说明
 * 排空旧中奖记录消息，业务号用于重复投递去重。ACTIVE 在抽奖事务中直接写中奖记录。
 */
package com.catgal.server.service.listener;

import com.catgal.server.domain.po.DrawRecord;
import com.catgal.server.mapper.PrizePoolMapper;
import com.catgal.server.service.IPrizePoolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import static com.catgal.common.constants.MqConstants.Exchange.CUTE_POINT_EXCHANGE;
import static com.catgal.common.constants.MqConstants.Exchange.DRAW_RECORD_EXCHANGE;
import static com.catgal.common.constants.MqConstants.Key.DRAW_RECORD_KEY;
import static com.catgal.common.constants.MqConstants.Key.SIGN_CUTE_POINTS_KEY;
import static com.catgal.common.constants.MqConstants.Queue.DRAW_RECORD_QUEUE;

@RequiredArgsConstructor
@Slf4j
@Component
public class DrawRecordListener {

    private final IPrizePoolService prizeService;

    @RabbitListener(id = "legacyDrawRecords", containerFactory = "rewardListenerContainerFactory",
            autoStartup = "#{!@rewardControlServiceImpl.isActive()}", bindings = @QueueBinding(
            value = @Queue(name = DRAW_RECORD_QUEUE, durable = "true"),
            exchange = @Exchange(name = DRAW_RECORD_EXCHANGE),
            key = DRAW_RECORD_KEY
    ))
    public void checkRemainStockAndCreateDrawRecord(DrawRecord message) {
        prizeService.checkRemainStockAndCreateDrawRecord(message);
    }
}
