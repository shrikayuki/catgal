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

    PageDTO<DrawRecordVO> queryMyPrizes(PageQuery query);
}
