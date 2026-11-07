package com.catgal.server.mapper;

import com.catgal.server.domain.po.DrawRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;

/**
 * <p>
 * 抽奖记录表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-05-27
 */
public interface DrawRecordMapper extends BaseMapper<DrawRecord> {

    @Insert("""
            INSERT INTO draw_record (biz_id, prize_id, user_id)
            VALUES (#{bizId}, #{prizeId}, #{userId})
            ON DUPLICATE KEY UPDATE biz_id = VALUES(biz_id)
            """)
    int insertIdempotent(DrawRecord record);
}
