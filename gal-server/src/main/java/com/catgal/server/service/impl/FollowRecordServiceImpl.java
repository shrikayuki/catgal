package com.catgal.server.service.impl;

import com.catgal.server.domain.po.FollowRecord;
import com.catgal.server.mapper.FollowRecordMapper;
import com.catgal.server.service.IFollowRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 关注记录表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-06-05
 */
@Service
public class FollowRecordServiceImpl extends ServiceImpl<FollowRecordMapper, FollowRecord> implements IFollowRecordService {

}
