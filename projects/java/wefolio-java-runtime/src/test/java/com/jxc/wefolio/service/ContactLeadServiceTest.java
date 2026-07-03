package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dto.ContactLeadSubmitRequest;
import com.jxc.wefolio.dto.ContactLeadSubmitResponse;
import com.jxc.wefolio.entity.ContactLeadEntity;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.mapper.ContactLeadEntityMapper;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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

    /** 访问服务模拟 */
    @Mock
    private PortfolioVisitService portfolioVisitService;

    @Test
    void submitShouldRejectMissingContactNameOrContactMethod() {
        ContactLeadSubmitRequest missingName = request();
        missingName.setContactName("");

        assertThatThrownBy(() -> service().submit(portfolio(), missingName))
                .isInstanceOf(com.jxc.wefolio.exception.BusinessException.class)
                .hasMessage("请填写联系人");

        ContactLeadSubmitRequest missingContact = request();
        missingContact.setPhone("");
        missingContact.setWechat("");

        assertThatThrownBy(() -> service().submit(portfolio(), missingContact))
                .isInstanceOf(com.jxc.wefolio.exception.BusinessException.class)
                .hasMessage("请至少填写手机号或微信号");
    }

    @Test
    void submitShouldStoreMaskedLeadAndRecordSubmitEvent() {
        when(contactLeadEntityMapper.insert(any(ContactLeadEntity.class))).thenAnswer(invocation -> {
            ContactLeadEntity lead = invocation.getArgument(0);
            lead.setId(66L);
            return 1;
        });
        ContactLeadSubmitRequest request = request();

        ContactLeadSubmitResponse response = service().submit(portfolio(), request);

        ArgumentCaptor<ContactLeadEntity> captor = ArgumentCaptor.forClass(ContactLeadEntity.class);
        verify(contactLeadEntityMapper).insert(captor.capture());
        ContactLeadEntity lead = captor.getValue();
        assertThat(response.getLeadId()).isEqualTo(66L);
        assertThat(lead.getContactName()).isEqualTo("林安");
        assertThat(lead.getPortfolioTitleSnapshot()).isEqualTo("林安婚礼司仪");
        assertThat(lead.getPortfolioShareCodeSnapshot()).isEqualTo("PF001");
        assertThat(lead.getPhoneLast4()).isEqualTo("8000");
        assertThat(lead.getPhoneCiphertext()).isNotEqualTo("13800138000");
        assertThat(lead.getWechatMaskHint()).isEqualTo("we***io");
        assertThat(lead.getWechatCiphertext()).isNotEqualTo("wefolio");
        verify(portfolioVisitService).recordContactLeadSubmitted(portfolio(), "visitor-a", 66L, "lead-1");
    }

    private ContactLeadService service() {
        return new ContactLeadService(contactLeadEntityMapper, portfolioEntityMapper, portfolioVisitService);
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
        portfolio.setPublishedRevision(3);
        portfolio.setPublishedConfigJson("""
                {"schemaVersion":"standard-personal-v1","share":{"title":"林安婚礼司仪"},"components":[]}
                """);
        return portfolio;
    }
}
