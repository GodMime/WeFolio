package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.common.auth.VisitorContext;
import com.jxc.wefolio.common.auth.VisitorContextHolder;
import com.jxc.wefolio.dict.FollowStatusDict;
import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioTypeDict;
import com.jxc.wefolio.dict.VisitSourceTypeDict;
import com.jxc.wefolio.dto.ContactLeadSubmitRequest;
import com.jxc.wefolio.dto.ContactLeadSubmitResponse;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.entity.ContactLeadEntity;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.exception.AuthenticationRequiredException;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.ContactLeadEntityMapper;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import com.jxc.wefolio.message.PortfolioMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

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

    /** 联系人最大长度 */
    private static final int CONTACT_NAME_MAX_LENGTH = 50;

    /** 手机号和微信号最大长度 */
    private static final int CONTACT_METHOD_MAX_LENGTH = 512;

    /** 意向档期最大长度 */
    private static final int DESIRED_SCHEDULE_MAX_LENGTH = 100;

    /** 需求描述最大长度 */
    private static final int NEEDS_MAX_LENGTH = 1000;

    /** 隐私说明版本最大长度 */
    private static final int CONSENT_VERSION_MAX_LENGTH = 32;

    /** 幂等键最大长度 */
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 64;

    /** 普通访客在同一作品集允许提交的最大次数 */
    private static final long MAX_SUBMISSION_COUNT = 3L;

    /** 联系线索 Mapper */
    private final ContactLeadEntityMapper contactLeadEntityMapper;

    /** 作品集 Mapper */
    private final PortfolioEntityMapper portfolioEntityMapper;

    /** 访问汇总 Mapper */
    private final VisitRecordEntityMapper visitRecordEntityMapper;

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
     * 按分享编码严格校验并提交线索。
     *
     * @param shareCode 分享编码
     * @param request 提交请求
     * @return 提交响应
     */
    @Transactional(rollbackFor = Exception.class)
    public ContactLeadSubmitResponse submitV2(String shareCode, ContactLeadSubmitRequest request) {
        PortfolioEntity portfolio = requirePublishedPersonalPortfolio(shareCode);
        requireEnabledContactForm(portfolio);
        NormalizedContactLeadRequest normalized = normalizeRequest(request, true);
        VisitorContext visitorContext = VisitorContextHolder.current()
                .orElseThrow(() -> new AuthenticationRequiredException("访客未登录"));
        VisitRecordEntity visitRecord = requireMatchingVisitRecord(
                portfolio,
                normalized.visitRecordId(),
                visitorContext
        );
        ContactLeadEntity existingLead = findExistingLead(portfolio, normalized.idempotencyKey());
        if (existingLead != null) {
            return buildSubmitResponse(existingLead);
        }
        if (!visitorContext.isTimelineAnonymous()) {
            enforceSubmissionLimit(portfolio, visitRecord);
        }
        return persistLead(portfolio, normalized, normalizeSourceType(visitRecord.getSourceType()));
    }

    /**
     * 提交线索。
     *
     * @param portfolio 作品集
     * @param request 提交请求
     * @return 提交响应
     */
    private ContactLeadSubmitResponse submitInternal(PortfolioEntity portfolio, ContactLeadSubmitRequest request) {
        NormalizedContactLeadRequest normalized = normalizeRequest(request, false);
        return persistLead(portfolio, normalized, normalizeSourceType(request.getSourceType()));
    }

    /**
     * 持久化联系线索并记录提交事件。
     *
     * @param portfolio 作品集
     * @param request 已规范化请求
     * @param sourceType 可信来源类型
     * @return 提交响应
     */
    private ContactLeadSubmitResponse persistLead(
            PortfolioEntity portfolio,
            NormalizedContactLeadRequest request,
            String sourceType
    ) {
        LocalDateTime now = LocalDateTime.now();
        ContactLeadEntity lead = new ContactLeadEntity();
        lead.setPortfolioId(portfolio.getId());
        lead.setPortfolioTitleSnapshot(resolvePortfolioTitleSnapshot(portfolio));
        lead.setPortfolioShareCodeSnapshot(resolvePortfolioShareCodeSnapshot(portfolio));
        lead.setPortfolioRevision(portfolio.getPublishedRevision());
        lead.setVisitRecordId(request.visitRecordId());
        lead.setOwnerType(portfolio.getOwnerType());
        lead.setOwnerId(portfolio.getOwnerId());
        lead.setContactName(request.contactName());
        lead.setPhoneCiphertext(emptyToNull(request.phone()));
        lead.setPhoneLast4(last4(request.phone()));
        lead.setWechatCiphertext(emptyToNull(request.wechat()));
        lead.setWechatMaskHint(maskWechat(request.wechat()));
        lead.setDesiredSchedule(request.desiredSchedule());
        lead.setNeeds(request.needs());
        lead.setSourceType(sourceType);
        lead.setConsentVersion(request.consentVersion());
        lead.setConsentAt(now);
        lead.setFollowStatus(FollowStatusDict.NOT_FOLLOWED_UP.getCode());
        lead.setIdempotencyKey(request.idempotencyKey());
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
        portfolioVisitService.recordContactLeadSubmitted(
                portfolio,
                VisitorContextHolder.requireVisitorKey(),
                lead.getId(),
                lead.getIdempotencyKey()
        );

        return buildSubmitResponse(lead);
    }

    /**
     * 查询已发布的个人作品集。
     */
    private PortfolioEntity requirePublishedPersonalPortfolio(String shareCode) {
        PortfolioEntity portfolio = portfolioEntityMapper.selectOne(
                Wrappers.lambdaQuery(PortfolioEntity.class)
                        .eq(PortfolioEntity::getShareCode, shareCode)
                        .eq(PortfolioEntity::getOwnerType, PortfolioOwnerTypeDict.USER.getCode())
                        .eq(PortfolioEntity::getPublicationStatus, PortfolioPublicationStatusDict.PUBLISHED.getCode())
                        .last("LIMIT 1")
        );
        if (portfolio == null
                || !PortfolioOwnerTypeDict.USER.getCode().equals(portfolio.getOwnerType())
                || !PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_UNAVAILABLE_MESSAGE);
        }
        return portfolio;
    }

    /**
     * 校验已发布配置中存在启用的联系表单组件。
     */
    private void requireEnabledContactForm(PortfolioEntity portfolio) {
        try {
            PortfolioConfigDto config = JSON.parseObject(portfolio.getPublishedConfigJson(), PortfolioConfigDto.class);
            boolean enabled = PortfolioComponentTraversal.listComponentLocations(config).stream()
                    .map(PortfolioComponentTraversal.ComponentLocation::component)
                    .anyMatch(component -> component != null
                    && PortfolioComponentTypeDict.CONTACT_FORM.getCode().equals(component.getComponentType())
                    && Boolean.TRUE.equals(component.getEnabled()));
            if (enabled) {
                return;
            }
        } catch (Exception ignored) {
            // 严格接口将不可解析配置视为联系表单不可用。
        }
        throw new BusinessException(PortfolioMessage.CONTACT_FORM_COMPONENT_NOT_FOUND_MESSAGE);
    }

    /**
     * 锁定并校验访问记录与作品集、当前访客完全一致。
     */
    private VisitRecordEntity requireMatchingVisitRecord(
            PortfolioEntity portfolio,
            Long visitRecordId,
            VisitorContext visitorContext
    ) {
        VisitRecordEntity record = visitRecordEntityMapper.selectOne(
                Wrappers.lambdaQuery(VisitRecordEntity.class)
                        .eq(VisitRecordEntity::getId, visitRecordId)
                        .last("LIMIT 1 FOR UPDATE")
        );
        boolean matches = record != null
                && Objects.equals(record.getPortfolioId(), portfolio.getId())
                && PortfolioTypeDict.PERSONAL.getCode().equals(record.getPortfolioType())
                && Objects.equals(record.getOwnerType(), portfolio.getOwnerType())
                && Objects.equals(record.getOwnerId(), portfolio.getOwnerId())
                && Objects.equals(record.getVisitorId(), visitorContext.getVisitorId())
                && Objects.equals(record.getVisitorKey(), visitorContext.getVisitorKey());
        if (!matches) {
            throw new BusinessException(PortfolioMessage.CONTACT_LEAD_VISIT_RECORD_INVALID_MESSAGE);
        }
        return record;
    }

    /**
     * 校验普通访客在当前作品集的提交次数。
     */
    private void enforceSubmissionLimit(PortfolioEntity portfolio, VisitRecordEntity visitRecord) {
        Long count = contactLeadEntityMapper.selectCount(
                Wrappers.lambdaQuery(ContactLeadEntity.class)
                        .eq(ContactLeadEntity::getPortfolioId, portfolio.getId())
                        .eq(ContactLeadEntity::getVisitRecordId, visitRecord.getId())
                        .eq(ContactLeadEntity::getOwnerType, portfolio.getOwnerType())
                        .eq(ContactLeadEntity::getOwnerId, portfolio.getOwnerId())
        );
        if (count != null && count >= MAX_SUBMISSION_COUNT) {
            throw new BusinessException(PortfolioMessage.CONTACT_LEAD_SUBMISSION_LIMIT_MESSAGE);
        }
    }

    /**
     * 规范化并校验联系线索请求。
     */
    private NormalizedContactLeadRequest normalizeRequest(ContactLeadSubmitRequest request, boolean requireVisitRecord) {
        if (request == null || !hasText(request.getContactName())) {
            throw new BusinessException(PortfolioMessage.CONTACT_NAME_REQUIRED_MESSAGE);
        }
        if (!hasText(request.getPhone()) && !hasText(request.getWechat())) {
            throw new BusinessException(PortfolioMessage.CONTACT_METHOD_REQUIRED_MESSAGE);
        }
        if (requireVisitRecord && (request.getVisitRecordId() == null || request.getVisitRecordId() <= 0)) {
            throw new BusinessException(PortfolioMessage.CONTACT_LEAD_VISIT_RECORD_INVALID_MESSAGE);
        }
        String contactName = request.getContactName().strip();
        String phone = defaultString(request.getPhone());
        String wechat = defaultString(request.getWechat());
        String desiredSchedule = defaultString(request.getDesiredSchedule());
        String needs = defaultString(request.getNeeds());
        String consentVersion = hasText(request.getConsentVersion())
                ? request.getConsentVersion().strip()
                : DEFAULT_CONSENT_VERSION;
        String idempotencyKey = normalizeRequiredString(
                request.getIdempotencyKey(),
                PortfolioMessage.CONTACT_SUBMIT_IDEMPOTENCY_REQUIRED_MESSAGE
        );
        if (isTooLong(contactName, CONTACT_NAME_MAX_LENGTH)
                || isTooLong(phone, CONTACT_METHOD_MAX_LENGTH)
                || isTooLong(wechat, CONTACT_METHOD_MAX_LENGTH)
                || isTooLong(desiredSchedule, DESIRED_SCHEDULE_MAX_LENGTH)
                || isTooLong(needs, NEEDS_MAX_LENGTH)
                || isTooLong(consentVersion, CONSENT_VERSION_MAX_LENGTH)
                || isTooLong(idempotencyKey, IDEMPOTENCY_KEY_MAX_LENGTH)) {
            throw new BusinessException(PortfolioMessage.CONTACT_LEAD_FIELD_LENGTH_INVALID_MESSAGE);
        }
        return new NormalizedContactLeadRequest(
                request.getVisitRecordId(),
                contactName,
                phone,
                wechat,
                desiredSchedule,
                needs,
                consentVersion,
                idempotencyKey
        );
    }

    /**
     * 规范化来源类型。
     */
    private String normalizeSourceType(String sourceType) {
        return VisitSourceTypeDict.fromCode(sourceType) == null
                ? VisitSourceTypeDict.UNKNOWN.getCode()
                : sourceType;
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
     * 空字符串转换为空值。
     */
    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 判断字段是否超过最大长度。
     */
    private boolean isTooLong(String value, int maxLength) {
        return value != null && value.length() > maxLength;
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

    /**
     * 已规范化的联系线索提交请求。
     */
    private record NormalizedContactLeadRequest(
            Long visitRecordId,
            String contactName,
            String phone,
            String wechat,
            String desiredSchedule,
            String needs,
            String consentVersion,
            String idempotencyKey
    ) {
    }
}
