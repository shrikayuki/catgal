package com.catgal.server.service;

/** 资源状态提交后的持久统计校准，调度和管理员运维共用此接口。 */
public interface IResourceCountService {
    void synchronizePending();
    long pendingCount();
}
