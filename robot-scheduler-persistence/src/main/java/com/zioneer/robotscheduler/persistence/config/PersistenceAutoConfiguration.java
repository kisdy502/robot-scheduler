package com.zioneer.robotscheduler.persistence.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.zioneer.robotscheduler.persistence.support.AuditUserProvider;
import com.zioneer.robotscheduler.persistence.support.CommonFieldMetaObjectHandler;
import com.zioneer.robotscheduler.persistence.support.SnowflakeIdGenerator;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 持久层自动装配：分页插件（PostgreSQL）+ 审计填充 + 雪花 ID + Mapper 扫描。
 *
 * <p>必须先于 MybatisPlusAutoConfiguration 处理：@MapperScan 注册 MapperScannerConfigurer 后，
 * MyBatis 的兜底扫描器（扫根包找 @Mapper）才会让位；否则它抢先执行——找不到 @Mapper 报
 * "No MyBatis mapper was found"警告，找到了则双重注册报 "Bean already defined"。</p>
 */
@AutoConfiguration
@AutoConfigureBefore(MybatisPlusAutoConfiguration.class)
@MapperScan("com.zioneer.robotscheduler.persistence.mapper")
public class PersistenceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

    @Bean
    @ConditionalOnMissingBean(MetaObjectHandler.class)
    public MetaObjectHandler commonFieldMetaObjectHandler() {
        return new CommonFieldMetaObjectHandler(defaultAuditUserProvider());
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditUserProvider defaultAuditUserProvider() {
        return () -> "system";
    }

    @Bean
    @ConditionalOnMissingBean
    public SnowflakeIdGenerator snowflakeIdGenerator() {
        return new SnowflakeIdGenerator();
    }
}
