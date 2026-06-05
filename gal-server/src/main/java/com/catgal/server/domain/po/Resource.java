package com.catgal.server.domain.po;

import com.baomidou.mybatisplus.annotation.*;
import com.catgal.common.enums.LanguageEnum;
import com.catgal.common.enums.PlatformEnum;
import com.catgal.common.enums.ResourceTypeEnum;
import com.catgal.common.utils.EnumListConverter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <p>
 * 游戏资源表
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
@Data
@Accessors(chain = true)
@TableName("resource")
@Schema(description = "游戏资源")
public class Resource implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "资源ID", example = "1")
    private Long id;

    @Schema(description = "所属游戏ID", example = "1")
    private Long gameId;

    @Schema(description = "发布者用户ID", example = "1")
    private Long userId;

    @Schema(description = "资源名称", example = "Summer Pockets 本体")
    private String name;

    @Schema(description = "资源链接", example = "https://pan.baidu.com/s/xxxxx")
    private String downloadUrl;

    @Schema(description = "提取码", example = "abcd")
    private String extractCode;

    @Schema(description = "解压密码", example = "123456")
    private String password;

    @Schema(description = "备注", example = "解压后运行setup.exe")
    private String remark;

    @Schema(description = "文件大小", example = "4.2GB")
    private String fileSize;

    /**
     * 资源类型，多个用逗号分隔：pc,生肉,汉化,手机,补丁,模拟器,其他
     */
    @Schema(description = "资源类型（存储字段）", hidden = true)
    private String types;

    /**
     * 语言，多个用逗号分隔：中文,日语,英语,其他
     */
    @Schema(description = "语言（存储字段）", hidden = true)
    private String languages;

    /**
     * 平台，多个用逗号分隔：win,mac,linux,android,ios,其他
     */
    @Schema(description = "平台（存储字段）", hidden = true)
    private String platforms;

    @Schema(description = "下载次数", example = "0")
    private Integer downloadCount;

    @Schema(description = "点赞数", example = "0")
    private Integer likeCount;

    @Schema(description = "状态：0-失效，1-有效，2-待审核", example = "1")
    private Integer status;

    @Schema(description = "是否官方资源：0-用户上传，1-官方", example = "0")
    private Integer isOfficial;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    // ========== getter/setter 自动转换 ==========

    public List<ResourceTypeEnum> getTypeList() {
        return EnumListConverter.toList(this.types, ResourceTypeEnum::fromCode);
    }

    public Resource setTypeList(List<ResourceTypeEnum> typeList) {
        this.types = EnumListConverter.toString(typeList);
        return this;
    }

    public List<LanguageEnum> getLanguageList() {
        return EnumListConverter.toList(this.languages, LanguageEnum::fromCode);
    }

    public Resource setLanguageList(List<LanguageEnum> languageList) {
        this.languages = EnumListConverter.toString(languageList);
        return this;
    }

    public List<PlatformEnum> getPlatformList() {
        return EnumListConverter.toList(this.platforms, PlatformEnum::fromCode);
    }

    public Resource setPlatformList(List<PlatformEnum> platformList) {
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