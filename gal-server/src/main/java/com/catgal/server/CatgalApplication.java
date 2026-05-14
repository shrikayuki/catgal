package com.catgal.server;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication(exclude = SecurityAutoConfiguration.class) // 默认会扫描 com.catgal.server 及其子包
@EnableScheduling
@EnableTransactionManagement
@MapperScan("com.catgal.server.mapper")  // 指定 Mapper 包路径
@ComponentScan(basePackages = {"com.catgal.server", "com.catgal.common"})
@Slf4j
public class CatgalApplication {
    public static void main(String[] args) {
        SpringApplication.run(CatgalApplication.class, args);
        log.info("CatgalApplication started");
    }
}
