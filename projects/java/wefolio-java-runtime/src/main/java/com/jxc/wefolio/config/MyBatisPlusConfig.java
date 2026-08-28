package com.jxc.wefolio.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.jxc.wefolio.entity.FeedbackEntity;
import com.jxc.wefolio.entity.FeedbackUploadTaskEntity;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.ZoneId;


/**
 * MyBatis Plus 配置
 *
 * <p>注册插件：</p>
 * <ul>
 *   <li>{@link OptimisticLockerInnerInterceptor} — 乐观锁，更新时自动 version+1</li>
 *   <li>{@link PaginationInnerInterceptor} — 分页，自动拦截分页查询</li>
 * </ul>
 *
 * <p>Mapper 扫描路径：com.jxc.wefolio.mapper</p>
 */
@Configuration
@MapperScan("com.jxc.wefolio.mapper")
public class MyBatisPlusConfig {

    /** BaseEntity 创建时间字段名 */
    private static final String FIELD_CREATED_AT = "createdAt";

    /** BaseEntity 更新时间字段名 */
    private static final String FIELD_UPDATED_AT = "updatedAt";

    /** 问题反馈使用的业务时区 */
    private static final ZoneId FEEDBACK_BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * MyBatis Plus 核心拦截器
     *
     * <p>乐观锁拦截器需在分页拦截器之前注册</p>
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 乐观锁：基于 @Version 字段，UPDATE 时自动 WHERE version=? AND SET version=version+1
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        // 分页：自动识别分页参数，生成数据库方言对应的分页 SQL
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * 基础实体时间字段自动填充处理器。
     *
     * <p>新增时字段已有显式值则保留原值；更新时始终刷新更新时间。</p>
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
                LocalDateTime now = currentTime(metaObject);
                fillWhenEmpty(metaObject, FIELD_CREATED_AT, now);
                fillWhenEmpty(metaObject, FIELD_UPDATED_AT, now);
            }

            /**
             * 更新实体时填充更新时间。
             *
             * @param metaObject MyBatis 元对象
             */
            @Override
            public void updateFill(MetaObject metaObject) {
                setFieldValByName(FIELD_UPDATED_AT, currentTime(metaObject), metaObject);
            }

            /**
             * 反馈实体使用固定业务时区，其余既有实体保持 JVM 默认时区语义。
             *
             * @param metaObject MyBatis 元对象
             * @return 当前本地时间
             */
            private LocalDateTime currentTime(MetaObject metaObject) {
                Object entity = metaObject.getOriginalObject();
                if (entity instanceof FeedbackEntity || entity instanceof FeedbackUploadTaskEntity) {
                    return LocalDateTime.now(FEEDBACK_BUSINESS_ZONE);
                }
                return LocalDateTime.now();
            }

            /**
             * 字段为空时写入默认时间，保留新增时调用方显式传入的值。
             *
             * @param metaObject MyBatis 元对象
             * @param fieldName 字段名
             * @param value 默认时间
             */
            private void fillWhenEmpty(MetaObject metaObject, String fieldName, LocalDateTime value) {
                if (getFieldValByName(fieldName, metaObject) == null) {
                    setFieldValByName(fieldName, value, metaObject);
                }
            }
        };
    }

    /**
     * SQL 日志开关。
     *
     * <p>默认关闭，避免生产环境把 SQL 参数打到标准输出。排查本地问题时可通过
     * {@code wefolio.mybatis.sql-log-enabled=true} 临时开启。</p>
     *
     * @param sqlLogEnabled 是否开启 SQL 标准输出日志
     * @return MyBatis 配置定制器
     */
    @Bean
    public ConfigurationCustomizer mybatisSqlLoggingCustomizer(
            @Value("${wefolio.mybatis.sql-log-enabled:false}") boolean sqlLogEnabled
    ) {
        return configuration -> configuration.setLogImpl(sqlLogEnabled ? StdOutImpl.class : NoLoggingImpl.class);
    }
}
