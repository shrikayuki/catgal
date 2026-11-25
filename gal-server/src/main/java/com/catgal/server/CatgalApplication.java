/*
 * 阅读说明
 * Java 服务启动入口。Spring Boot 从当前包扫描 Controller、Service 和配置。
 * 主方法创建应用上下文，定时任务及异步能力由相应注解与配置启用。
 *
 * 建议沿一条业务请求逐层阅读
 * 1. WebConfig 注册拦截器，LoginInterceptor 建立当前请求的用户身份。
 * 2. Controller 绑定路径、查询与正文参数，Service 负责业务规则和事务编排。
 * 3. Mapper/BaseMapper/mapper XML 执行 SQL，PO 映射数据，DTO 收请求，VO 组装响应。
 * 4. GameController → GameServiceImpl → GameSearchService，学习 MySQL/ES 查询与回退。
 * 5. FavoriteItemController → FavoriteItemServiceImpl → lua/favorite，学习完整快照和原子变更。
 * 6. RedisDirtySetProcessor、MQ Listener 和 task 包展示后台同步、确认和失败重试。
 * 7. StorageFileServiceImpl → IFileBindingService/IObjectStorageService，学习文件状态与引用解耦。
 * 8. AiSessionController → PythonAiServiceClient → ai-service/app/persistence，学习持久化 SSE。
 * 9. GlobalExceptionHandler 将异常收敛成 R，RequestTraceFilter 提供请求关联编号。
 */
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
