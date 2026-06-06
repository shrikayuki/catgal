package com.catgal.server.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.vo.DrawRecordVO;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.CollUtils;
import com.catgal.server.domain.po.DrawRecord;
import com.catgal.server.domain.po.PrizePool;
import com.catgal.server.mapper.DrawRecordMapper;
import com.catgal.server.mapper.PrizePoolMapper;
import com.catgal.server.service.IDrawRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.server.service.IPrizePoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import javax.management.Query;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 抽奖记录表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-05-27
 */
@Service
@RequiredArgsConstructor
public class DrawRecordServiceImpl extends ServiceImpl<DrawRecordMapper, DrawRecord> implements IDrawRecordService {

    private final PrizePoolMapper prizePoolMapper;

    @Override
    public PageDTO<DrawRecordVO> queryMyPrizes(PageQuery query) {
        Long UserId = UserContext.getUserId();
        if (UserId == null) {
            throw new RuntimeException("用户不存在");
        }
        Page<DrawRecord> page = lambdaQuery().eq(DrawRecord::getUserId, UserId).page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<DrawRecord> records = page.getRecords();
        if (CollUtil.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        Set<Long> prizeIds = records.stream().map(DrawRecord::getPrizeId).collect(Collectors.toSet());
        List<PrizePool> prizePools = prizePoolMapper.selectBatchIds(prizeIds);
        Map<Long, PrizePool> prizeMap = prizePools.stream()
                .collect(Collectors.toMap(
                        PrizePool::getId,
                        Function.identity(),
                        (existing, replacement) -> existing  // 遇到重复key时保留已存在的
                ));
        List<DrawRecordVO> vos = new ArrayList<>(records.size());
        for (DrawRecord record : records) {
            DrawRecordVO drawRecordVO = BeanUtils.copyBean(record, DrawRecordVO.class);
            if (drawRecordVO != null) {
                drawRecordVO.setUserId(UserId);
                drawRecordVO.setPrizeImage(prizeMap.getOrDefault(record.getPrizeId(), null).getPrizeImage());
                drawRecordVO.setPrizeName(prizeMap.getOrDefault(record.getPrizeId(), null).getPrizeName());
            }
            vos.add(drawRecordVO);

        }
        return PageDTO.of(page, vos);
    }
}
