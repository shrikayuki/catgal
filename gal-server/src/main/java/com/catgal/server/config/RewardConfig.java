package com.catgal.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import static com.catgal.server.service.IUserAccountStatusService.TIME_ZONE;

/** 签到日期和奖池月份统一使用站点时间，测试通过固定 Clock 隔离真实当月奖池。 */
@Configuration
public class RewardConfig {
    @Bean
    public Clock rewardClock() { return Clock.system(TIME_ZONE); }
}
