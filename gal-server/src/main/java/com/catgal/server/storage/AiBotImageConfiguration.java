/*
 * 阅读说明
 * 装配机器人形象相关的文件绑定配置，复用托管图片基础能力。
 */
package com.catgal.server.storage;

import com.catgal.server.mapper.AiBotMapper;
import com.catgal.server.mapper.StorageFileMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.IFileBindingService;
import com.catgal.server.service.impl.AiBotImageFileBindingServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiBotImageConfiguration {
    @Bean
    IFileBindingService aiBotAvatarBinding(AiBotMapper bots, StorageFileMapper files, CurrentUserProvider user) {
        return new AiBotImageFileBindingServiceImpl(AiBotImageKind.AVATAR, bots, files, user);
    }

    @Bean
    IFileBindingService aiBotPortraitBinding(AiBotMapper bots, StorageFileMapper files, CurrentUserProvider user) {
        return new AiBotImageFileBindingServiceImpl(AiBotImageKind.PORTRAIT, bots, files, user);
    }
}
