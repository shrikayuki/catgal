/*
 * 阅读说明
 * 分页查询游戏下的资源列表、根据资源id查看资源链接、下载游戏下的资源的 HTTP 入口。
 * 路由前缀 /resource，请求绑定后委托 IResourceService。
 * LoginRequired 标记的入口要求登录，上下文用户身份来自服务端认证。
 */
package com.catgal.server.controller;


import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.server.domain.dto.ResourceEditDTO;
import com.catgal.server.domain.dto.ResourceRevisionDTO;
import com.catgal.server.domain.po.ResourceReviewHistory;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.domain.vo.admin.AdminResourceVO;
import com.catgal.common.domain.dto.ResourceAddDTO;
import com.catgal.common.domain.query.GameQuery;
import com.catgal.common.domain.vo.GameConnectVO;
import com.catgal.common.domain.vo.GameResourceVO;
import com.catgal.common.domain.vo.GameReviewVO;
import com.catgal.common.domain.vo.ResourceUrlVO;
import com.catgal.server.service.IResourceService;
import com.catgal.server.mvc.annotation.LoginRequired;
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
    @LoginRequired
    @Operation(summary = "根据资源id查看资源链接")
    public R<ResourceUrlVO> getResourceUrl(@PathVariable Long id){
        ResourceUrlVO vo = resourceService.getResourceUrl(id);
        return R.ok(vo);
    }

    @PostMapping("/{id}/download")
    @LoginRequired
    @Operation(summary = "下载游戏下的资源")
    public R<String> download(@PathVariable Long id) {
        return R.ok(resourceService.download(id));
    }

    @PostMapping
    @LoginRequired
    @Operation(summary = "发布资源")
    public R<Void> addResource(@Valid @RequestBody ResourceAddDTO dto) {
        resourceService.addResource(dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @LoginRequired
    @Operation(summary = "删除资源")
    public R<Void> deleteResource(@PathVariable Long id) {
        resourceService.deleteResource(id);
        return R.ok();
    }

    @GetMapping("/{id}/edit")
    @LoginRequired
    @Operation(summary = "读取本人资源编辑数据")
    public R<AdminResourceVO> editable(@PathVariable Long id) {
        return R.ok(resourceService.getEditable(id));
    }

    @PutMapping("/{id}")
    @LoginRequired
    @Operation(summary = "编辑资源", description = "创作者编辑后重新待审，必须携带当前revision")
    public R<AdminResourceVO> edit(@PathVariable Long id, @Valid @RequestBody ResourceEditDTO dto) {
        return R.ok(resourceService.editResource(id, dto));
    }

    @PostMapping("/{id}/submit")
    @LoginRequired
    @Operation(summary = "失效资源重新送审")
    public R<AdminResourceVO> submit(@PathVariable Long id, @Valid @RequestBody ResourceRevisionDTO dto) {
        return R.ok(resourceService.submitResource(id, dto.getRevision()));
    }

    @GetMapping("/{id}/history")
    @LoginRequired
    @Operation(summary = "查看本人资源审核历史")
    public R<PageDTO<ResourceReviewHistory>> history(@PathVariable Long id, @Valid AdminPageQuery query) {
        return R.ok(resourceService.history(id, query));
    }

}
