package com.catgal.server.task;

import cn.hutool.json.JSONUtil;
import com.catgal.common.domain.vo.PrizeVO;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.service.IPrizePoolService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.catgal.common.constants.DateFormatConstant.YM_FORMATTER;
import static com.catgal.common.constants.RedisConstant.PRIZE_DATE_CACHE_KEY;


@Component
@Slf4j
@RequiredArgsConstructor
public class initPrizePoolPerMonthTask {

    private final StringRedisTemplate redisTemplate;
    private final IPrizePoolService prizeService;


    @Scheduled(cron = "0 0 1 1 * *")
    public void initPrizeCachePerMonth() {
        LocalDate now = LocalDate.now();
        String month = YM_FORMATTER.format(now);
        List<PrizeVO> poolInfoByMonth = prizeService.getPoolInfoByMonth(month);
        if (CollUtils.isEmpty(poolInfoByMonth)) {
            log.error("奖池无数据,请放奖品");
            return;
        }

        String key = StringUtils.format(PRIZE_DATE_CACHE_KEY, month);

        // 清理上月
        String lastMonth = YM_FORMATTER.format(now.minusMonths(1));
        redisTemplate.delete(StringUtils.format(PRIZE_DATE_CACHE_KEY, lastMonth));
        log.info("清理上月奖池缓存: {}", lastMonth);

        // 写入本月
        Map<String, String> map = new HashMap<>();

        for (PrizeVO prize : poolInfoByMonth) {
            map.put(prize.getId().toString(), JSONUtil.toJsonStr(prize));

        }


        redisTemplate.opsForHash().putAll(key, map);

        log.info("{} 月奖池缓存初始化完成，共 {} 个奖品", month, poolInfoByMonth.size());



    }



}
