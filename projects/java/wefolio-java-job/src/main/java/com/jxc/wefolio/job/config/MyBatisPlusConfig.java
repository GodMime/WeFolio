package com.jxc.wefolio.job.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置 — 注册分页、乐观锁和基础实体时间填充能力。
 */
@Configuration
@MapperScan("com.jxc.wefolio.job.mapper")
public class MyBatisPlusConfig {

    /** 基础实体创建时间字段名 */
    private static final String FIELD_CREATED_AT = "createdAt";

    /** 基础实体更新时间字段名 */
    private static final String FIELD_UPDATED_AT = "updatedAt";

    /**
     * 创建 MyBatis-Plus 核心拦截器。
     *
     * @return MyBatis-Plus 拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * 创建基础实体时间字段自动填充处理器。
     *
     * @return MyBatis-Plus 元对象填充处理器
     */
    @Bean
    public MetaObjectHandler baseEntityMetaObjectHandler() {
        return new MetaObjectHandler() {

            /**
             * 新增实体时填充创建时间和更新时间。
             *
             * @param metaObject MyBatis 元对象
             */
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                fillWhenEmpty(metaObject, FIELD_CREATED_AT, now);
                fillWhenEmpty(metaObject, FIELD_UPDATED_AT, now);
            }

            /**
             * 更新实体时刷新更新时间。
             *
             * @param metaObject MyBatis 元对象
             */
            @Override
            public void updateFill(MetaObject metaObject) {
                setFieldValByName(FIELD_UPDATED_AT, LocalDateTime.now(), metaObject);
            }

            /**
             * 字段为空时写入默认值。
             *
             * @param metaObject MyBatis 元对象
             * @param fieldName 字段名
             * @param value 默认值
             */
            private void fillWhenEmpty(MetaObject metaObject, String fieldName, LocalDateTime value) {
                if (getFieldValByName(fieldName, metaObject) == null) {
                    setFieldValByName(fieldName, value, metaObject);
                }
            }
        };
    }

    /**
     * 创建 MyBatis SQL 日志开关定制器。
     *
     * @param sqlLogEnabled 是否输出 SQL 到标准输出
     * @return MyBatis 配置定制器
     */
    @Bean
    public ConfigurationCustomizer mybatisSqlLoggingCustomizer(
            @Value("${wefolio.mybatis.sql-log-enabled:false}") boolean sqlLogEnabled
    ) {
        return configuration -> configuration.setLogImpl(sqlLogEnabled ? StdOutImpl.class : NoLoggingImpl.class);
    }
}
