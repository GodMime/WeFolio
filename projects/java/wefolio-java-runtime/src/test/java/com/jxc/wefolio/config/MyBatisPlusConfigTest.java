package com.jxc.wefolio.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.jxc.wefolio.entity.FeedbackEntity;
import com.jxc.wefolio.entity.FeedbackUploadTaskEntity;
import com.jxc.wefolio.entity.WorkUploadTaskEntity;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MyBatis Plus 配置测试 — 固定 SQL 日志开关默认关闭，可按配置开启。
 */
class MyBatisPlusConfigTest {

    /** 修改 JVM 默认时区时使用的并行测试资源锁。 */
    private static final String DEFAULT_TIME_ZONE_RESOURCE = "java.util.TimeZone.default";

    /** 问题反馈使用的业务时区。 */
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void sqlLoggingCustomizerDisablesSqlStdoutByDefault() {
        MyBatisPlusConfig config = new MyBatisPlusConfig();
        MybatisConfiguration mybatisConfiguration = new MybatisConfiguration();

        config.mybatisSqlLoggingCustomizer(false).customize(mybatisConfiguration);

        assertThat(mybatisConfiguration.getLogImpl()).isEqualTo(NoLoggingImpl.class);
    }

    @Test
    void sqlLoggingCustomizerCanEnableStdoutSqlLogs() {
        MyBatisPlusConfig config = new MyBatisPlusConfig();
        MybatisConfiguration mybatisConfiguration = new MybatisConfiguration();

        config.mybatisSqlLoggingCustomizer(true).customize(mybatisConfiguration);

        assertThat(mybatisConfiguration.getLogImpl()).isEqualTo(StdOutImpl.class);
    }

    @Test
    void baseEntityMetaObjectHandlerShouldFillInsertTimestampsWhenEmpty() throws Exception {
        MetaObjectHandler handler = findMetaObjectHandlerBean();
        WorkUploadTaskEntity task = new WorkUploadTaskEntity();
        MetaObject metaObject = SystemMetaObject.forObject(task);

        handler.insertFill(metaObject);

        assertThat(task.getCreatedAt()).isNotNull();
        assertThat(task.getUpdatedAt()).isNotNull();
    }

    @Test
    void baseEntityMetaObjectHandlerShouldKeepExplicitInsertTimestamps() throws Exception {
        MetaObjectHandler handler = findMetaObjectHandlerBean();
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 1, 2, 3, 4, 6);
        WorkUploadTaskEntity task = new WorkUploadTaskEntity();
        task.setCreatedAt(createdAt);
        task.setUpdatedAt(updatedAt);
        MetaObject metaObject = SystemMetaObject.forObject(task);

        handler.insertFill(metaObject);

        assertThat(task.getCreatedAt()).isEqualTo(createdAt);
        assertThat(task.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void baseEntityMetaObjectHandlerShouldFillUpdateTimestampWhenEmpty() throws Exception {
        MetaObjectHandler handler = findMetaObjectHandlerBean();
        WorkUploadTaskEntity task = new WorkUploadTaskEntity();
        MetaObject metaObject = SystemMetaObject.forObject(task);

        handler.updateFill(metaObject);

        assertThat(task.getCreatedAt()).isNull();
        assertThat(task.getUpdatedAt()).isNotNull();
    }

    @Test
    void baseEntityMetaObjectHandlerShouldRefreshExplicitUpdateTimestamp() throws Exception {
        MetaObjectHandler handler = findMetaObjectHandlerBean();
        LocalDateTime staleUpdatedAt = LocalDateTime.of(2026, 1, 2, 3, 4, 6);
        WorkUploadTaskEntity task = new WorkUploadTaskEntity();
        task.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        task.setUpdatedAt(staleUpdatedAt);
        MetaObject metaObject = SystemMetaObject.forObject(task);

        handler.updateFill(metaObject);

        assertThat(task.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        assertThat(task.getUpdatedAt()).isAfter(staleUpdatedAt);
    }

    /** 既有实体的基础时间填充继续跟随 JVM 默认时区。 */
    @Test
    @ResourceLock(DEFAULT_TIME_ZONE_RESOURCE)
    void baseEntityMetaObjectHandlerShouldKeepDefaultZoneForExistingEntities() throws Exception {
        TimeZone originalTimeZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            MetaObjectHandler handler = findMetaObjectHandlerBean();
            WorkUploadTaskEntity task = new WorkUploadTaskEntity();
            Instant before = Instant.now();

            handler.insertFill(SystemMetaObject.forObject(task));

            Instant after = Instant.now();
            assertThat(task.getCreatedAt())
                    .isBetween(LocalDateTime.ofInstant(before, ZoneId.of("UTC")),
                            LocalDateTime.ofInstant(after, ZoneId.of("UTC")));
            assertThat(task.getUpdatedAt()).isEqualTo(task.getCreatedAt());
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    /** 问题反馈审计时间固定使用上海业务时区。 */
    @Test
    @ResourceLock(DEFAULT_TIME_ZONE_RESOURCE)
    void baseEntityMetaObjectHandlerShouldUseShanghaiZoneForFeedbackEntities() throws Exception {
        TimeZone originalTimeZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            MetaObjectHandler handler = findMetaObjectHandlerBean();
            FeedbackEntity feedback = new FeedbackEntity();
            FeedbackUploadTaskEntity uploadTask = new FeedbackUploadTaskEntity();
            Instant before = Instant.now();

            handler.insertFill(SystemMetaObject.forObject(feedback));
            handler.insertFill(SystemMetaObject.forObject(uploadTask));
            handler.updateFill(SystemMetaObject.forObject(feedback));

            Instant after = Instant.now();
            assertThat(feedback.getCreatedAt())
                    .isBetween(LocalDateTime.ofInstant(before, SHANGHAI_ZONE),
                            LocalDateTime.ofInstant(after, SHANGHAI_ZONE));
            assertThat(feedback.getUpdatedAt())
                    .isBetween(feedback.getCreatedAt(), LocalDateTime.ofInstant(after, SHANGHAI_ZONE));
            assertThat(uploadTask.getCreatedAt())
                    .isBetween(LocalDateTime.ofInstant(before, SHANGHAI_ZONE),
                            LocalDateTime.ofInstant(after, SHANGHAI_ZONE));
            assertThat(uploadTask.getUpdatedAt()).isEqualTo(uploadTask.getCreatedAt());
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    /**
     * 查找基础实体自动填充处理器 Bean。
     *
     * @return MyBatis-Plus 元对象填充处理器
     * @throws Exception 反射调用失败时抛出
     */
    private MetaObjectHandler findMetaObjectHandlerBean() throws Exception {
        Method method = Arrays.stream(MyBatisPlusConfig.class.getDeclaredMethods())
                .filter(candidate -> MetaObjectHandler.class.isAssignableFrom(candidate.getReturnType()))
                .findFirst()
                .orElseThrow();
        method.setAccessible(true);
        return (MetaObjectHandler) method.invoke(new MyBatisPlusConfig());
    }
}
