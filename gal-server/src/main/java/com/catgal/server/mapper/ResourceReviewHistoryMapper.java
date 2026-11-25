package com.catgal.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.catgal.server.domain.po.ResourceReviewHistory;

/** 常规历史插入/分页复用 MyBatis-Plus。历史仅在资源业务事务中写入。 */
public interface ResourceReviewHistoryMapper extends BaseMapper<ResourceReviewHistory> {
}
