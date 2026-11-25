/*
 * 阅读说明
 * 抽奖记录的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
package com.catgal.server.service;

import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.vo.DrawRecordVO;
import com.catgal.server.domain.po.DrawRecord;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 抽奖记录表 服务类
 * </p>
 *
 * @author rance
 * @since 2026-05-27
 */
public interface IDrawRecordService extends IService<DrawRecord> {

    /** 仅查询当前登录用户的中奖记录，批量装配奖品展示信息。 */
    PageDTO<DrawRecordVO> queryMyPrizes(PageQuery query);
}
