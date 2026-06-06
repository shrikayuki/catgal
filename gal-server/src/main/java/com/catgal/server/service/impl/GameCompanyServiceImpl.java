package com.catgal.server.service.impl;

import com.catgal.server.domain.po.GameCompany;
import com.catgal.server.mapper.GameCompanyMapper;
import com.catgal.server.service.IGameCompanyService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 游戏-会社关联表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-05-30
 */
@Service
public class GameCompanyServiceImpl extends ServiceImpl<GameCompanyMapper, GameCompany> implements IGameCompanyService {

}
