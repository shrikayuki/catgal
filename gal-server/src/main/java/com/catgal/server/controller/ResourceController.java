package com.catgal.server.controller;


import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.ResourceAddDTO;
import com.catgal.common.domain.query.GameQuery;
import com.catgal.common.domain.vo.GameConnectVO;
import com.catgal.common.domain.vo.GameResourceVO;
import com.catgal.common.domain.vo.GameReviewVO;
import com.catgal.common.domain.vo.ResourceUrlVO;
import com.catgal.server.service.IResourceService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 游戏资源表 前端控制器
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
@RestController
@RequestMapping("/resource")
@RequiredArgsConstructor
public class ResourceController {

    private final IResourceService resourceService;

    @GetMapping("/page")
    @Operation(summary = "分页查询游戏下的资源列表")
    public R<GameConnectVO<GameResourceVO>> pageGameResource(GameQuery query){
        GameConnectVO<GameResourceVO> vo = resourceService.pageGameResource(query);
        return R.ok(vo);
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据资源id查看资源链接")
    public R<ResourceUrlVO> getResourceUrl(@PathVariable Long id){
        ResourceUrlVO vo = resourceService.getResourceUrl(id);
        return R.ok(vo);
    }

    @PostMapping("/{id}/download")
    @Operation(summary = "下载游戏下的资源")
    public R<String> download(@PathVariable Long id) {
        return R.ok(resourceService.download(id));
    }

    @PostMapping
    @Operation(summary = "发布资源")
    public R<Void> addResource(@Valid @RequestBody ResourceAddDTO dto) {
        resourceService.addResource(dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除资源")
    public R<Void> deleteResource(@PathVariable Long id) {
        resourceService.deleteResource(id);
        return R.ok();
    }

}
