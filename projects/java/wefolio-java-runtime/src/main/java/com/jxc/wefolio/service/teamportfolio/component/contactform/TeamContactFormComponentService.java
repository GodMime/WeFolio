package com.jxc.wefolio.service.teamportfolio.component.contactform;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.common.auth.VisitorContext;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.FollowStatusDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.PortfolioTypeDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.dict.VisitSourceTypeDict;
import com.jxc.wefolio.dto.teamportfolio.TeamContactLeadResponse;
import com.jxc.wefolio.dto.teamportfolio.TeamContactLeadSubmitRequest;
import com.jxc.wefolio.entity.ContactLeadEntity;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.ContactLeadEntityMapper;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 团队预留联系信息组件运行服务。
 *
 * <p>当前 v1 密文依赖共享 AUTH_TOKEN_SECRET。完成密文迁移或引入 keyring 前不得直接轮换该密钥，
 * 部署侧必须对当前密钥进行安全备份，避免历史团队联系方式永久无法解密。</p>
 */
@Service
public class TeamContactFormComponentService {

    /** 联系表单组件类型。 */
    private static final String COMPONENT_TYPE_CONTACT_FORM = "CONTACT_FORM";

    /** 组件启用配置键。 */
    private static final String CONFIG_KEY_ENABLED = "enabled";

    /** 组件类型配置键。 */
    private static final String CONFIG_KEY_COMPONENT_TYPE = "componentType";

    /** 组件实例键配置键。 */
    private static final String CONFIG_KEY_COMPONENT_KEY = "componentKey";

    /** 组件配置配置键。 */
    private static final String CONFIG_KEY_CONFIG = "config";

    /** 组件配置列表键。 */
    private static final String CONFIG_KEY_COMPONENTS = "components";

    /** Schema 版本配置键。 */
    private static final String CONFIG_KEY_SCHEMA_VERSION = "schemaVersion";

    /** 分享信息配置键。 */
    private static final String CONFIG_KEY_SHARE = "share";

    /** 分享标题配置键。 */
    private static final String CONFIG_KEY_TITLE = "title";

    /** 默认团队作品集标题。 */
    private static final String DEFAULT_PORTFOLIO_TITLE = "团队作品集";

    /** 默认隐私同意版本。 */
    private static final String DEFAULT_CONSENT_VERSION = "v1";

    /** 联系人最大长度。 */
    private static final int CONTACT_NAME_MAX_LENGTH = 50;

    /** 意向档期最大长度。 */
    private static final int DESIRED_SCHEDULE_MAX_LENGTH = 100;

    /** 需求描述最大长度。 */
    private static final int NEEDS_MAX_LENGTH = 1000;

    /** 隐私同意版本最大长度。 */
    private static final int CONSENT_VERSION_MAX_LENGTH = 32;

    /** 幂等键最大长度。 */
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 64;

    /** 访客稳定键最大长度。 */
    private static final int VISITOR_KEY_MAX_LENGTH = 64;

    /** 联系方式明文最大长度。 */
    private static final int CONTACT_VALUE_MAX_LENGTH = 256;

    /** 最大分页大小。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 查询单条记录限制。 */
    private static final String QUERY_LIMIT_ONE = "LIMIT 1";

    /** 唯一键竞争后查询当前已提交线索并加锁。 */
    private static final String QUERY_LIMIT_ONE_FOR_UPDATE = "LIMIT 1 FOR UPDATE";

    /** 作品集不可访问提示。 */
    private static final String PORTFOLIO_UNAVAILABLE_MESSAGE = "当前团队作品集暂未开放访问";

    /** 访客身份非法提示。 */
    private static final String VISITOR_INVALID_MESSAGE = "访客身份无效";

    /** 访问记录非法提示。 */
    private static final String VISIT_RECORD_INVALID_MESSAGE = "访问记录无效";

    /** 联系人必填提示。 */
    private static final String CONTACT_NAME_REQUIRED_MESSAGE = "请填写联系人";

    /** 联系方式必填提示。 */
    private static final String CONTACT_METHOD_REQUIRED_MESSAGE = "请至少填写手机号或微信号";

    /** 幂等键必填提示。 */
    private static final String IDEMPOTENCY_KEY_REQUIRED_MESSAGE = "请提供提交幂等键";

    /** 字段长度非法提示。 */
    private static final String FIELD_LENGTH_INVALID_MESSAGE = "预留联系信息字段长度不合法";

    /** 分页参数非法提示。 */
    private static final String PAGE_INVALID_MESSAGE = "分页参数不合法";

    /** 线索不存在提示。 */
    private static final String LEAD_NOT_FOUND_MESSAGE = "团队预留信息不存在";

    /** 跟进状态非法提示。 */
    private static final String FOLLOW_STATUS_INVALID_MESSAGE = "跟进状态不支持";

    /** 跟进备注长度非法提示。 */
    private static final String FOLLOW_NOTE_LENGTH_INVALID_MESSAGE = "跟进备注长度不合法";

    /** 写入失败提示。 */
    private static final String PERSISTENCE_FAILED_MESSAGE = "预留联系信息保存失败";

    /** 团队可读取线索的角色。 */
    private static final Set<String> READABLE_ROLES = Set.of(
            TeamRoleDict.OWNER.getCode(), TeamRoleDict.MANAGER.getCode(), TeamRoleDict.MEMBER.getCode());

    /** 团队可更新跟进的角色。 */
    private static final Set<String> FOLLOW_UPDATABLE_ROLES = Set.of(
            TeamRoleDict.OWNER.getCode(), TeamRoleDict.MANAGER.getCode());

    /** 作品集数据访问器。 */
    private final PortfolioEntityMapper portfolioEntityMapper;

    /** 访问记录数据访问器。 */
    private final VisitRecordEntityMapper visitRecordEntityMapper;

    /** 联系线索数据访问器。 */
    private final ContactLeadEntityMapper contactLeadEntityMapper;

    /** 团队访问控制服务。 */
    private final TeamPortfolioAccessService teamPortfolioAccessService;

    /** 团队联系方式加解密服务。 */
    private final TeamContactLeadCryptoService contactLeadCryptoService;

    /** 团队预留联系信息组件配置校验器。 */
    private final TeamContactFormComponentValidator validator;

    /**
     * 创建团队预留联系信息组件运行服务。
     *
     * @param portfolioEntityMapper 作品集数据访问器
     * @param visitRecordEntityMapper 访问记录数据访问器
     * @param contactLeadEntityMapper 联系线索数据访问器
     * @param teamPortfolioAccessService 团队访问控制服务
     * @param contactLeadCryptoService 团队联系方式加解密服务
     * @param validator 团队预留联系信息组件配置校验器
     */
    public TeamContactFormComponentService(
            PortfolioEntityMapper portfolioEntityMapper,
            VisitRecordEntityMapper visitRecordEntityMapper,
            ContactLeadEntityMapper contactLeadEntityMapper,
            TeamPortfolioAccessService teamPortfolioAccessService,
            TeamContactLeadCryptoService contactLeadCryptoService,
            TeamContactFormComponentValidator validator
    ) {
        this.portfolioEntityMapper = portfolioEntityMapper;
        this.visitRecordEntityMapper = visitRecordEntityMapper;
        this.contactLeadEntityMapper = contactLeadEntityMapper;
        this.teamPortfolioAccessService = teamPortfolioAccessService;
        this.contactLeadCryptoService = contactLeadCryptoService;
        this.validator = validator;
    }

    /**
     * 提交团队作品集预留联系信息。
     *
     * @param shareCode 团队作品集分享编码
     * @param request 提交请求
     * @param visitor 已认证访客上下文
     * @return 线索提交结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SubmitResult submit(String shareCode, TeamContactLeadSubmitRequest request, VisitorContext visitor) {
        String normalizedShareCode = requireText(shareCode, PORTFOLIO_UNAVAILABLE_MESSAGE, IDEMPOTENCY_KEY_MAX_LENGTH);
        PortfolioEntity portfolio = portfolioEntityMapper.selectOne(
                Wrappers.lambdaQuery(PortfolioEntity.class)
                        .eq(PortfolioEntity::getShareCode, normalizedShareCode)
                        .eq(PortfolioEntity::getOwnerType, PortfolioOwnerTypeDict.TEAM.getCode())
                        .eq(PortfolioEntity::getTemplateType, PortfolioTemplateTypeDict.STANDARD.getCode())
                        .eq(PortfolioEntity::getStatus, PortfolioStatusDict.ACTIVE.getCode())
                        .eq(PortfolioEntity::getSchemaVersion,
                                TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1)
                        .eq(PortfolioEntity::getPublicationStatus, PortfolioPublicationStatusDict.PUBLISHED.getCode())
                        .last(QUERY_LIMIT_ONE)
        );
        if (!isPublishedTeamPortfolio(portfolio, normalizedShareCode)) {
            throw new BusinessException(PORTFOLIO_UNAVAILABLE_MESSAGE);
        }
        requirePublishedContactForm(portfolio, null);
        VisitorIdentity visitorIdentity = requireVisitor(visitor);
        NormalizedRequest normalizedRequest = normalizeRequest(request);
        VisitRecordEntity visitRecord = visitRecordEntityMapper.selectById(normalizedRequest.visitRecordId());
        if (!isOwnedVisitRecord(visitRecord, portfolio, visitorIdentity)) {
            throw new BusinessException(VISIT_RECORD_INVALID_MESSAGE);
        }
        LocalDateTime now = LocalDateTime.now();
        ContactLeadEntity lead = new ContactLeadEntity();
        lead.setPortfolioId(portfolio.getId());
        lead.setPortfolioTitleSnapshot(resolvePortfolioTitle(portfolio));
        lead.setPortfolioShareCodeSnapshot(normalizedShareCode);
        lead.setPortfolioRevision(portfolio.getPublishedRevision());
        lead.setVisitRecordId(normalizedRequest.visitRecordId());
        lead.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        lead.setOwnerId(portfolio.getOwnerId());
        lead.setContactName(normalizedRequest.contactName());
        lead.setPhoneCiphertext(contactLeadCryptoService.encryptPhone(normalizedRequest.phone()));
        lead.setWechatCiphertext(contactLeadCryptoService.encryptWechat(normalizedRequest.wechat()));
        lead.setDesiredSchedule(normalizedRequest.desiredSchedule());
        lead.setNeeds(normalizedRequest.needs());
        lead.setSourceType(resolveSourceType(visitRecord.getSourceType()));
        lead.setConsentVersion(normalizedRequest.consentVersion());
        lead.setConsentAt(now);
        lead.setFollowStatus(FollowStatusDict.NOT_FOLLOWED_UP.getCode());
        lead.setFollowNote("");
        lead.setIdempotencyKey(normalizedRequest.idempotencyKey());
        lead.setSubmittedAt(now);
        try {
            if (contactLeadEntityMapper.insert(lead) != 1
                    || lead.getId() == null || lead.getId() <= 0 || lead.getSubmittedAt() == null) {
                throw new BusinessException(PERSISTENCE_FAILED_MESSAGE);
            }
        } catch (DuplicateKeyException exception) {
            ContactLeadEntity existing = findExistingLeadForUpdate(
                    portfolio, normalizedRequest.idempotencyKey());
            if (isSameIdempotencyLead(existing, portfolio, normalizedRequest.idempotencyKey())) {
                return new SubmitResult(existing.getId(), existing.getSubmittedAt());
            }
            throw exception;
        }
        return new SubmitResult(lead.getId(), lead.getSubmittedAt());
    }

    /**
     * 查询团队预留联系信息。
     *
     * @param teamId 团队 ID
     * @param page 页码，从 1 开始
     * @param pageSize 页大小
     * @param userId 当前维护者 ID
     * @return 团队线索分页结果
     */
    public TeamContactLeadResponse list(long teamId, int page, int pageSize, long userId) {
        TeamPortfolioAccessService.TeamPortfolioAccess access = teamPortfolioAccessService.requireTeamRole(
                teamId, userId, READABLE_ROLES);
        validatePage(teamId, page, pageSize);
        Page<ContactLeadEntity> selected = contactLeadEntityMapper.selectPage(
                new Page<>(page, pageSize),
                Wrappers.lambdaQuery(ContactLeadEntity.class)
                        .eq(ContactLeadEntity::getOwnerType, PortfolioOwnerTypeDict.TEAM.getCode())
                        .eq(ContactLeadEntity::getOwnerId, teamId)
                        .orderByDesc(ContactLeadEntity::getSubmittedAt)
                        .orderByDesc(ContactLeadEntity::getId)
        );
        TeamContactLeadResponse response = new TeamContactLeadResponse();
        response.setPage(page);
        response.setPageSize(pageSize);
        if (selected == null) {
            response.setHasMore(false);
            response.setItems(List.of());
            return response;
        }
        response.setHasMore(page < selected.getPages());
        List<TeamContactLeadResponse.Item> items = new ArrayList<>();
        for (ContactLeadEntity lead : selected.getRecords() == null ? List.<ContactLeadEntity>of() : selected.getRecords()) {
            if (!isTeamLead(lead, teamId)) {
                continue;
            }
            items.add(toItem(lead, teamId, access.team()));
        }
        response.setItems(List.copyOf(items));
        return response;
    }

    /**
     * 更新团队线索跟进状态。
     *
     * @param teamId 团队 ID
     * @param leadId 线索 ID
     * @param followStatus 跟进状态
     * @param followNote 跟进备注
     * @param userId 当前维护者 ID
     * @return 更新后的完整线索展示项
     */
    @Transactional(rollbackFor = Exception.class)
    public TeamContactLeadResponse.Item updateFollowStatus(
            long teamId,
            long leadId,
            String followStatus,
            String followNote,
            long userId
    ) {
        TeamPortfolioAccessService.TeamPortfolioAccess access = teamPortfolioAccessService.requireTeamRole(
                teamId, userId, FOLLOW_UPDATABLE_ROLES);
        if (teamId <= 0 || leadId <= 0) {
            throw new BusinessException(LEAD_NOT_FOUND_MESSAGE);
        }
        FollowStatusDict status = FollowStatusDict.fromCode(normalizeString(followStatus));
        if (status == null) {
            throw new BusinessException(FOLLOW_STATUS_INVALID_MESSAGE);
        }
        String normalizedNote = normalizeString(followNote);
        if (normalizedNote.length() > NEEDS_MAX_LENGTH) {
            throw new BusinessException(FOLLOW_NOTE_LENGTH_INVALID_MESSAGE);
        }
        ContactLeadEntity lead = contactLeadEntityMapper.selectOne(
                Wrappers.lambdaQuery(ContactLeadEntity.class)
                        .eq(ContactLeadEntity::getId, leadId)
                        .eq(ContactLeadEntity::getOwnerType, PortfolioOwnerTypeDict.TEAM.getCode())
                        .eq(ContactLeadEntity::getOwnerId, teamId)
                        .last(QUERY_LIMIT_ONE)
        );
        if (!isTeamLead(lead, teamId)) {
            throw new BusinessException(LEAD_NOT_FOUND_MESSAGE);
        }
        ContactLeadEntity patch = new ContactLeadEntity();
        patch.setFollowStatus(status.getCode());
        patch.setFollowNote(normalizedNote);
        LambdaUpdateWrapper<ContactLeadEntity> updateWrapper = Wrappers.lambdaUpdate(ContactLeadEntity.class)
                .eq(ContactLeadEntity::getId, leadId)
                .eq(ContactLeadEntity::getOwnerType, PortfolioOwnerTypeDict.TEAM.getCode())
                .eq(ContactLeadEntity::getOwnerId, teamId);
        Integer previousVersion = lead.getVersion();
        if (previousVersion != null) {
            updateWrapper
                    .eq(ContactLeadEntity::getVersion, previousVersion)
                    .set(ContactLeadEntity::getVersion, previousVersion + 1);
        }
        if (contactLeadEntityMapper.update(patch, updateWrapper) != 1) {
            throw new BusinessException(PERSISTENCE_FAILED_MESSAGE);
        }
        lead.setFollowStatus(status.getCode());
        lead.setFollowNote(normalizedNote);
        lead.setVersion(previousVersion == null ? null : previousVersion + 1);
        return toItem(lead, teamId, access.team());
    }

    /**
     * 无写入地校验指定实例是当前发布配置中已启用的联系表单组件。
     *
     * @param portfolio 已发布标准团队作品集
     * @param componentKey 联系表单组件实例键
     */
    public void validatePublishedComponent(PortfolioEntity portfolio, String componentKey) {
        String shareCode = portfolio == null ? "" : normalizeString(portfolio.getShareCode());
        if (!isPublishedTeamPortfolio(portfolio, shareCode) || normalizeString(componentKey).isBlank()) {
            throw new BusinessException(PORTFOLIO_UNAVAILABLE_MESSAGE);
        }
        requirePublishedContactForm(portfolio, normalizeString(componentKey));
    }

    /**
     * 校验分享作品集是否具备完整团队公开身份。
     *
     * @param portfolio 作品集实体
     * @param shareCode 分享编码
     * @return 是否可作为团队访客作品集
     */
    private boolean isPublishedTeamPortfolio(PortfolioEntity portfolio, String shareCode) {
        return portfolio != null
                && portfolio.getId() != null && portfolio.getId() > 0
                && portfolio.getOwnerId() != null && portfolio.getOwnerId() > 0
                && portfolio.getPublishedRevision() != null && portfolio.getPublishedRevision() > 0
                && PortfolioOwnerTypeDict.TEAM.getCode().equals(portfolio.getOwnerType())
                && PortfolioTemplateTypeDict.STANDARD.getCode().equals(portfolio.getTemplateType())
                && PortfolioStatusDict.ACTIVE.getCode().equals(portfolio.getStatus())
                && TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1.equals(portfolio.getSchemaVersion())
                && PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())
                && Long.valueOf(0L).equals(portfolio.getDeleted())
                && shareCode.equals(normalizeString(portfolio.getShareCode()))
                && !normalizeString(portfolio.getPublishedConfigJson()).isBlank();
    }

    /**
     * 确认发布配置中存在已启用且配置合法的预留联系信息组件。
     *
     * @param portfolio 已校验作品集
     * @param requiredComponentKey 指定组件实例键；提交入口为空时接受任一有效实例
     */
    private void requirePublishedContactForm(PortfolioEntity portfolio, String requiredComponentKey) {
        try {
            JSONObject config = JSON.parseObject(portfolio.getPublishedConfigJson());
            if (config == null || !TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1.equals(
                    normalizeString(config.getString(CONFIG_KEY_SCHEMA_VERSION)))) {
                throw new BusinessException(PORTFOLIO_UNAVAILABLE_MESSAGE);
            }
            JSONArray components = config.getJSONArray(CONFIG_KEY_COMPONENTS);
            if (components == null) {
                throw new BusinessException(PORTFOLIO_UNAVAILABLE_MESSAGE);
            }
            TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(
                    portfolio.getOwnerId(), portfolio.getId(), portfolio.getPublishedRevision());
            boolean found = false;
            for (Object componentObject : components) {
                if (!(componentObject instanceof JSONObject component)
                        || requiredComponentKey != null && !requiredComponentKey.equals(
                                normalizeString(component.getString(CONFIG_KEY_COMPONENT_KEY)))
                        || !Boolean.TRUE.equals(component.getBoolean(CONFIG_KEY_ENABLED))
                        || !COMPONENT_TYPE_CONTACT_FORM.equals(component.getString(CONFIG_KEY_COMPONENT_TYPE))) {
                    continue;
                }
                validator.normalizeAndValidate(component.getJSONObject(CONFIG_KEY_CONFIG), context);
                found = true;
            }
            if (!found) {
                throw new BusinessException(PORTFOLIO_UNAVAILABLE_MESSAGE);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(PORTFOLIO_UNAVAILABLE_MESSAGE, exception);
        }
    }

    /**
     * 校验访客上下文。
     *
     * @param visitor 访客上下文
     * @return 可用于归属复验的访客身份
     */
    private VisitorIdentity requireVisitor(VisitorContext visitor) {
        if (visitor == null) {
            throw new BusinessException(VISITOR_INVALID_MESSAGE);
        }
        String visitorKey = normalizeString(visitor.getVisitorKey());
        if (visitorKey.isBlank() || visitorKey.length() > VISITOR_KEY_MAX_LENGTH
                || (visitor.getVisitorId() != null && visitor.getVisitorId() <= 0)) {
            throw new BusinessException(VISITOR_INVALID_MESSAGE);
        }
        return new VisitorIdentity(visitor.getVisitorId(), visitorKey);
    }

    /**
     * 规范化并校验提交请求。
     *
     * @param request 原始请求
     * @return 规范化请求
     */
    private NormalizedRequest normalizeRequest(TeamContactLeadSubmitRequest request) {
        if (request == null || request.getVisitRecordId() == null || request.getVisitRecordId() <= 0) {
            throw new BusinessException(VISIT_RECORD_INVALID_MESSAGE);
        }
        String contactName = requireText(request.getContactName(), CONTACT_NAME_REQUIRED_MESSAGE, CONTACT_NAME_MAX_LENGTH);
        String phone = normalizeString(request.getPhone());
        String wechat = normalizeString(request.getWechat());
        if (phone.isBlank() && wechat.isBlank()) {
            throw new BusinessException(CONTACT_METHOD_REQUIRED_MESSAGE);
        }
        validateMaximumLength(phone, CONTACT_VALUE_MAX_LENGTH);
        validateMaximumLength(wechat, CONTACT_VALUE_MAX_LENGTH);
        String desiredSchedule = normalizeString(request.getDesiredSchedule());
        String needs = normalizeString(request.getNeeds());
        String consentVersion = normalizeString(request.getConsentVersion());
        String idempotencyKey = requireText(request.getIdempotencyKey(), IDEMPOTENCY_KEY_REQUIRED_MESSAGE, IDEMPOTENCY_KEY_MAX_LENGTH);
        validateMaximumLength(desiredSchedule, DESIRED_SCHEDULE_MAX_LENGTH);
        validateMaximumLength(needs, NEEDS_MAX_LENGTH);
        if (consentVersion.isBlank()) {
            consentVersion = DEFAULT_CONSENT_VERSION;
        }
        validateMaximumLength(consentVersion, CONSENT_VERSION_MAX_LENGTH);
        return new NormalizedRequest(request.getVisitRecordId(), contactName, phone, wechat,
                desiredSchedule, needs, consentVersion, idempotencyKey);
    }

    /**
     * 校验访问记录与当前团队作品集、访客身份完全一致。
     *
     * @param visitRecord 访问记录
     * @param portfolio 团队作品集
     * @param visitor 访客身份
     * @return 是否通过归属复验
     */
    private boolean isOwnedVisitRecord(
            VisitRecordEntity visitRecord,
            PortfolioEntity portfolio,
            VisitorIdentity visitor
    ) {
        return visitRecord != null
                && visitRecord.getId() != null && visitRecord.getId() > 0
                && visitor.visitorKey().equals(normalizeString(visitRecord.getVisitorKey()))
                && (visitor.visitorId() == null || visitRecord.getVisitorId() == null
                || visitor.visitorId().equals(visitRecord.getVisitorId()))
                && portfolio.getId().equals(visitRecord.getPortfolioId())
                && PortfolioTypeDict.TEAM.getCode().equals(visitRecord.getPortfolioType())
                && PortfolioOwnerTypeDict.TEAM.getCode().equals(visitRecord.getOwnerType())
                && portfolio.getOwnerId().equals(visitRecord.getOwnerId());
    }

    /**
     * 查询同团队同作品集同幂等键的已有线索。
     *
     * @param portfolio 团队作品集
     * @param idempotencyKey 幂等键
     * @return 已有线索或 null
     */
    private ContactLeadEntity findExistingLeadForUpdate(PortfolioEntity portfolio, String idempotencyKey) {
        return contactLeadEntityMapper.selectOne(
                Wrappers.lambdaQuery(ContactLeadEntity.class)
                        .eq(ContactLeadEntity::getIdempotencyKey, idempotencyKey)
                        .eq(ContactLeadEntity::getOwnerType, PortfolioOwnerTypeDict.TEAM.getCode())
                        .eq(ContactLeadEntity::getOwnerId, portfolio.getOwnerId())
                        .eq(ContactLeadEntity::getPortfolioId, portfolio.getId())
                        .last(QUERY_LIMIT_ONE_FOR_UPDATE)
        );
    }

    /**
     * 复验幂等查询结果，避免不可信 Mapper 结果被错误复用。
     *
     * @param lead 已有线索
     * @param portfolio 团队作品集
     * @param idempotencyKey 幂等键
     * @return 是否可安全复用
     */
    private boolean isSameIdempotencyLead(ContactLeadEntity lead, PortfolioEntity portfolio, String idempotencyKey) {
        return lead != null
                && lead.getId() != null && lead.getId() > 0
                && PortfolioOwnerTypeDict.TEAM.getCode().equals(lead.getOwnerType())
                && portfolio.getOwnerId().equals(lead.getOwnerId())
                && portfolio.getId().equals(lead.getPortfolioId())
                && idempotencyKey.equals(lead.getIdempotencyKey())
                && lead.getSubmittedAt() != null;
    }

    /**
     * 校验分页参数。
     *
     * @param teamId 团队 ID
     * @param page 页码
     * @param pageSize 页大小
     */
    private void validatePage(long teamId, int page, int pageSize) {
        if (teamId <= 0 || page <= 0 || pageSize <= 0 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(PAGE_INVALID_MESSAGE);
        }
    }

    /**
     * 校验线索是否确实归属于指定团队。
     *
     * @param lead 线索实体
     * @param teamId 团队 ID
     * @return 是否归属指定团队
     */
    private boolean isTeamLead(ContactLeadEntity lead, long teamId) {
        return lead != null
                && lead.getId() != null && lead.getId() > 0
                && PortfolioOwnerTypeDict.TEAM.getCode().equals(lead.getOwnerType())
                && Long.valueOf(teamId).equals(lead.getOwnerId());
    }

    /**
     * 转换团队线索为鉴权后的完整展示项。
     *
     * @param lead 团队线索
     * @param teamId 团队 ID
     * @param team 团队实体
     * @return 完整展示项
     */
    private TeamContactLeadResponse.Item toItem(ContactLeadEntity lead, long teamId, TeamEntity team) {
        TeamContactLeadResponse.Item item = new TeamContactLeadResponse.Item();
        item.setLeadId(lead.getId());
        item.setTeamId(teamId);
        item.setTeamName(team == null ? "" : normalizeString(team.getName()));
        item.setPortfolioId(lead.getPortfolioId());
        item.setPortfolioTitle(normalizeString(lead.getPortfolioTitleSnapshot()));
        item.setPortfolioShareCode(normalizeString(lead.getPortfolioShareCodeSnapshot()));
        item.setPortfolioRevision(lead.getPortfolioRevision());
        item.setVisitRecordId(lead.getVisitRecordId());
        item.setContactName(normalizeString(lead.getContactName()));
        item.setPhone(contactLeadCryptoService.decryptPhone(lead.getPhoneCiphertext()));
        item.setWechat(contactLeadCryptoService.decryptWechat(lead.getWechatCiphertext()));
        item.setDesiredSchedule(normalizeString(lead.getDesiredSchedule()));
        item.setNeeds(normalizeString(lead.getNeeds()));
        String sourceType = resolveSourceType(lead.getSourceType());
        VisitSourceTypeDict source = VisitSourceTypeDict.fromCode(sourceType);
        item.setSourceType(sourceType);
        item.setSourceText(source.getDisplayName());
        item.setConsentVersion(normalizeString(lead.getConsentVersion()));
        FollowStatusDict status = FollowStatusDict.fromCode(lead.getFollowStatus());
        item.setFollowStatus(status == null ? FollowStatusDict.NOT_FOLLOWED_UP.getCode() : status.getCode());
        item.setFollowStatusText(status == null
                ? FollowStatusDict.NOT_FOLLOWED_UP.getDisplayName() : status.getDisplayName());
        item.setFollowNote(normalizeString(lead.getFollowNote()));
        item.setSubmittedAt(lead.getSubmittedAt());
        return item;
    }

    /**
     * 解析团队作品集标题快照。
     *
     * @param portfolio 团队作品集
     * @return 作品集标题
     */
    private String resolvePortfolioTitle(PortfolioEntity portfolio) {
        try {
            JSONObject config = JSON.parseObject(portfolio.getPublishedConfigJson());
            JSONObject share = config == null ? null : config.getJSONObject(CONFIG_KEY_SHARE);
            String title = share == null ? "" : normalizeString(share.getString(CONFIG_KEY_TITLE));
            return title.isBlank() ? DEFAULT_PORTFOLIO_TITLE : title;
        } catch (RuntimeException exception) {
            throw new BusinessException(PORTFOLIO_UNAVAILABLE_MESSAGE, exception);
        }
    }

    /**
     * 将未知来源统一回落为 UNKNOWN。
     *
     * @param sourceType 原始来源类型
     * @return 有效来源类型
     */
    private String resolveSourceType(String sourceType) {
        VisitSourceTypeDict source = VisitSourceTypeDict.fromCode(normalizeString(sourceType));
        return source == null ? VisitSourceTypeDict.UNKNOWN.getCode() : source.getCode();
    }

    /**
     * 校验必填文本和长度。
     *
     * @param value 原始文本
     * @param message 空值提示
     * @param maximumLength 最大长度
     * @return 规范化必填文本
     */
    private String requireText(String value, String message, int maximumLength) {
        String normalized = normalizeString(value);
        if (normalized.isBlank()) {
            throw new BusinessException(message);
        }
        validateMaximumLength(normalized, maximumLength);
        return normalized;
    }

    /**
     * 校验文本最大长度。
     *
     * @param value 文本
     * @param maximumLength 最大长度
     */
    private void validateMaximumLength(String value, int maximumLength) {
        if (value != null && value.length() > maximumLength) {
            throw new BusinessException(FIELD_LENGTH_INVALID_MESSAGE);
        }
    }

    /**
     * 规范化字符串。
     *
     * @param value 原始字符串
     * @return 非空、去除首尾空白的字符串
     */
    private String normalizeString(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * 访客身份快照。
     *
     * @param visitorId 访客 ID
     * @param visitorKey 访客稳定键
     */
    private record VisitorIdentity(Long visitorId, String visitorKey) {
    }

    /**
     * 规范化后的提交请求。
     *
     * @param visitRecordId 访问记录 ID
     * @param contactName 联系人
     * @param phone 手机号
     * @param wechat 微信号
     * @param desiredSchedule 意向档期
     * @param needs 需求描述
     * @param consentVersion 隐私同意版本
     * @param idempotencyKey 幂等键
     */
    private record NormalizedRequest(
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

    /**
     * 访客提交成功结果。
     *
     * @param leadId 线索 ID
     * @param submittedAt 提交时间
     */
    public record SubmitResult(Long leadId, LocalDateTime submittedAt) {
    }
}
