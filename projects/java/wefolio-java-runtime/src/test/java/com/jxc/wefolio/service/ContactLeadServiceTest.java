package com.jxc.wefolio.service;

import com.jxc.wefolio.common.auth.VisitorContext;
import com.jxc.wefolio.common.auth.VisitorContextHolder;
import com.jxc.wefolio.dict.BillingWindowScopeDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioTypeDict;
import com.jxc.wefolio.dict.VisitEventTypeDict;
import com.jxc.wefolio.dto.ContactLeadSubmitRequest;
import com.jxc.wefolio.dto.ContactLeadSubmitResponse;
import com.jxc.wefolio.entity.ContactLeadEntity;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.entity.VisitorEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.ContactLeadEntityMapper;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 联系线索服务测试 — 覆盖访客预留联系信息校验和敏感字段存储。
 */
@ExtendWith(MockitoExtension.class)
class ContactLeadServiceTest {

    /** 联系线索 Mapper 模拟 */
    @Mock
    private ContactLeadEntityMapper contactLeadEntityMapper;

    /** 作品集 Mapper 模拟 */
    @Mock
    private PortfolioEntityMapper portfolioEntityMapper;

    /** 访问汇总 Mapper 模拟 */
    @Mock
    private VisitRecordEntityMapper visitRecordEntityMapper;

    /** 访问服务模拟 */
    @Mock
    private PortfolioVisitService portfolioVisitService;

    /** 访客身份服务模拟 */
    @Mock
    private VisitorService visitorService;

    /** 维护者本人访问识别服务模拟 */
    @Mock
    private OwnerSelfVisitService ownerSelfVisitService;

    /** 访客积分滚动窗口服务模拟 */
    @Mock
    private PointBillingWindowService pointBillingWindowService;

    @BeforeEach
    void setUp() {
        VisitorContextHolder.set(new VisitorContext(1024L, "visitor-a", "Bearer wf-visitor-v1.test"));
        VisitorEntity ordinaryVisitor = new VisitorEntity();
        ordinaryVisitor.setId(1024L);
        ordinaryVisitor.setOpenid("openid-visitor");
        lenient().when(visitorService.findById(1024L)).thenReturn(ordinaryVisitor);
        lenient().when(ownerSelfVisitService.isOwnerSelfVisitor(
                eq(7L), eq("openid-visitor"), eq(88L), eq(1024L), any()))
                .thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        VisitorContextHolder.clear();
    }

    @Test
    void submitShouldRejectMissingContactNameOrContactMethod() {
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio());
        ContactLeadSubmitRequest missingName = request();
        missingName.setContactName("");

        assertThatThrownBy(() -> service().submit("PF001", missingName))
                .isInstanceOf(com.jxc.wefolio.exception.BusinessException.class)
                .hasMessage("请填写联系人");

        ContactLeadSubmitRequest missingContact = request();
        missingContact.setPhone("");
        missingContact.setWechat("");

        assertThatThrownBy(() -> service().submit("PF001", missingContact))
                .isInstanceOf(com.jxc.wefolio.exception.BusinessException.class)
                .hasMessage("请至少填写手机号或微信号");
    }

    /** 旧版留资入口不得接受团队作品集，避免向团队密文字段写入明文。 */
    @Test
    void submitShouldRejectPublishedTeamPortfolio() {
        PortfolioEntity teamPortfolio = portfolio();
        teamPortfolio.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        teamPortfolio.setOwnerId(100L);
        when(portfolioEntityMapper.selectOne(any())).thenReturn(teamPortfolio);

        assertThatThrownBy(() -> service().submit("TPF001", request()))
                .isInstanceOf(com.jxc.wefolio.exception.BusinessException.class)
                .hasMessage("作品集暂不可访问");

        verify(contactLeadEntityMapper, never()).insert(any(ContactLeadEntity.class));
    }

    @Test
    void submitShouldStoreMaskedLeadAndRecordSubmitEvent() {
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio());
        when(contactLeadEntityMapper.insert(any(ContactLeadEntity.class))).thenAnswer(invocation -> {
            ContactLeadEntity lead = invocation.getArgument(0);
            lead.setId(66L);
            return 1;
        });
        ContactLeadSubmitRequest request = request();

        ContactLeadSubmitResponse response = service().submit("PF001", request);

        ArgumentCaptor<ContactLeadEntity> captor = ArgumentCaptor.forClass(ContactLeadEntity.class);
        verify(contactLeadEntityMapper).insert(captor.capture());
        ContactLeadEntity lead = captor.getValue();
        assertThat(response.getLeadId()).isEqualTo(66L);
        assertThat(lead.getContactName()).isEqualTo("林安");
        assertThat(lead.getPortfolioTitleSnapshot()).isEqualTo("林安婚礼司仪");
        assertThat(lead.getPortfolioShareCodeSnapshot()).isEqualTo("PF001");
        assertThat(lead.getPhoneLast4()).isEqualTo("8000");
        assertThat(lead.getPhoneCiphertext()).isEqualTo("13800138000");
        assertThat(lead.getWechatMaskHint()).isEqualTo("we***io");
        assertThat(lead.getWechatCiphertext()).isEqualTo("wefolio");
        verify(portfolioVisitService).recordContactLeadSubmitted(portfolio(), "visitor-a", 66L, "lead-1");
        verifyNoInteractions(pointBillingWindowService);
    }

    @Test
    void submitShouldIgnoreRequestVisitorKeyAndUseVisitorContext() {
        VisitorContextHolder.set(new VisitorContext(2048L, "server-key", "Bearer wf-visitor-v1.server"));
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio());
        when(contactLeadEntityMapper.insert(any(ContactLeadEntity.class))).thenAnswer(invocation -> {
            ContactLeadEntity lead = invocation.getArgument(0);
            lead.setId(66L);
            return 1;
        });
        ContactLeadSubmitRequest request = request();
        request.setVisitorKey("attacker-key");

        service().submit("PF001", request);

        verify(portfolioVisitService).recordContactLeadSubmitted(portfolio(), "server-key", 66L, "lead-1");
    }


    @Test
    void submitShouldReturnExistingLeadWhenIdempotencyKeyAlreadyInserted() {
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio());
        ContactLeadEntity existingLead = new ContactLeadEntity();
        existingLead.setId(77L);
        existingLead.setPortfolioId(88L);
        existingLead.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
        existingLead.setOwnerId(7L);
        existingLead.setIdempotencyKey("lead-1");
        existingLead.setSubmittedAt(LocalDateTime.of(2026, 7, 18, 12, 30));
        when(contactLeadEntityMapper.insert(any(ContactLeadEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(contactLeadEntityMapper.selectOne(any())).thenReturn(existingLead);

        ContactLeadSubmitResponse response = service().submit("PF001", request());

        assertThat(response.getLeadId()).isEqualTo(77L);
        assertThat(response.getSubmittedAt()).isEqualTo(existingLead.getSubmittedAt());
        verify(contactLeadEntityMapper).selectOne(any());
        verify(portfolioVisitService, never()).recordContactLeadSubmitted(any(), any(), any(), any());
    }

    @Test
    void submitV2ShouldValidateVisitIdentityAndUseTrustedSourceType() {
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolioWithContactForm());
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(visitRecord());
        when(contactLeadEntityMapper.insert(any(ContactLeadEntity.class))).thenAnswer(invocation -> {
            ContactLeadEntity lead = invocation.getArgument(0);
            lead.setId(66L);
            return 1;
        });
        ContactLeadSubmitRequest request = request();
        request.setSourceType("QR_CODE");

        ContactLeadSubmitResponse response = service().submitV2("PF001", request);

        ArgumentCaptor<ContactLeadEntity> captor = ArgumentCaptor.forClass(ContactLeadEntity.class);
        verify(contactLeadEntityMapper).insert(captor.capture());
        assertThat(response.getLeadId()).isEqualTo(66L);
        assertThat(captor.getValue().getSourceType()).isEqualTo("WECHAT_SHARE_CARD");
        assertThat(captor.getValue().getVisitRecordId()).isEqualTo(33L);
        verify(contactLeadEntityMapper).selectCount(any());
    }

    /** v2 新建线索必须以线索 ID 进入独立作品集窗口。 */
    @Test
    void submitV2ShouldBillNewLeadInPortfolioWindow() {
        PortfolioEntity portfolio = portfolioWithContactForm();
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(visitRecord());
        when(contactLeadEntityMapper.insert(any(ContactLeadEntity.class)))
                .thenAnswer(invocation -> {
                    ContactLeadEntity lead = invocation.getArgument(0);
                    lead.setId(66L);
                    return 1;
                });

        ContactLeadSubmitResponse response = service().submitV2("PF001", request());

        assertThat(response.getLeadId()).isEqualTo(66L);
        InOrder writes = inOrder(
                contactLeadEntityMapper,
                portfolioVisitService,
                pointBillingWindowService);
        writes.verify(contactLeadEntityMapper).insert(any(ContactLeadEntity.class));
        writes.verify(portfolioVisitService).recordContactLeadSubmitted(
                eq(portfolio), eq("visitor-a"), eq(66L), eq("lead-1"));
        writes.verify(pointBillingWindowService).consumeIfEligible(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(pointBillingWindowService).consumeIfEligible(
                7L, 1024L,
                PointSceneCodeDict.SUBMIT_CONTACT_LEAD.getCode(),
                BillingWindowScopeDict.PORTFOLIO.getCode(), 88L,
                "PORTFOLIO_CONTACT_LEAD", "66", "PF_CONTACT_LEAD:66",
                "访客预留联系信息");
    }

    @Test
    void submitV2ShouldFindContactFormInSecondaryMenu() {
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolioWithSecondaryContactForm());
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(visitRecord());
        when(contactLeadEntityMapper.insert(any(ContactLeadEntity.class))).thenAnswer(invocation -> {
            ContactLeadEntity lead = invocation.getArgument(0);
            lead.setId(66L);
            return 1;
        });

        ContactLeadSubmitResponse response = service().submitV2("PF001", request());

        assertThat(response.getLeadId()).isEqualTo(66L);
        verify(contactLeadEntityMapper).insert(any(ContactLeadEntity.class));
    }

    @Test
    void submitV2ShouldRejectVisitRecordFromAnotherVisitor() {
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolioWithContactForm());
        VisitRecordEntity record = visitRecord();
        record.setVisitorKey("another-visitor");
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(record);

        assertThatThrownBy(() -> service().submitV2("PF001", request()))
                .isInstanceOf(com.jxc.wefolio.exception.BusinessException.class)
                .hasMessage("访问记录无效");

        verify(contactLeadEntityMapper, never()).insert(any(ContactLeadEntity.class));
    }

    @Test
    void submitV2ShouldReturnIdempotentResultBeforeCheckingSubmissionLimit() {
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolioWithContactForm());
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(visitRecord());
        ContactLeadEntity existingLead = new ContactLeadEntity();
        existingLead.setId(77L);
        existingLead.setSubmittedAt(LocalDateTime.of(2026, 7, 18, 12, 30));
        when(contactLeadEntityMapper.selectOne(any())).thenReturn(existingLead);

        ContactLeadSubmitResponse response = service().submitV2("PF001", request());

        assertThat(response.getLeadId()).isEqualTo(77L);
        verify(contactLeadEntityMapper, never()).selectCount(any());
        verify(contactLeadEntityMapper, never()).insert(any(ContactLeadEntity.class));
        verifyNoInteractions(pointBillingWindowService);
    }

    /** v2 并发重复键败者返回既有线索，不再计费。 */
    @Test
    void submitV2ShouldNotBillDuplicateKeyRecovery() {
        when(portfolioEntityMapper.selectOne(any()))
                .thenReturn(portfolioWithContactForm());
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(visitRecord());
        ContactLeadEntity existing = new ContactLeadEntity();
        existing.setId(77L);
        existing.setSubmittedAt(LocalDateTime.of(2026, 8, 13, 10, 0));
        when(contactLeadEntityMapper.insert(any(ContactLeadEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(contactLeadEntityMapper.selectOne(any()))
                .thenReturn(null, existing);

        ContactLeadSubmitResponse response = service().submitV2("PF001", request());

        assertThat(response.getLeadId()).isEqualTo(77L);
        verify(portfolioVisitService, never())
                .recordContactLeadSubmitted(any(), any(), any(), any());
        verifyNoInteractions(pointBillingWindowService);
    }

    /** 正常自访无访问记录，在规范化阶段拒绝且零写入。 */
    @Test
    void ownerSelfWithoutVisitRecordShouldFailBeforeIdentityLookupOrWrites() {
        when(portfolioEntityMapper.selectOne(any()))
                .thenReturn(portfolioWithContactForm());
        ContactLeadSubmitRequest request = request();
        request.setVisitRecordId(null);

        assertThatThrownBy(() -> service().submitV2("PF001", request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("访问记录无效");

        verifyNoInteractions(visitorService, ownerSelfVisitService,
                visitRecordEntityMapper, contactLeadEntityMapper,
                portfolioVisitService, pointBillingWindowService);
    }

    /** 自访异常携带正数访问记录时仍在行锁和写入前拒绝。 */
    @Test
    void ownerSelfWithPositiveVisitRecordShouldFailBeforeVisitLockOrWrites() {
        when(portfolioEntityMapper.selectOne(any()))
                .thenReturn(portfolioWithContactForm());
        VisitorEntity visitor = new VisitorEntity();
        visitor.setId(1024L);
        visitor.setOpenid("openid-owner");
        when(visitorService.findById(1024L)).thenReturn(visitor);
        when(ownerSelfVisitService.isOwnerSelfVisitor(
                7L, "openid-owner", 88L, 1024L,
                VisitEventTypeDict.CONTACT_LEAD_SUBMITTED.getCode())).thenReturn(true);

        assertThatThrownBy(() -> service().submitV2("PF001", request()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("访问记录无效");

        verifyNoInteractions(visitRecordEntityMapper, contactLeadEntityMapper,
                portfolioVisitService, pointBillingWindowService);
    }

    /** 线索写入后计费故障必须向上抛出以触发整体回滚。 */
    @Test
    void submitV2ShouldPropagateBillingFailure() {
        when(portfolioEntityMapper.selectOne(any()))
                .thenReturn(portfolioWithContactForm());
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(visitRecord());
        when(contactLeadEntityMapper.insert(any(ContactLeadEntity.class)))
                .thenAnswer(invocation -> {
                    ContactLeadEntity lead = invocation.getArgument(0);
                    lead.setId(66L);
                    return 1;
                });
        when(pointBillingWindowService.consumeIfEligible(
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException("积分计费窗口异常"));

        assertThatThrownBy(() -> service().submitV2("PF001", request()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("积分计费窗口异常");
    }

    /** v2 留资编排必须对所有异常回滚。 */
    @Test
    void submitV2ShouldUseRollbackTransaction() throws NoSuchMethodException {
        Method method = ContactLeadService.class.getMethod(
                "submitV2", String.class, ContactLeadSubmitRequest.class);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).contains(Exception.class);
    }

    @Test
    void submitV2ShouldLimitOrdinaryVisitorToThreeSubmissions() {
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolioWithContactForm());
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(visitRecord());
        when(contactLeadEntityMapper.selectCount(any())).thenReturn(3L);

        assertThatThrownBy(() -> service().submitV2("PF001", request()))
                .isInstanceOf(com.jxc.wefolio.exception.BusinessException.class)
                .hasMessage("同一访客对同一作品集最多可提交3次联系方式");

        verify(contactLeadEntityMapper, never()).insert(any(ContactLeadEntity.class));
    }

    @Test
    void submitV2ShouldSkipSubmissionLimitForTimelineAnonymousVisitor() {
        VisitorContextHolder.set(new VisitorContext(
                1024L,
                "visitor-a",
                "Bearer wf-visitor-timeline-v1.test",
                true
        ));
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolioWithContactForm());
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(visitRecord());
        when(contactLeadEntityMapper.insert(any(ContactLeadEntity.class))).thenAnswer(invocation -> {
            ContactLeadEntity lead = invocation.getArgument(0);
            lead.setId(66L);
            return 1;
        });

        ContactLeadSubmitResponse response = service().submitV2("PF001", request());

        assertThat(response.getLeadId()).isEqualTo(66L);
        verify(contactLeadEntityMapper, never()).selectCount(any());
    }

    @Test
    void submitShouldRejectFieldsLongerThanDatabaseColumns() {
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio());
        ContactLeadSubmitRequest tooLong = request();
        tooLong.setContactName("名".repeat(51));

        assertThatThrownBy(() -> service().submit("PF001", tooLong))
                .isInstanceOf(com.jxc.wefolio.exception.BusinessException.class)
                .hasMessage("预留联系信息字段长度不合法");

        verify(contactLeadEntityMapper, never()).insert(any(ContactLeadEntity.class));
    }

    @Test
    void submitV2ShouldRequireEnabledPublishedContactForm() {
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio());

        assertThatThrownBy(() -> service().submitV2("PF001", request()))
                .isInstanceOf(com.jxc.wefolio.exception.BusinessException.class)
                .hasMessage("联系表单组件不存在");

        verify(visitRecordEntityMapper, never()).selectOne(any());
    }

    private ContactLeadService service() {
        return new ContactLeadService(
                contactLeadEntityMapper,
                portfolioEntityMapper,
                visitRecordEntityMapper,
                portfolioVisitService,
                visitorService,
                ownerSelfVisitService,
                pointBillingWindowService
        );
    }

    private ContactLeadSubmitRequest request() {
        ContactLeadSubmitRequest request = new ContactLeadSubmitRequest();
        request.setVisitorKey("visitor-a");
        request.setVisitRecordId(33L);
        request.setContactName("林安");
        request.setPhone("13800138000");
        request.setWechat("wefolio");
        request.setDesiredSchedule("2026-07-18 午宴");
        request.setNeeds("想了解主持报价");
        request.setSourceType("WECHAT_SHARE_CARD");
        request.setConsentVersion("v1");
        request.setIdempotencyKey("lead-1");
        return request;
    }

    private PortfolioEntity portfolio() {
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setId(88L);
        portfolio.setShareCode("PF001");
        portfolio.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
        portfolio.setOwnerId(7L);
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        portfolio.setPublishedRevision(3);
        portfolio.setPublishedConfigJson("""
                {"schemaVersion":"standard-personal-v1","share":{"title":"林安婚礼司仪"},"components":[]}
                """);
        return portfolio;
    }

    private PortfolioEntity portfolioWithContactForm() {
        PortfolioEntity portfolio = portfolio();
        portfolio.setPublishedConfigJson("""
                {"schemaVersion":"standard-personal-v1","share":{"title":"林安婚礼司仪"},"components":[
                  {"componentKey":"contact-1","componentType":"CONTACT_FORM","enabled":true,"config":{}}
                ]}
                """);
        return portfolio;
    }

    private PortfolioEntity portfolioWithSecondaryContactForm() {
        PortfolioEntity portfolio = portfolio();
        portfolio.setPublishedConfigJson("""
                {
                  "schemaVersion":"standard-personal-v1",
                  "share":{"title":"林安婚礼司仪"},
                  "components":[],
                  "bottomNav":{
                    "enabled":true,
                    "items":[
                      {"key":"home","title":"主页"},
                      {"key":"contact","title":"联系","components":[
                        {"componentKey":"contact-secondary","componentType":"CONTACT_FORM",
                         "sortOrder":1000,"enabled":true,"config":{}}
                      ]}
                    ]
                  }
                }
                """);
        return portfolio;
    }

    private VisitRecordEntity visitRecord() {
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(33L);
        record.setVisitorId(1024L);
        record.setVisitorKey("visitor-a");
        record.setPortfolioId(88L);
        record.setPortfolioType(PortfolioTypeDict.PERSONAL.getCode());
        record.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
        record.setOwnerId(7L);
        record.setSourceType("WECHAT_SHARE_CARD");
        return record;
    }
}
