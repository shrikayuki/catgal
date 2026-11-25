/*
 * 阅读说明
 * 标签资料 CRUD 与游戏引用保护，接口结构与会社维护一致。
 * revision 检测并发编辑，共享资料锁与游戏关联维护保持统一顺序。
 */
package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.catalog.CatalogKind;
import com.catgal.server.catalog.CatalogValidation;
import com.catgal.server.domain.po.Tag;
import com.catgal.server.domain.po.GameTag;
import com.catgal.server.domain.query.CatalogPageQuery;
import com.catgal.server.domain.vo.CatalogEntryVO;
import com.catgal.server.mapper.TagMapper;
import com.catgal.server.mapper.GameTagMapper;
import com.catgal.server.service.ITagService;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>
 * 游戏标签表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-05-30
 */
@Service
@RequiredArgsConstructor
public class TagServiceImpl extends ServiceImpl<TagMapper, Tag> implements ITagService {
    private final TagMapper tagMapper;
    private final GameTagMapper gameTagMapper;

    @Override
    public PageDTO<CatalogEntryVO> pageCatalog(CatalogPageQuery query) {
        Page<Tag> page = CatalogValidation.page(query);
        String keyword = CatalogValidation.keyword(query);
        tagMapper.selectPage(page, Wrappers.<Tag>lambdaQuery()
                .like(Tag::getName, keyword).orderByDesc(Tag::getId));
        return new PageDTO<>(page.getTotal(), page.getPages(),
                page.getRecords().stream().map(this::toView).toList());
    }

    @Override
    public CatalogEntryVO getCatalog(long id) {
        CatalogValidation.requireId(CatalogKind.TAG, id);
        Tag row = tagMapper.selectById(id);
        if (row == null) throw BusinessException.notFound("标签不存在");
        return toView(row);
    }

    @Override
    @Transactional
    // 规范化名称并插入，数据库唯一约束竞争转换为 409 重复名称。
    public CatalogEntryVO createCatalog(String requestedName) {
        Tag row = new Tag();
        row.setName(CatalogValidation.normalizeName(CatalogKind.TAG, requestedName));
        try {
            if (tagMapper.insert(row) != 1 || row.getId() == null) {
                throw BusinessException.internalError("创建资料后未获取到ID");
            }
        } catch (DuplicateKeyException exception) {
            throw BusinessException.conflict("标签名称已存在");
        }
        return getCatalog(row.getId().longValue());
    }

    @Override
    @Transactional
    // UPDATE 的条件同时包含 ID 与 revision，原子递增版本以识别并发编辑。
    public CatalogEntryVO updateCatalog(long id, String requestedName, Long revision) {
        CatalogValidation.requireId(CatalogKind.TAG, id);
        CatalogValidation.requireRevision(revision);
        String name = CatalogValidation.normalizeName(CatalogKind.TAG, requestedName);
        int updated;
        try {
            updated = tagMapper.update(null, Wrappers.<Tag>lambdaUpdate()
                    .eq(Tag::getId, id).eq(Tag::getRevision, revision)
                    .set(Tag::getName, name)
                    .setSql("revision = revision + 1, update_time = CURRENT_TIMESTAMP"));
        } catch (DuplicateKeyException exception) {
            throw BusinessException.conflict("标签名称已存在");
        }
        if (updated != 1) {
            getCatalog(id);
            throw BusinessException.conflict("标签已被修改，请刷新后重试");
        }
        return getCatalog(id);
    }

    @Override
    @Transactional
    // 先锁目标资料再检查引用，有关联时返回冲突，确保游戏关系不被静默破坏。
    public void deleteCatalog(long id, Long revision) {
        CatalogValidation.requireId(CatalogKind.TAG, id);
        CatalogValidation.requireRevision(revision);
        Long currentRevision = tagMapper.selectRevisionForUpdate(id);
        if (currentRevision == null) throw BusinessException.notFound("标签不存在");
        if (!currentRevision.equals(revision)) {
            throw BusinessException.conflict("资料已被修改，请刷新后重试");
        }
        Long references = gameTagMapper.selectCount(
                Wrappers.<GameTag>lambdaQuery().eq(GameTag::getTagId, id));
        if (references != null && references > 0) {
            throw BusinessException.conflict("标签仍被游戏引用，请先解除关联");
        }
        try {
            if (tagMapper.delete(Wrappers.<Tag>lambdaQuery()
                    .eq(Tag::getId, id).eq(Tag::getRevision, revision)) != 1) {
                throw BusinessException.conflict("资料已被并发修改，请刷新后重试");
            }
        } catch (DataIntegrityViolationException exception) {
            throw BusinessException.conflict("标签仍被引用，请刷新后检查关联");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    // 复用调用方事务，资料 ID 排序后获取共享锁，保护游戏关系替换中的存在性判断。
    public void lockEntries(Collection<Long> ids) {
        CatalogValidation.requireLockIds(CatalogKind.TAG, ids);
        if (ids.isEmpty()) return;
        List<Long> found = tagMapper.selectIdsForShare(ids.stream().sorted().toList());
        if (found.size() != ids.size()) throw BusinessException.badRequest("部分标签不存在");
    }

    private CatalogEntryVO toView(Tag row) {
        return new CatalogEntryVO(row.getId().longValue(), row.getName(), row.getRevision(),
                row.getCreateTime(), row.getUpdateTime());
    }
}
