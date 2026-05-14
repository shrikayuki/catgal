package com.catgal.server.service;

import com.catgal.common.domain.dto.ResourceAddDTO;
import com.catgal.common.domain.query.GameQuery;
import com.catgal.common.domain.vo.GameConnectVO;
import com.catgal.common.domain.vo.GameResourceVO;
import com.baomidou.mybatisplus.extension.service.IService;
import com.catgal.common.domain.vo.ResourceUrlVO;
import com.catgal.server.domain.po.Resource;
import jakarta.validation.Valid;

/**
 * <p>
 * 游戏资源表 服务类
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
public interface IResourceService extends IService<Resource> {

    GameConnectVO<GameResourceVO> pageGameResource(GameQuery query);

    ResourceUrlVO getResourceUrl(Long id);

    void addResource(@Valid ResourceAddDTO dto);

    void deleteResource(Long id);

    String download(Long id);
}
