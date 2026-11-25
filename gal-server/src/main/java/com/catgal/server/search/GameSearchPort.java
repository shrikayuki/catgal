/*
 * 阅读说明
 * 游戏检索契约，MySQL 与 ES 都返回 ID 列表和总量，业务层据此回库。
 */
package com.catgal.server.search;

import com.catgal.common.domain.query.GamePageQuery;
import java.util.List;

/** Business search contract, independent of Elasticsearch's query and hit types. */
public interface GameSearchPort {
    SearchPage search(GamePageQuery query);

    record SearchPage(long total, List<Long> ids) {
        public SearchPage {
            ids = List.copyOf(ids);
        }
    }
}
