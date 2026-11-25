/*
 * 阅读说明
 * 本人积分流水的滚动查询，使用 create_time 与 id 的复合游标。
 * 多读一行计算 hasMore，同时间记录靠 id 排序继续定位，兼容历史空时间。
 */
package com.catgal.server.service.impl;

import com.catgal.server.domain.po.CutePointsRecord;
import com.catgal.server.mapper.CutePointsRecordMapper;
import com.catgal.server.service.ICutePointsRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.catgal.common.domain.dto.ScrollPageDTO;
import com.catgal.common.domain.query.ScrollPageQuery;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.vo.CutePointsRecordVO;
import com.catgal.server.security.CurrentUserProvider;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 萌萌点流水表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-05-23
 */
@Service
@RequiredArgsConstructor
public class CutePointsRecordServiceImpl extends ServiceImpl<CutePointsRecordMapper, CutePointsRecord> implements ICutePointsRecordService {
    private final CutePointsRecordMapper recordMapper;
    private final CurrentUserProvider currentUserProvider;

    @Override
    // 从认证上下文限定本人流水，按时间和 ID 的复合游标向更早记录翻页。
    public ScrollPageDTO<CutePointsRecordVO> queryMyRecords(ScrollPageQuery query) {
        Long userId = currentUserProvider.requireUserId();
        if (query == null || query.getPageSize() == null
                || query.getPageSize() < 1 || query.getPageSize() > 100) {
            throw BusinessException.badRequest("每页数量须在1到100之间");
        }
        Cursor cursor = parseCursor(query.getCursor());
        LambdaQueryWrapper<CutePointsRecord> wrapper = Wrappers.<CutePointsRecord>lambdaQuery()
                .select(CutePointsRecord::getId, CutePointsRecord::getCutePoints,
                        CutePointsRecord::getType, CutePointsRecord::getBizId,
                        CutePointsRecord::getBeforePoints, CutePointsRecord::getAfterPoints,
                        CutePointsRecord::getRemark, CutePointsRecord::getCreateTime)
                .eq(CutePointsRecord::getUserId, userId);
        if (cursor != null) {
            if (cursor.createTime() == null) {
                wrapper.isNull(CutePointsRecord::getCreateTime).lt(CutePointsRecord::getId, cursor.id());
            } else {
                wrapper.and(remaining -> remaining
                        .lt(CutePointsRecord::getCreateTime, cursor.createTime())
                        .or(tied -> tied.eq(CutePointsRecord::getCreateTime, cursor.createTime())
                                .lt(CutePointsRecord::getId, cursor.id()))
                        .or().isNull(CutePointsRecord::getCreateTime));
            }
        }
        int size = query.getPageSize();
        List<CutePointsRecord> rows = recordMapper.selectList(wrapper
                .orderByDesc(CutePointsRecord::getCreateTime, CutePointsRecord::getId)
                .last("LIMIT " + (size + 1)));
        boolean hasMore = rows.size() > size;
        List<CutePointsRecord> visible = rows.subList(0, Math.min(size, rows.size()));
        String nextCursor = hasMore ? encodeCursor(visible.get(visible.size() - 1)) : null;
        List<CutePointsRecordVO> values = visible.stream().map(row -> new CutePointsRecordVO(
                row.getId(), row.getCutePoints(), row.getType(), row.getBizId(), row.getBeforePoints(),
                row.getAfterPoints(), row.getRemark(), row.getCreateTime())).toList();
        return new ScrollPageDTO<>(values, nextCursor, hasMore);
    }

    // 解析并验证游标结构及范围，非法值转换为业务参数错误。
    private Cursor parseCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) return null;
        if (cursor.length() > 128) throw BusinessException.badRequest("流水游标格式错误");
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = value.indexOf('|');
            if (separator < 1) throw new IllegalArgumentException();
            String time = value.substring(0, separator);
            long id = Long.parseLong(value.substring(separator + 1));
            if (id <= 0) throw new IllegalArgumentException();
            LocalDateTime createTime = "null".equals(time) ? null : LocalDateTime.parse(time);
            if (createTime != null && (createTime.getYear() < 1000 || createTime.getYear() > 9999)) {
                throw new IllegalArgumentException();
            }
            return new Cursor(createTime, id);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw BusinessException.badRequest("流水游标格式错误");
        }
    }

    // 将下一页定位信息编码为游标，客户端按原样回传。
    private String encodeCursor(CutePointsRecord row) {
        String value = row.getCreateTime() + "|" + row.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record Cursor(LocalDateTime createTime, long id) { }
}
