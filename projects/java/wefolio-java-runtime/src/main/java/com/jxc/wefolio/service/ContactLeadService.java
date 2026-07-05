package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.FollowStatusDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.VisitSourceTypeDict;
import com.jxc.wefolio.dto.ContactLeadSubmitRequest;
import com.jxc.wefolio.dto.ContactLeadSubmitResponse;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.entity.ContactLeadEntity;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.ContactLeadEntityMapper;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.message.PortfolioMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 联系线索服务 — 负责访客预留联系信息校验、明文存储和事件记录。
 */
@Service
@RequiredArgsConstructor
public class ContactLeadService {

    /** 默认隐私说明版本 */
    private static final String DEFAULT_CONSENT_VERSION = "v1";

    /** 作品集标题快照兜底 */
    private static final String DEFAULT_PORTFOLIO_TITLE_SNAPSHOT = "个人作品集";

    /** 分享编码快照兜底 */
    private static final String DEFAULT_PORTFOLIO_SHARE_CODE_SNAPSHOT = "";

    /** 联系线索 Mapper */
    private final ContactLeadEntityMapper contactLeadEntityMapper;

    /** 作品集 Mapper */
    private final PortfolioEntityMapper portfolioEntityMapper;

    /** 访问服务 */
    private final PortfolioVisitService portfolioVisitService;

    /**
     * 按分享编码提交线索。
     *
     * @param shareCode 分享编码
     * @param request 提交请求
     * @return 提交响应
     */
    @Transactional(rollbackFor = Exception.class)
    public ContactLeadSubmitResponse submit(String shareCode, ContactLeadSubmitRequest request) {
        PortfolioEntity portfolio = portfolioEntityMapper.selectOne(
                Wrappers.lambdaQuery(PortfolioEntity.class)
                        .eq(PortfolioEntity::getShareCode, shareCode)
                        .last("LIMIT 1")
        );
        if (portfolio == null || !PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_UNAVAILABLE_MESSAGE);
        }
        return submitInternal(portfolio, request);
    }

    /**
     * 提交线索。
     *
     * @param portfolio 作品集
     * @param request 提交请求
     * @return 提交响应
     */
    private ContactLeadSubmitResponse submitInternal(PortfolioEntity portfolio, ContactLeadSubmitRequest request) {
        if (request == null || !hasText(request.getContactName())) {
            throw new BusinessException(PortfolioMessage.CONTACT_NAME_REQUIRED_MESSAGE);
        }
        if (!hasText(request.getPhone()) && !hasText(request.getWechat())) {
            throw new BusinessException(PortfolioMessage.CONTACT_METHOD_REQUIRED_MESSAGE);
        }
        LocalDateTime now = LocalDateTime.now();
        ContactLeadEntity lead = new ContactLeadEntity();
        lead.setPortfolioId(portfolio.getId());
        lead.setPortfolioTitleSnapshot(resolvePortfolioTitleSnapshot(portfolio));
        lead.setPortfolioShareCodeSnapshot(resolvePortfolioShareCodeSnapshot(portfolio));
        lead.setPortfolioRevision(portfolio.getPublishedRevision());
        lead.setVisitRecordId(request.getVisitRecordId());
        lead.setOwnerType(portfolio.getOwnerType());
        lead.setOwnerId(portfolio.getOwnerId());
        lead.setContactName(request.getContactName().strip());
        lead.setPhoneCiphertext(hasText(request.getPhone()) ? request.getPhone().strip() : null);
        lead.setPhoneLast4(last4(request.getPhone()));
        lead.setWechatCiphertext(hasText(request.getWechat()) ? request.getWechat().strip() : null);
        lead.setWechatMaskHint(maskWechat(request.getWechat()));
        lead.setDesiredSchedule(defaultString(request.getDesiredSchedule()));
        lead.setNeeds(defaultString(request.getNeeds()));
        lead.setSourceType(VisitSourceTypeDict.fromCode(request.getSourceType()) == null
                ? VisitSourceTypeDict.UNKNOWN.getCode()
                : request.getSourceType());
        lead.setConsentVersion(hasText(request.getConsentVersion()) ? request.getConsentVersion().strip() : DEFAULT_CONSENT_VERSION);
        lead.setConsentAt(now);
        lead.setFollowStatus(FollowStatusDict.NOT_FOLLOWED_UP.getCode());
        lead.setIdempotencyKey(normalizeRequiredString(
                request.getIdempotencyKey(),
                PortfolioMessage.CONTACT_SUBMIT_IDEMPOTENCY_REQUIRED_MESSAGE
        ));
        lead.setSubmittedAt(now);
        try {
            contactLeadEntityMapper.insert(lead);
        } catch (DuplicateKeyException e) {
            ContactLeadEntity existingLead = findExistingLead(portfolio, lead.getIdempotencyKey());
            if (existingLead != null) {
                return buildSubmitResponse(existingLead);
            }
            throw e;
        }
        portfolioVisitService.recordContactLeadSubmitted(portfolio, request.getVisitorKey(), lead.getId(), lead.getIdempotencyKey());

        return buildSubmitResponse(lead);
    }

    /**
     * 按幂等键查询同一作品集下已提交的线索。
     *
     * @param portfolio 作品集
     * @param idempotencyKey 幂等键
     * @return 已存在的线索，找不到时返回 null
     */
    private ContactLeadEntity findExistingLead(PortfolioEntity portfolio, String idempotencyKey) {
        return contactLeadEntityMapper.selectOne(
                Wrappers.lambdaQuery(ContactLeadEntity.class)
                        .eq(ContactLeadEntity::getPortfolioId, portfolio.getId())
                        .eq(ContactLeadEntity::getOwnerType, portfolio.getOwnerType())
                        .eq(ContactLeadEntity::getOwnerId, portfolio.getOwnerId())
                        .eq(ContactLeadEntity::getIdempotencyKey, idempotencyKey)
                        .last("LIMIT 1")
        );
    }

    /**
     * 构建线索提交响应。
     *
     * @param lead 联系线索
     * @return 提交响应
     */
    private ContactLeadSubmitResponse buildSubmitResponse(ContactLeadEntity lead) {
        ContactLeadSubmitResponse response = new ContactLeadSubmitResponse();
        response.setLeadId(lead.getId());
        response.setSubmittedAt(lead.getSubmittedAt());
        return response;
    }

    /**
     * 解析作品集标题快照。
     *
     * @param portfolio 作品集
     * @return 标题快照
     */
    private String resolvePortfolioTitleSnapshot(PortfolioEntity portfolio) {
        if (portfolio == null || portfolio.getPublishedConfigJson() == null
                || portfolio.getPublishedConfigJson().isBlank()) {
            return DEFAULT_PORTFOLIO_TITLE_SNAPSHOT;
        }
        try {
            PortfolioConfigDto config = JSON.parseObject(portfolio.getPublishedConfigJson(), PortfolioConfigDto.class);
            if (config != null && config.getShare() != null && config.getShare().getTitle() != null
                    && !config.getShare().getTitle().isBlank()) {
                return config.getShare().getTitle().strip();
            }
            return DEFAULT_PORTFOLIO_TITLE_SNAPSHOT;
        } catch (Exception e) {
            return DEFAULT_PORTFOLIO_TITLE_SNAPSHOT;
        }
    }

    /**
     * 解析作品集分享编码快照。
     *
     * @param portfolio 作品集
     * @return 分享编码快照
     */
    private String resolvePortfolioShareCodeSnapshot(PortfolioEntity portfolio) {
        if (portfolio == null || portfolio.getShareCode() == null || portfolio.getShareCode().isBlank()) {
            return DEFAULT_PORTFOLIO_SHARE_CODE_SNAPSHOT;
        }
        return portfolio.getShareCode().strip();
    }

    /**
     * 手机尾号。
     *
     * @param value 手机号
     * @return 尾号
     */
    private String last4(String value) {
        String normalized = defaultString(value).replaceAll("\\D", "");
        if (normalized.length() <= 4) {
            return normalized.isBlank() ? null : normalized;
        }
        return normalized.substring(normalized.length() - 4);
    }

    /**
     * 微信号脱敏提示。
     *
     * @param value 微信号
     * @return 脱敏提示
     */
    private String maskWechat(String value) {
        String normalized = defaultString(value);
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.length() <= 4) {
            return normalized.charAt(0) + "***";
        }
        return normalized.substring(0, 2) + "***" + normalized.substring(normalized.length() - 2);
    }

    /**
     * 规范化必填字符串。
     *
     * @param value 原值
     * @param message 错误提示
     * @return 字符串
     */
    private String normalizeRequiredString(String value, String message) {
        if (!hasText(value)) {
            throw new BusinessException(message);
        }
        return value.strip();
    }

    /**
     * 默认字符串。
     *
     * @param value 原值
     * @return 非空字符串
     */
    private String defaultString(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * 判断字符串是否有内容。
     *
     * @param value 原值
     * @return true 表示有内容
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
