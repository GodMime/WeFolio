package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.common.auth.VisitorContext;
import com.jxc.wefolio.common.auth.VisitorContextHolder;
import com.jxc.wefolio.dict.AuthTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dto.ContactLeadSubmitRequest;
import com.jxc.wefolio.dto.ContactLeadSubmitResponse;
import com.jxc.wefolio.entity.ContactLeadEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.dto.MaintainerWechatLoginRequest;
import com.jxc.wefolio.dto.WechatPhoneNumberResponse;
import org.springframework.dao.DuplicateKeyException;
import java.util.concurrent.Callable;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.mapper.ContactLeadEntityMapper;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 两个服务实例和独立真实事务验证跨访客内容去重；H2 不替代 MySQL RR 验收。 */
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:contact_content_lock;MODE=MySQL;DB_CLOSE_DELAY=-1")
class ContactLeadConcurrencyIntegrationTest {
    /** 隔离数据库访问。 */
    @Autowired private JdbcTemplate jdbc;
    /** 实际线索 Mapper。 */
    @Autowired private ContactLeadEntityMapper leads;
    /** 实际作品集 Mapper。 */
    @Autowired private PortfolioEntityMapper portfolios;
    /** 事务管理器。 */
    @Autowired private PlatformTransactionManager transactions;
    /** 事件边界只计数，不访问外部服务。 */
    @MockBean private PortfolioVisitService visits;

    /** 使用独立注册事务，失败方必须完整回滚。 */
    @Autowired private UserRegistrationService registration;
    /** 不调用真实注册奖励服务，统计赢家副作用次数。 */
    @MockBean private PointService points;

    /** 首次插入未提交时第二访客必须等待，提交后返回同一条历史记录。 */
    @Test
    void differentVisitorsAndKeysShouldOnlyCreateOneLead() throws Exception {
        createTable(PortfolioEntity.class);
        createTable(ContactLeadEntity.class);
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setShareCode("PF-CONCURRENT");
        portfolio.setOwnerId(7L);
        portfolio.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        portfolios.insert(portfolio);
        ContactLeadService firstService = service();
        ContactLeadService secondService = service();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            inserted.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(visits).recordContactLeadSubmitted(any(), any(), any(), any());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> submit(firstService, 1L, "first-key"));
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch secondStarted = new CountDownLatch(1);
            var second = workers.submit(() -> {
                secondStarted.countDown();
                return submit(secondService, 2L, "different-key");
            });
            assertThat(secondStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(second.isDone()).isFalse();
            release.countDown();
            var firstResult = first.get(5, TimeUnit.SECONDS);
            var secondResult = second.get(5, TimeUnit.SECONDS);
            assertThat(secondResult.getLeadId()).isEqualTo(firstResult.getLeadId());
            assertThat(secondResult.getSubmittedAt()).isEqualTo(firstResult.getSubmittedAt());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_contact_lead", Integer.class)).isEqualTo(1);
            verify(visits, times(1)).recordContactLeadSubmitted(any(), any(), any(), any());
        } finally {
            release.countDown();
            jdbc.execute("DROP ALL OBJECTS");
        }
    }

    /** 并发同 openid 只有一个用户与一次注册奖励；同手机号另一个 openid 正常新建。 */
    @Test
    void concurrentRegistrationRollsBackLosingUserAndDoesNotMergeByPhone() throws Exception {
        createTable(UserEntity.class);
        createTable(UserAuthEntity.class);
        jdbc.execute("CREATE UNIQUE INDEX uk_auth_type_open_id ON wf_user_auth(auth_type,open_id,deleted)");
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> registerFirst = () -> register(start, "WFFIRST", "SameOpenId");
            Callable<Boolean> registerSecond = () -> register(start, "WFSECOND", "SameOpenId");
            var first = workers.submit(registerFirst);
            var second = workers.submit(registerSecond);
            start.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS) ^ second.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_user", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_user_auth", Integer.class)).isEqualTo(1);
            verify(points, times(1)).createGiftOrders(any());
            assertThat(register(start, "WFTHIRD", "OtherOpenId")).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_user", Integer.class)).isEqualTo(2);
            verify(points, times(2)).createGiftOrders(any());
        } finally {
            start.countDown();
            jdbc.execute("DROP ALL OBJECTS");
        }
    }

    /** 保留旧的多身份测试用户，新注册独立创建且不改变旧认证归属。 */
    @Test
    void legacySharedUsersRemainUntouchedWhileNewRegistrationsStayIndependent() throws Exception {
        createTable(UserEntity.class);
        createTable(UserAuthEntity.class);
        try {
            for (long userId : new long[]{8L, 16L}) {
                jdbc.update("INSERT INTO wf_user(id,unique_code,phone_number,status) VALUES(?,?,?,?)",
                        userId, "WFLEGACY" + userId, "13800000000", UserStatusDict.ACTIVE.getCode());
            }
            for (long[] mapping : new long[][]{{8, 8}, {17, 8}, {21, 8}, {52, 8}, {16, 16}, {32, 16}}) {
                jdbc.update("INSERT INTO wf_user_auth(id,user_id,auth_type,open_id,identifier_hash,status) VALUES(?,?,?,?,?,?)",
                        mapping[0], mapping[1], AuthTypeDict.WECHAT_MINI_APP.getCode(),
                        "legacy-openid-" + mapping[0], "legacy-hash-" + mapping[0], UserStatusDict.ACTIVE.getCode());
            }
            // V60 的 openid 约束允许保留旧的 userId 多身份记录。
            jdbc.execute("CREATE UNIQUE INDEX uk_auth_type_open_id ON wf_user_auth(auth_type,open_id,deleted)");
            var original = jdbc.queryForList("SELECT * FROM wf_user_auth ORDER BY id");
            assertThat(register(new CountDownLatch(0), "WFNEWONE", "new-openid-one")).isTrue();
            assertThat(register(new CountDownLatch(0), "WFNEWTWO", "new-openid-two")).isTrue();
            assertThat(jdbc.queryForList("SELECT * FROM wf_user_auth WHERE user_id IN (8,16) ORDER BY id"))
                    .isEqualTo(original);
            assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM wf_user_auth WHERE user_id NOT IN (8,16)", Integer.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_user_auth WHERE user_id NOT IN (8,16)", Integer.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_user WHERE phone_number='13800000000'", Integer.class))
                    .isEqualTo(4);
            verify(points, times(2)).createGiftOrders(any());
        } finally {
            jdbc.execute("DROP ALL OBJECTS");
        }
    }

    /** 所有测试身份使用相同手机号，确保手机号只作为资料。 */
    private boolean register(CountDownLatch start, String uniqueCode, String openid) throws Exception {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        WechatPhoneNumberResponse.PhoneInfo phone = new WechatPhoneNumberResponse.PhoneInfo();
        phone.setPhoneNumber("13800000000");
        try {
            registration.createWechatMaintainerUser(uniqueCode, new MaintainerWechatLoginRequest(),
                    phone, null, "hash-" + openid, openid, null);
            return true;
        } catch (DuplicateKeyException expected) {
            return false;
        }
    }

    /** 每个请求在线程内建立独立访客和事务。 */
    private ContactLeadSubmitResponse submit(ContactLeadService service, long visitorId, String key) {
        VisitorContextHolder.set(new VisitorContext(visitorId, "visitor-" + visitorId, "test"));
        try {
            ContactLeadSubmitRequest request = new ContactLeadSubmitRequest();
            request.setContactName("同一联系人");
            request.setPhone("13800000000");
            request.setIdempotencyKey(key);
            return new TransactionTemplate(transactions).execute(status -> service.submit("PF-CONCURRENT", request));
        } finally {
            VisitorContextHolder.clear();
        }
    }

    /** 两个业务实例使用实际数据库 Mapper，非本用例依赖明确隔离。 */
    private ContactLeadService service() {
        return new ContactLeadService(leads, portfolios, mock(VisitRecordEntityMapper.class), visits,
                mock(VisitorService.class), mock(OwnerSelfVisitService.class), mock(PointBillingWindowService.class));
    }

    /** 为真实 Mapper 建立隔离测试表，保持实体完整列集合。 */
    private void createTable(Class<?> type) {
        TableInfo table = TableInfoHelper.getTableInfo(type);
        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(table.getTableName())
                .append(" (id BIGINT AUTO_INCREMENT PRIMARY KEY");
        table.getFieldList().forEach(field -> {
            Class<?> fieldType = field.getPropertyType();
            String sqlType = fieldType == LocalDateTime.class ? "TIMESTAMP(3)"
                    : Number.class.isAssignableFrom(fieldType) ? "BIGINT" : "VARCHAR(16000)";
            ddl.append(", ").append(field.getColumn()).append(' ').append(sqlType);
            if (field.getColumn().equals("deleted") || field.getColumn().equals("version")) ddl.append(" DEFAULT 0");
        });
        jdbc.execute(ddl.append(')').toString());
    }
}
