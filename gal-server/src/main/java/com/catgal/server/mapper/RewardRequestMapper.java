package com.catgal.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.catgal.server.domain.po.RewardRequest;

/** 普通 CRUD 使用 MyBatis-Plus，用户行锁负责串行化同用户请求。 */
public interface RewardRequestMapper extends BaseMapper<RewardRequest> { }
