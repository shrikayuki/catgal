/*
 * 阅读说明
 * 萌萌点流水的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
package com.catgal.server.service;

import com.catgal.server.domain.po.CutePointsRecord;
import com.baomidou.mybatisplus.extension.service.IService;
import com.catgal.common.domain.dto.ScrollPageDTO;
import com.catgal.common.domain.query.ScrollPageQuery;
import com.catgal.server.domain.vo.CutePointsRecordVO;

/**
 * <p>
 * 萌萌点流水表 服务类
 * </p>
 *
 * @author rance
 * @since 2026-05-23
 */
public interface ICutePointsRecordService extends IService<CutePointsRecord> {
    /** 从认证上下文限定本人流水，按时间和 ID 的复合游标向更早记录翻页。 */
    ScrollPageDTO<CutePointsRecordVO> queryMyRecords(ScrollPageQuery query);
}
