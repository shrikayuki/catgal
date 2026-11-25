package com.catgal.server.service;

import com.catgal.common.domain.dto.PageDTO;
import com.catgal.server.domain.dto.admin.AdminPrizeSaveDTO;
import com.catgal.server.domain.po.PrizePool;
import com.catgal.server.domain.query.admin.AdminPageQuery;

/** 奖池维护契约，Controller 仅绑定请求，库存锁与版本约束在实现中执行。 */
public interface IAdminPrizeService {
    PageDTO<PrizePool> pagePrizes(AdminPageQuery query);
    PrizePool createPrize(AdminPrizeSaveDTO request);
    PrizePool updatePrize(Long id, AdminPrizeSaveDTO request);
    PrizePool updateStatus(Long id, Integer status, String expectedRevision);
}
