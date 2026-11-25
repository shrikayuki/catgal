/*
 * 阅读说明
 * 用户积分余额的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
package com.catgal.server.service;

import com.catgal.server.domain.po.UserCutePoints;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 用户萌萌点汇总表 服务类
 * </p>
 *
 * @author rance
 * @since 2026-05-23
 */
public interface IUserCutePointsService extends IService<UserCutePoints> {

    /** fresh=true 在新流程直接回库，供提交操作后立即刷新余额。 */
    Integer getUserCutePointsCache(Long userId, boolean fresh);

    /** 读取积分缓存，缺失时从数据库余额初始化，区分合法 0 和缺失字段。 */
    Integer getUserCutePointsCache(Long userId);

    /** 对已领取用户批量同步积分，任务成功后由处理器确认，异常保留恢复入口。 */
    void userCutePointsToDB(String key, Integer batchSize);
}
