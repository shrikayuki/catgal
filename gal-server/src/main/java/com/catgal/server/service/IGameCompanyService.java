/*
 * 阅读说明
 * 游戏与会社关联的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
package com.catgal.server.service;

import com.catgal.server.domain.po.GameCompany;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 游戏-会社关联表 服务类
 * </p>
 *
 * @author rance
 * @since 2026-05-30
 */
public interface IGameCompanyService extends IService<GameCompany> {

}
