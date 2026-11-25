/*
 * 阅读说明
 * 游戏标签的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
package com.catgal.server.service;

import com.catgal.server.domain.po.Tag;
import com.baomidou.mybatisplus.extension.service.IService;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.server.domain.query.CatalogPageQuery;
import com.catgal.server.domain.vo.CatalogEntryVO;
import java.util.Collection;

/**
 * <p>
 * 游戏标签表 服务类
 * </p>
 *
 * @author rance
 * @since 2026-05-30
 */
public interface ITagService extends IService<Tag> {
    /** 分页查询资料目录，关键词和排序由资料校验规则收敛。 */
    PageDTO<CatalogEntryVO> pageCatalog(CatalogPageQuery query);

    /** 读取资料名称和 revision，后续编辑或删除回传该版本。 */
    CatalogEntryVO getCatalog(long id);

    /** 规范化名称并创建资料，同名由数据库唯一约束保护。 */
    CatalogEntryVO createCatalog(String name);

    /** 使用 ID 与 revision 条件修改名称，版本过期返回冲突。 */
    CatalogEntryVO updateCatalog(long id, String name, Long revision);

    /** 检查当前版本及游戏引用后删除资料，仍被引用的记录保留并返回冲突。 */
    void deleteCatalog(long id, Long revision);

    /** The caller must hold the game row lock within the same transaction. */
    void lockEntries(Collection<Long> ids);
}
