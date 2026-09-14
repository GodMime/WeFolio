package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.jxc.wefolio.config.MyBatisPlusConfig;
import com.jxc.wefolio.config.PortfolioOpenPerformanceProperties;
import com.jxc.wefolio.dto.PortfolioRenderDto;
import com.jxc.wefolio.dto.VisitorPortfolioOpenRequest;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.ScheduleEntityMapper;
import com.jxc.wefolio.mapper.SlotDefinitionEntityMapper;
import com.jxc.wefolio.mapper.ScheduleQueryRecordEntityMapper;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.VisitActivityMessage;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.ArgumentMatchers.*;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioTypeDict;
import com.jxc.wefolio.dict.FollowStatusDict;
import com.jxc.wefolio.dto.VisitActivityTrackingDto;
import com.jxc.wefolio.entity.BaseEntity;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.entity.VisitActivitySessionEntity;
import com.jxc.wefolio.entity.VisitEventEntity;
import com.jxc.wefolio.entity.VisitorEntity;
import com.jxc.wefolio.mapper.VisitActivitySessionEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioVisitService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.AopTestUtils;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 活动会话真实 Spring 事务及行锁测试；默认 H2，MySQL 子类显式启用隔离库。 */
@SpringJUnitConfig(VisitActivitySessionConcurrencyIntegrationTest.Config.class)
public class VisitActivitySessionConcurrencyIntegrationTest {
    /** 真实事务代理。 */
    @Autowired protected VisitActivitySessionTransactionService service;
    /** SQL 验证入口。 */
    @Autowired protected JdbcTemplate jdbc;
    /** 真实汇总 Mapper，用于证明旧实体乐观锁无法覆盖新时长。 */
    @Autowired protected VisitRecordEntityMapper records;
    /** 真实活动 Mapper。 */
    @Autowired protected VisitActivitySessionEntityMapper sessions;
    /** 积分服务仅替代远端计费边界，打开事务仍使用真实访问服务。 */
    @Autowired protected PointBillingWindowService billing;

    /** 重建默认内存表；MySQL 仅清理已明确声明为专用测试库的数据。 */
    @BeforeEach
    void prepare() {
        reset((PointBillingWindowService) AopTestUtils.getUltimateTargetObject(billing));
        if (Boolean.getBoolean("visit.activity.mysql.enabled")) {
            for (String table : List.of("wf_visit_activity_session", "wf_visit_event", "wf_visit_record", "wf_visitor")) {
                jdbc.execute("DELETE FROM " + table);
            }
        } else {
            createTable("wf_visit_record", VisitRecordEntity.class);
            createTable("wf_visit_event", VisitEventEntity.class);
            createTable("wf_visitor", VisitorEntity.class);
            createTable("wf_visit_activity_session", VisitActivitySessionEntity.class);
            jdbc.execute("CREATE UNIQUE INDEX uk_activity_client ON wf_visit_activity_session(visitor_id,portfolio_type,portfolio_id,client_session_key,deleted)");
            jdbc.execute("CREATE UNIQUE INDEX uk_activity_open ON wf_visit_activity_session(visitor_id,portfolio_type,portfolio_id,open_idempotency_key,deleted)");
            jdbc.execute("CREATE UNIQUE INDEX uk_event_key ON wf_visit_event(idempotency_key,deleted)");
            jdbc.execute("CREATE UNIQUE INDEX uk_record_visitor ON wf_visit_record(visitor_id,portfolio_id,deleted)");
        }
        jdbc.update("INSERT INTO wf_visitor(id,openid,visitor_key) VALUES (7,'test-visitor-openid','test-visitor-key')");
        assertThat(AopUtils.isAopProxy(service)).isTrue();
    }

    /** 45→15→30→重复45秒只累计45秒；首个零单独证明NULL初始化。 */
    @Test
    void cumulativeHighWaterAndFirstZero() {
        var result = open("client", "open");
        assertThat(foreground(result.record().getId())).isNull();
        assertThat(service.accept(8L, PortfolioTypeDict.PERSONAL.getCode(), 7L, result.session().getId(), 0)).isZero();
        assertThat(foreground(result.record().getId())).isZero();
        age(result.session().getId());
        for (long incoming : new long[]{45000,15000,30000,45000}) {
            assertThat(service.accept(8L, PortfolioTypeDict.PERSONAL.getCode(), 7L, result.session().getId(), incoming))
                    .isEqualTo(45000L);
        }
        assertThat(foreground(result.record().getId())).isEqualTo(45000L);
        assertThat(jdbc.queryForObject("SELECT total_duration_seconds FROM wf_visit_record WHERE id=?", Long.class,
                result.record().getId())).isZero();
    }

    /** 同会话并发只取高水位，多会话并发按差量准确求和。 */
    @Test
    void concurrentSessionsAndSameSession() throws Exception {
        var first = open("client-a", "open-a");var second = open("client-b", "open-b");
        age(first.session().getId());age(second.session().getId());
        try (var executor = Executors.newFixedThreadPool(3)) {
            var a = executor.submit(() -> service.accept(8L, PortfolioTypeDict.PERSONAL.getCode(), 7L, first.session().getId(), 45000));
            var b = executor.submit(() -> service.accept(8L, PortfolioTypeDict.PERSONAL.getCode(), 7L, first.session().getId(), 30000));
            var c = executor.submit(() -> service.accept(8L, PortfolioTypeDict.PERSONAL.getCode(), 7L, second.session().getId(), 15000));
            a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);c.get(10,TimeUnit.SECONDS);
        }
        assertThat(foreground(first.record().getId())).isEqualTo(60000L);
    }

    /** 同双键并发首次打开仅创建一次事件和会话，恢复跨两小时也不触发计费。 */
    @Test
    void concurrentOpenAndExpiredWindowRestoreOnlyOpenOnce() throws Exception {
        VisitActivitySessionTransactionService.OpenResult first;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> open("client", "open"));
            var b = executor.submit(() -> open("client", "open"));
            first = a.get(10,TimeUnit.SECONDS);
            assertThat(b.get(10,TimeUnit.SECONDS).session().getId()).isEqualTo(first.session().getId());
        }
        jdbc.update("UPDATE wf_visit_activity_session SET created_at=? WHERE id=?", LocalDateTime.now().minusHours(3), first.session().getId());
        assertThat(open("client", "open").session().getId()).isEqualTo(first.session().getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_visit_event",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_visit_activity_session",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT visit_count FROM wf_visit_record",Long.class)).isEqualTo(1L);
        assertThat(mockingDetails(AopTestUtils.getUltimateTargetObject(billing)).getInvocations()).hasSize(1);
    }

    /** 不同访客同时首次打开不会因不存在会话的间隙锁互相阻塞或回滚。 */
    @Test
    void differentVisitorsCanOpenConcurrently() throws Exception {
        jdbc.update("INSERT INTO wf_visitor(id,openid,visitor_key) VALUES (17,'test-other-openid','test-other-key')");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> open("client-a", "open-a"));
            var second = executor.submit(() -> service.open(portfolio(), PortfolioTypeDict.PERSONAL.getCode(), 17L,
                    "test-other-key", null, "open-b", tracking("client-b")));
            assertThat(first.get(10, TimeUnit.SECONDS).session().getVisitorId()).isEqualTo(7L);
            assertThat(second.get(10, TimeUnit.SECONDS).session().getVisitorId()).isEqualTo(17L);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_visit_activity_session",Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_visit_record",Long.class)).isEqualTo(2L);
        assertThatThrownBy(() -> service.open(portfolio(), PortfolioTypeDict.PERSONAL.getCode(),17L,
                "test-other-key",null,"open-a",tracking("client-foreign"))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_visit_activity_session",Long.class)).isEqualTo(2L);
    }

    /** 数据库本身保护活动键和打开键，不能依赖应用串行化替代唯一约束。 */
    @Test
    void databaseRejectsDuplicateClientAndOpenKeys() {
        var result = open("client", "open");
        VisitActivitySessionEntity duplicate = new VisitActivitySessionEntity();
        BeanUtils.copyProperties(result.session(), duplicate);duplicate.setId(null);
        assertThatThrownBy(() -> sessions.insert(duplicate)).isInstanceOf(DuplicateKeyException.class);
        duplicate.setId(null);duplicate.setClientSessionKey("different-client");
        assertThatThrownBy(() -> sessions.insert(duplicate)).isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_visit_activity_session",Long.class)).isEqualTo(1L);
    }

    /** 汇总更新失败必须回滚已经推进的会话；失败重试仍可累计。 */
    @Test
    void aggregateFailureRollsBackSession() {
        var result = open("client", "open");age(result.session().getId());
        jdbc.update("UPDATE wf_visit_record SET deleted=id WHERE id=?",result.record().getId());
        assertThatThrownBy(() -> service.accept(8L, PortfolioTypeDict.PERSONAL.getCode(), 7L, result.session().getId(), 15000))
                .isInstanceOf(RuntimeException.class);
        assertThat(sessions.selectById(result.session().getId()).getActiveDurationMs()).isZero();
        assertThat(sessions.selectById(result.session().getId()).getLastReportedAt()).isNull();
        jdbc.update("UPDATE wf_visit_record SET deleted=0 WHERE id=?",result.record().getId());
        assertThat(service.accept(8L, PortfolioTypeDict.PERSONAL.getCode(), 7L, result.session().getId(), 15000)).isEqualTo(15000L);
    }

    /** 渲染在提交后执行；失败不回滚旧打开业务，重试复用会话且不重复计费。 */
    @Test
    void renderFailureKeepsCommittedOpenAndRetryReusesSessionOutsideTransaction() {
        PortfolioRenderService render = mock(PortfolioRenderService.class);
        List<PortfolioOpenPerformanceLogger.Report> reports = new ArrayList<>();
        VisitorPortfolioService entryPoint = personalEntryPoint(render, reports);
        AtomicInteger attempts = new AtomicInteger();
        when(render.render(any(), any(), eq(false), eq(false), isNull(), anyLong())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("测试渲染失败");
            }
            return new PortfolioRenderDto();
        });
        VisitorPortfolioOpenRequest request = new VisitorPortfolioOpenRequest();
        request.setLoginCode("test-login-code");request.setIdempotencyKey("open");request.setTracking(tracking("client"));
        assertThatThrownBy(() -> entryPoint.openPortfolio("PF-TEST", request)).isInstanceOf(IllegalStateException.class);
        Long sessionId = jdbc.queryForObject("SELECT id FROM wf_visit_activity_session", Long.class);
        Long recordId = jdbc.queryForObject("SELECT id FROM wf_visit_record", Long.class);
        assertThat(foreground(recordId)).isNull();
        assertThat(sessions.selectById(sessionId).getLastReportedAt()).isNull();
        var recovered = entryPoint.openPortfolio("PF-TEST", request);
        assertThat(recovered.getTrackingSessionId()).isEqualTo(sessionId);
        assertThat(recovered.getTrackingActiveDurationMs()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_visit_event", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT visit_count FROM wf_visit_record", Long.class)).isEqualTo(1L);
        assertThat(mockingDetails(AopTestUtils.getUltimateTargetObject(billing)).getInvocations()).hasSize(1);
        assertThat(reports).hasSize(2).allSatisfy(report -> {
            assertThat(report.visitWriteMs()).isNotNull();
            assertThat(report.renderMs()).isNotNull();
        });
        assertThat(reports.getFirst().outcome()).isEqualTo(PortfolioOpenPerformanceLogger.Outcome.RENDER_FAILED);
        assertThat(reports.getLast().outcome()).isEqualTo(PortfolioOpenPerformanceLogger.Outcome.SUCCESS);
    }

    /** 双键一一绑定，两种不匹配均返回受控错误并保持事件、扣费和会话数量。 */
    @Test
    void mismatchedDoubleKeysFailWithoutNewOpenOrCharge() {
        var initial = open("client", "open");
        assertThatThrownBy(() -> open("another-client", "open"))
                .isInstanceOf(BusinessException.class).hasMessage(VisitActivityMessage.KEY_CONFLICT);
        assertThatThrownBy(() -> open("client", "another-open"))
                .isInstanceOf(BusinessException.class).hasMessage(VisitActivityMessage.KEY_CONFLICT);
        assertThat(open("client", "open").session().getId()).isEqualTo(initial.session().getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_visit_activity_session",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_visit_event",Long.class)).isEqualTo(1L);
        assertThat(mockingDetails(AopTestUtils.getUltimateTargetObject(billing)).getInvocations()).hasSize(1);
    }

    /** 持旧版本实体执行跟进更新会失败，不能把新累计覆盖回旧值。 */
    @Test
    void staleFollowUpdateCannotOverwriteActivity() {
        var result = open("client", "open");age(result.session().getId());
        VisitRecordEntity stale = records.selectById(result.record().getId());
        service.accept(8L, PortfolioTypeDict.PERSONAL.getCode(), 7L, result.session().getId(), 15000);
        stale.setFollowStatus(FollowStatusDict.CONTACTED.getCode());
        assertThat(records.updateById(stale)).isZero();
        assertThat(foreground(stale.getId())).isEqualTo(15000L);
    }

    /** 设备创建时间相同时取较大ID，旧会话补报不覆盖新设备。 */
    @Test
    void latestDeviceUsesCreationAndIdInsteadOfReportTime() {
        var first = open("client-a", "open-a");var second = open("client-b", "open-b");
        LocalDateTime created = LocalDateTime.now().minusMinutes(1);
        jdbc.update("UPDATE wf_visit_activity_session SET created_at=?,model=? WHERE id=?", created,"旧设备",first.session().getId());
        jdbc.update("UPDATE wf_visit_activity_session SET created_at=?,model=? WHERE id=?", created,"新设备",second.session().getId());
        service.accept(8L, PortfolioTypeDict.PERSONAL.getCode(), 7L, first.session().getId(), 15000);
        assertThat(sessions.selectLatestDevice(first.record().getId()).getModel()).isEqualTo("新设备");
    }

    /** 身份或业务类型错误的上报不写入；更换绑定open键也禁止恢复。 */
    @Test
    void rejectsForeignScopeAndMismatchedOpenKey() {
        var result = open("client", "open");
        assertThatThrownBy(() -> service.accept(8L, PortfolioTypeDict.TEAM.getCode(),7L,result.session().getId(),0)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.accept(8L, PortfolioTypeDict.PERSONAL.getCode(),9L,result.session().getId(),0)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> open("client", "different-open")).isInstanceOf(RuntimeException.class);
        assertThat(foreground(result.record().getId())).isNull();
    }

    /** 构造真实活动事务上方的个人打开服务；空内容作品集仍使用已确定的有效访问口径。 */
    private VisitorPortfolioService personalEntryPoint(PortfolioRenderService render,
            List<PortfolioOpenPerformanceLogger.Report> reports) {
        PortfolioEntity portfolio = portfolio();portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        portfolio.setPublishedConfigJson("{\"schemaVersion\":\"standard-personal-v1\",\"components\":[]}");
        PortfolioEntityMapper portfolioMapper = mock(PortfolioEntityMapper.class);
        when(portfolioMapper.selectOne(any())).thenReturn(portfolio);
        VisitorService visitorService = mock(VisitorService.class);
        VisitorEntity visitor = new VisitorEntity();visitor.setId(7L);visitor.setVisitorKey("test-visitor-key");
        when(visitorService.resolveForOpen(any(),any(),any(),any())).thenReturn(new VisitorService.VisitorSession(visitor,false));
        VisitorAuthTokenService tokens = mock(VisitorAuthTokenService.class);
        when(tokens.issueToken(anyLong(), anyString()))
                .thenReturn(new VisitorAuthTokenService.VisitorLoginToken("Bearer","test-token",7200L));
        PortfolioOpenPerformanceProperties properties = new PortfolioOpenPerformanceProperties();properties.setNormalSampleRate(1D);
        PortfolioOpenPerformanceLogger logger = new PortfolioOpenPerformanceLogger(properties,System::nanoTime,() -> 0D,reports::add);
        return new VisitorPortfolioService(portfolioMapper, mock(ScheduleEntityMapper.class),mock(SlotDefinitionEntityMapper.class),
                mock(PortfolioVisitService.class),render,visitorService,tokens,mock(ScheduleQueryRecordEntityMapper.class),
                mock(OwnerSelfVisitService.class),mock(PointBalanceGateService.class),billing,logger,
                new VisitActivitySessionApplicationService(service));
    }

    /** 创建真实个人打开事务。 */
    protected VisitActivitySessionTransactionService.OpenResult open(String client, String open) {
        return service.open(portfolio(), PortfolioTypeDict.PERSONAL.getCode(),7L,"test-visitor-key",null,open,tracking(client));
    }
    /** 构造最小发布作品集。 */
    private PortfolioEntity portfolio() {
        PortfolioEntity portfolio = new PortfolioEntity();portfolio.setId(8L);portfolio.setOwnerId(9L);
        portfolio.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());portfolio.setPublishedRevision(1);
        portfolio.setShareCode("PF-TEST");return portfolio;
    }
    /** 构造已校验活动请求。 */
    private VisitActivityTrackingDto tracking(String key) {
        VisitActivityTrackingDto tracking = new VisitActivityTrackingDto();tracking.setVersion(1);tracking.setClientSessionKey(key);return tracking;
    }
    /** 将测试会话移至一分钟前，为累计验证提供合理服务端时间。 */
    private void age(Long id) { jdbc.update("UPDATE wf_visit_activity_session SET created_at=? WHERE id=?",LocalDateTime.now().minusMinutes(1),id); }
    /** 读取可空汇总。 */
    private Long foreground(Long id) { return jdbc.queryForObject("SELECT foreground_duration_ms FROM wf_visit_record WHERE id=?",Long.class,id); }
    /** 按实体生成仅用于H2的测试表；真实MySQL始终执行Flyway脚本。 */
    private void createTable(String name, Class<?> entity) {
        jdbc.execute("DROP TABLE IF EXISTS " + name);
        List<String> columns = new ArrayList<>();
        for (Class<?> type : List.of(entity,BaseEntity.class)) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                String column = field.getName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
                String definition = field.getType() == Long.class ? "BIGINT" : field.getType() == Integer.class ? "INT" :
                        field.getType() == LocalDateTime.class ? "TIMESTAMP(3)" : field.getType() == LocalDate.class ? "DATE" : "VARCHAR(2048)";
                if (column.equals("id")) definition = "BIGINT AUTO_INCREMENT PRIMARY KEY";
                else if (column.equals("deleted") || column.equals("version")) definition += " DEFAULT 0";
                else if (column.equals("created_at") || column.equals("updated_at")) definition += " DEFAULT CURRENT_TIMESTAMP(3)";
                columns.add(column + " " + definition);
            }
        }
        jdbc.execute("CREATE TABLE " + name + " (" + String.join(",",columns) + ")");
    }

    /** 独立测试上下文，只装配被测服务、真实Mapper和事务管理器。 */
    @TestConfiguration
    @EnableTransactionManagement(proxyTargetClass = true)
    @MapperScan("com.jxc.wefolio.mapper")
    @Import({VisitActivitySessionTransactionService.class,PortfolioVisitService.class,TeamPortfolioVisitService.class})
    public static class Config {
        /** 独立H2默认数据源；MySQL仅接受显式专用库环境变量，不读取runtime .env。 */
        @Bean
        DataSource dataSource() {
            if (!Boolean.getBoolean("visit.activity.mysql.enabled")) {
                return new DriverManagerDataSource("jdbc:h2:mem:visit_activity;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
            }
            String url = System.getenv("VISIT_ACTIVITY_MYSQL_URL");
            String user = System.getenv("VISIT_ACTIVITY_MYSQL_USERNAME");
            String password = System.getenv("VISIT_ACTIVITY_MYSQL_PASSWORD");
            if (!"true".equals(System.getenv("VISIT_ACTIVITY_MYSQL_ISOLATED")) || url == null || user == null || password == null) {
                throw new IllegalStateException("MySQL验证要求显式提供四项 VISIT_ACTIVITY_MYSQL_* 隔离库变量");
            }
            URI uri = URI.create(url.substring("jdbc:".length()));
            if (!"mysql".equals(uri.getScheme()) || !uri.getPath().matches("/wefolio_visit_activity_test_[A-Za-z0-9_]+")) {
                throw new IllegalStateException("MySQL验证只允许 wefolio_visit_activity_test_ 前缀的专用测试库");
            }
            DriverManagerDataSource source = new DriverManagerDataSource(url,user,password);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            if (!jdbc.queryForObject("SELECT VERSION()",String.class).startsWith("8.")) {
                throw new IllegalStateException("本验证必须使用 MySQL 8");
            }
            if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class) != 0) {
                throw new IllegalStateException("MySQL迁移验证要求空的专用测试库，禁止覆盖既有表");
            }
            // 先验证全新库至V56，再插入旧访问，最后执行V57升级并验证历史NULL。
            Flyway.configure().dataSource(source).target("56").load().migrate();
            jdbc.update("""
                INSERT INTO wf_visit_record(visitor_id,visitor_key,portfolio_id,last_portfolio_revision,portfolio_type,
                    owner_type,owner_id,first_visited_at,last_visited_at)
                VALUES (7,'migration-history',8,1,'PERSONAL','USER',9,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))
                """);
            Flyway.configure().dataSource(source).load().migrate();
            if (jdbc.queryForObject("SELECT foreground_duration_ms FROM wf_visit_record WHERE visitor_key='migration-history'",Long.class) != null) {
                throw new IllegalStateException("历史访问必须保留NULL前台时长");
            }
            return source;
        }
        /** SQL验证入口。 */
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        /** 真实事务管理器。 */
        @Bean DataSourceTransactionManager transactionManager(DataSource dataSource) { return new DataSourceTransactionManager(dataSource); }
        /** 按生产命名规则和乐观锁插件创建MyBatis会话工厂。 */
        @Bean SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();factory.setDataSource(dataSource);
            MybatisConfiguration configuration = new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            GlobalConfig global = new GlobalConfig();
            global.setMetaObjectHandler(new MyBatisPlusConfig().baseEntityMetaObjectHandler());
            factory.setGlobalConfig(global);
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            factory.setPlugins(interceptor);return factory.getObject();
        }
        /** 积分业务边界替身，其他数据库写入均真实。 */
        @Bean PointBillingWindowService billing() { return mock(PointBillingWindowService.class); }
    }
}
