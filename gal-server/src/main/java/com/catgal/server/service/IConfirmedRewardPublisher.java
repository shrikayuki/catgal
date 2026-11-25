package com.catgal.server.service;

/** 只用于积分切换与新 Outbox，确认等待与原通用 MQ 帮助类分离。 */
public interface IConfirmedRewardPublisher {
    void send(String exchange, String routingKey, Object payload);
    int inFlight();
}
