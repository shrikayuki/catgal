package com.catgal.server.service.impl;

import com.catgal.server.service.IConfirmedRewardPublisher;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.stereotype.Service;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

/** 专用模板不会改写其他模块的回调。mandatory return 和 broker confirm 必须都成功。 */
@Service
public class ConfirmedRewardPublisherImpl implements IConfirmedRewardPublisher {
    private final RabbitTemplate template;
    private final Set<String> outstanding = ConcurrentHashMap.newKeySet();

    public ConfirmedRewardPublisherImpl(ConnectionFactory factory, MessageConverter converter) {
        template = new RabbitTemplate(factory);
        template.setMessageConverter(converter);
        template.setMandatory(true);
        template.setBeforePublishPostProcessors(message -> {
            // 到达 channel 的实际发布步骤才计入待确认。序列化、建连阶段失败不会产生 broker 回调。
            // 旧来源发送期间还持有数据库共享屏障，建连中的请求由该屏障覆盖。
            outstanding.add(message.getMessageProperties().getCorrelationId());
            return message;
        });
    }

    @Override
    public void send(String exchange, String routingKey, Object payload) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        correlation.getFuture().whenComplete((ack, failure) -> outstanding.remove(correlation.getId()));
        try {
            template.convertAndSend(exchange, routingKey, payload, message -> {
                message.getMessageProperties().setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
                message.getMessageProperties().setCorrelationId(correlation.getId());
                return message;
            }, correlation);
            // 超时保留尚未终结的 future，切换检查能发现结果仍不明确的旧消息。
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null)
                throw new IllegalStateException("积分通知未被消息队列确认接收");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待积分消息确认被中断", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("积分消息确认超时或连接失败", exception);
        } finally {
            // 补齐“终态回调先发生、发布步骤随后登记”的竞争，结果仍不明确时继续保留待确认记录。
            if (correlation.getFuture().isDone()) outstanding.remove(correlation.getId());
        }
    }

    @Override
    public int inFlight() { return outstanding.size(); }
}
