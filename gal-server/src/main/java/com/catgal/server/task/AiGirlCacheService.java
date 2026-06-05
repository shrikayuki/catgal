package com.catgal.server.task;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.catgal.server.domain.po.AiGirl;
import com.catgal.server.mapper.AiGirlMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiGirlCacheService {


    private final AiGirlMapper aiGirlMapper;

    // Caffeine 本地缓存
    private Cache<String, AiGirl> girlCache;

    @PostConstruct
    public void init() {
        // 构建缓存：最大100条，1小时过期
        this.girlCache = Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .recordStats()  // 记录命中率（调试用）
                .build();
        
        // 服务器启动时加载所有角色
        loadAllGirls();
    }

    /**
     * 启动时加载所有启用角色到缓存
     */
    public void loadAllGirls() {
        List<AiGirl> girls = aiGirlMapper.selectList(
            new LambdaQueryWrapper<AiGirl>()
                .eq(AiGirl::getStatus, 1)
        );
        
        for (AiGirl girl : girls) {
            girlCache.put(girl.getGirlCode(), girl);
            log.info("加载角色到缓存: {} -> {}", girl.getGirlCode(), girl.getName());
        }
        log.info("角色缓存加载完成，共 {} 条", girls.size());
    }

    /**
     * 根据角色编码获取角色（从缓存）
     */
    public AiGirl getByCode(String girlCode) {
        return girlCache.get(girlCode, code -> {
            // 缓存没有时查数据库（兜底）
            log.warn("缓存未命中，从数据库加载: {}", code);
            return aiGirlMapper.selectByGirlCode(code);
        });
    }

    /**
     * 获取所有角色（从缓存）
     */
    public List<AiGirl> getAllGirls() {
        // 方法1：从缓存获取
        Map<String, AiGirl> cacheMap = girlCache.asMap();

        if (!cacheMap.isEmpty()) {
            return new ArrayList<>(cacheMap.values());
        }

        // 方法2：缓存为空，从数据库查并同步到缓存
        synchronized (this) {
            // 双重检查，避免重复查库
            if (girlCache.asMap().isEmpty()) {
                List<AiGirl> girls = aiGirlMapper.selectList(
                        new LambdaQueryWrapper<AiGirl>()
                                .eq(AiGirl::getStatus, 1)
                );

                for (AiGirl girl : girls) {
                    girlCache.put(girl.getGirlCode(), girl);
                }
                return girls;
            }
            return new ArrayList<>(girlCache.asMap().values());
        }
    }

    /**
     * 刷新缓存（修改角色信息后调用）
     */
    public void refreshCache(String girlCode) {
        girlCache.invalidate(girlCode);
        AiGirl girl = aiGirlMapper.selectByGirlCode(girlCode);
        if (girl != null) {
            girlCache.put(girlCode, girl);
            log.info("角色缓存已刷新: {}", girlCode);
        }
    }

    /**
     * 全量刷新（批量修改后调用）
     */
    public void refreshAll() {
        girlCache.invalidateAll();
        loadAllGirls();
    }

    /**
     * 获取缓存统计信息
     */
    public String getStats() {
        return girlCache.stats().toString();
    }
}