package com.catgal.server.service.impl;

import com.catgal.server.domain.po.GameTag;
import com.catgal.server.mapper.GameTagMapper;
import com.catgal.server.service.IGameTagService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 游戏-标签关联表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-05-30
 */
@Service
public class GameTagServiceImpl extends ServiceImpl<GameTagMapper, GameTag> implements IGameTagService {

}
