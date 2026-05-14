package com.catgal.server.domain.po;

import java.math.BigDecimal;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.LocalDate;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.catgal.common.enums.LanguageEnum;
import com.catgal.common.enums.PlatformEnum;
import com.catgal.common.enums.ResourceTypeEnum;
import com.catgal.common.utils.EnumListConverter;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 游戏主表
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("game")
public class Game implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 游戏主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 游戏名称
     */
    private String name;

    /**
     * 游戏封面URL
     */
    private String coverUrl;

    /**
     * 游戏介绍
     */
    private String introduction;

    /**
     * 游戏发售时间
     */
    private LocalDate releaseDate;

    /**
     * 游戏评分，0-10分，保留两位小数
     */
    private BigDecimal rating;

    /**
     * 下载人数
     */
    private Integer downloadCount;

    /**
     * 收藏人数
     */
    private Integer favoriteCount;

    /**
     * 下载资源的数量
     */
    private Integer resourceCount;

    /**
     * 评论数
     */
    private Integer commentCount;

    /**
     * 评价数
     */
    private Integer reviewCount;

    /**
     * 游戏发布人ID（关联user表）
     */
    private Long publisherId;

    /**
     * 状态：0-草稿，1-已发布，2-已下架
     */
    private Integer status;

    /**
     * 浏览数
     */
    private Integer viewCount;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    // ========== 新增冗余字段（从资源表汇总） ==========

    /**
     * 资源类型汇总（逗号分隔）：pc,生肉,汉化,手机,补丁,模拟器,其他
     */
    private String types;

    /**
     * 语言汇总（逗号分隔）：中文,日语,英语,其他
     */
    private String languages;

    /**
     * 平台汇总（逗号分隔）：win,mac,linux,android,ios,其他
     */
    private String platforms;

    // ========== getter/setter 自动转换 ==========

    public List<ResourceTypeEnum> getTypeList() {
        return EnumListConverter.toList(this.types, ResourceTypeEnum::fromCode);
    }

    public Game setTypeList(List<ResourceTypeEnum> typeList) {
        this.types = EnumListConverter.toString(typeList);
        return this;
    }

    public List<LanguageEnum> getLanguageList() {
        return EnumListConverter.toList(this.languages, LanguageEnum::fromCode);
    }

    public Game setLanguageList(List<LanguageEnum> languageList) {
        this.languages = EnumListConverter.toString(languageList);
        return this;
    }

    public List<PlatformEnum> getPlatformList() {
        return EnumListConverter.toList(this.platforms, PlatformEnum::fromCode);
    }

    public Game setPlatformList(List<PlatformEnum> platformList) {
        this.platforms = EnumListConverter.toString(platformList);
        return this;
    }

    // ========== Set 方法（新增，自动去重） ==========

    public Set<ResourceTypeEnum> getTypeSet() {
        return new LinkedHashSet<>(getTypeList());
    }

    public Set<LanguageEnum> getLanguageSet() {
        return new LinkedHashSet<>(getLanguageList());

    }

    public Set<PlatformEnum> getPlatformSet() {
        return new LinkedHashSet<>(getPlatformList());
    }
}