package com.catgal.server.service.impl;

import com.catgal.server.domain.po.Tag;
import com.catgal.server.mapper.TagMapper;
import com.catgal.server.service.ITagService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 游戏标签表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-05-30
 */
@Service
public class TagServiceImpl extends ServiceImpl<TagMapper, Tag> implements ITagService {

}
