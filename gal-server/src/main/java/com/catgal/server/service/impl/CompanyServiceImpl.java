/*
 * 阅读说明
 * 会社资料 CRUD，基础操作使用 MyBatis-Plus，锁查询由 Mapper 负责。
 * 名称唯一约束、revision 条件更新和引用检查分别处理重复、并发编辑与安全删除。
 */
package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.catalog.CatalogKind;
import com.catgal.server.catalog.CatalogValidation;
import com.catgal.server.domain.po.Company;
import com.catgal.server.domain.po.GameCompany;
import com.catgal.server.domain.query.CatalogPageQuery;
import com.catgal.server.domain.vo.CatalogEntryVO;
import com.catgal.server.mapper.CompanyMapper;
import com.catgal.server.mapper.GameCompanyMapper;
import com.catgal.server.service.ICompanyService;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CompanyServiceImpl extends ServiceImpl<CompanyMapper, Company> implements ICompanyService {
    private final CompanyMapper companyMapper;
    private final GameCompanyMapper gameCompanyMapper;

    @Override
    public PageDTO<CatalogEntryVO> pageCatalog(CatalogPageQuery query) {
        Page<Company> page = CatalogValidation.page(query);
        String keyword = CatalogValidation.keyword(query);
        companyMapper.selectPage(page, Wrappers.<Company>lambdaQuery()
                .like(Company::getName, keyword).orderByDesc(Company::getId));
        return new PageDTO<>(page.getTotal(), page.getPages(),
                page.getRecords().stream().map(this::toView).toList());
    }

    @Override
    public CatalogEntryVO getCatalog(long id) {
        CatalogValidation.requireId(CatalogKind.COMPANY, id);
        Company row = companyMapper.selectById(id);
        if (row == null) throw BusinessException.notFound("会社不存在");
        return toView(row);
    }

    @Override
    @Transactional
    // 规范化名称并插入，数据库唯一约束竞争转换为 409 重复名称。
    public CatalogEntryVO createCatalog(String requestedName) {
        Company row = new Company();
        row.setName(CatalogValidation.normalizeName(CatalogKind.COMPANY, requestedName));
        try {
            if (companyMapper.insert(row) != 1 || row.getId() == null) {
                throw BusinessException.internalError("创建资料后未获取到ID");
            }
        } catch (DuplicateKeyException exception) {
            throw BusinessException.conflict("会社名称已存在");
        }
        return getCatalog(row.getId().longValue());
    }

    @Override
    @Transactional
    // UPDATE 的条件同时包含 ID 与 revision，原子递增版本以识别并发编辑。
    public CatalogEntryVO updateCatalog(long id, String requestedName, Long revision) {
        CatalogValidation.requireId(CatalogKind.COMPANY, id);
        CatalogValidation.requireRevision(revision);
        String name = CatalogValidation.normalizeName(CatalogKind.COMPANY, requestedName);
        int updated;
        try {
            updated = companyMapper.update(null, Wrappers.<Company>lambdaUpdate()
                    .eq(Company::getId, id).eq(Company::getRevision, revision)
                    .set(Company::getName, name)
                    .setSql("revision = revision + 1, update_time = CURRENT_TIMESTAMP"));
        } catch (DuplicateKeyException exception) {
            throw BusinessException.conflict("会社名称已存在");
        }
        if (updated != 1) {
            getCatalog(id);
            throw BusinessException.conflict("会社已被修改，请刷新后重试");
        }
        return getCatalog(id);
    }

    @Override
    @Transactional
    // 先锁目标资料再检查引用，有关联时返回冲突，确保游戏关系不被静默破坏。
    public void deleteCatalog(long id, Long revision) {
        CatalogValidation.requireId(CatalogKind.COMPANY, id);
        CatalogValidation.requireRevision(revision);
        Long currentRevision = companyMapper.selectRevisionForUpdate(id);
        if (currentRevision == null) throw BusinessException.notFound("会社不存在");
        if (!currentRevision.equals(revision)) {
            throw BusinessException.conflict("资料已被修改，请刷新后重试");
        }
        Long references = gameCompanyMapper.selectCount(
                Wrappers.<GameCompany>lambdaQuery().eq(GameCompany::getCompanyId, id));
        if (references != null && references > 0) {
            throw BusinessException.conflict("会社仍被游戏引用，请先解除关联");
        }
        try {
            if (companyMapper.delete(Wrappers.<Company>lambdaQuery()
                    .eq(Company::getId, id).eq(Company::getRevision, revision)) != 1) {
                throw BusinessException.conflict("资料已被并发修改，请刷新后重试");
            }
        } catch (DataIntegrityViolationException exception) {
            throw BusinessException.conflict("会社仍被引用，请刷新后检查关联");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    // 复用调用方事务，资料 ID 排序后获取共享锁，保护游戏关系替换中的存在性判断。
    public void lockEntries(Collection<Long> ids) {
        CatalogValidation.requireLockIds(CatalogKind.COMPANY, ids);
        if (ids.isEmpty()) return;
        List<Long> found = companyMapper.selectIdsForShare(ids.stream().sorted().toList());
        if (found.size() != ids.size()) throw BusinessException.badRequest("部分会社不存在");
    }

    private CatalogEntryVO toView(Company row) {
        return new CatalogEntryVO(row.getId(), row.getName(), row.getRevision(),
                row.getCreateTime(), row.getUpdateTime());
    }
}
