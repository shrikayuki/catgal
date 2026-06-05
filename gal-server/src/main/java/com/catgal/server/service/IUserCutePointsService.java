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

    Integer getUserCutePointsCache(Long userId);

    void userCutePointsToDB(String key, Integer batchSize);
}
