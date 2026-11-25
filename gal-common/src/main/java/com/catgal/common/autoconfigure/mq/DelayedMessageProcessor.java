/*
 * 阅读说明
 * 将延迟参数写入 AMQP 消息属性，交给支持相应能力的 Broker 处理。
 */
package com.catgal.common.autoconfigure.mq;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;

import java.time.Duration;

public class DelayedMessageProcessor extends BasicIdMessageProcessor {

    private final long delay;

    public DelayedMessageProcessor(Duration delay) {
        this.delay = delay.toMillis();
    }

    @Override
    public Message postProcessMessage(Message message) throws AmqpException {
        // 1.添加消息id
        super.postProcessMessage(message);
        // 2.添加延迟时间
        message.getMessageProperties().setHeader("x-delay", delay);
        return message;
    }
}
