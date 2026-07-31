package com.jxc.wefolio.service.teamportfolio;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.common.auth.VisitorContext;
import com.jxc.wefolio.common.auth.VisitorContextHolder;
import com.jxc.wefolio.config.TeamPortfolioProperties;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.TeamStatusDict;
import com.jxc.wefolio.dict.VisitEventTypeDict;
import com.jxc.wefolio.dto.VisitorAvatarUploadTicketRequest;
import com.jxc.wefolio.dto.VisitorAvatarUploadTicketResponse;
import com.jxc.wefolio.dto.VisitorProfileUpdateRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamContactLeadSubmitRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioRenderDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryResponse;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioEventRequest;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioOpenRequest;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioResponse;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.entity.VisitorEntity;
import com.jxc.wefolio.exception.AuthenticationRequiredException;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.message.TeamPortfolioMessage;
import com.jxc.wefolio.service.VisitorAuthTokenService;
import com.jxc.wefolio.service.VisitorService;
import com.jxc.wefolio.service.PointBalanceGateService;
import com.jxc.wefolio.service.PortfolioOpenPerformanceLogger;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentService;
import com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 标准团队作品集访客端业务编排服务。
 */
@Service
@RequiredArgsConstructor
public class VisitorTeamPortfolioService {

    /** 维护遮罩固定使用的中性浅色主题。 */
    private static final String MAINTENANCE_THEME_MODE = "light";

    /** 积分非正维护原因。 */
    private static final String POINT_BALANCE_NON_POSITIVE = "POINT_BALANCE_NON_POSITIVE";

    /** 查询单条作品集限制。 */
    private static final String QUERY_LIMIT_ONE = "LIMIT 1";

    /** 统一不可访问提示。 */
    private static final String PORTFOLIO_UNAVAILABLE_MESSAGE = "当前作品集暂未开放访问";

    /** 默认团队作品集标题。 */
    private static final String DEFAULT_TITLE = "团队作品集";

    /** 朋友圈单页匿名身份作用域前缀。 */
    private static final String TIMELINE_ANONYMOUS_SCOPE_PREFIX = "TEAM:";

    /** 功能开关配置。 */
    private final TeamPortfolioProperties teamPortfolioProperties;

    /** 作品集数据访问器。 */
    private final PortfolioEntityMapper portfolioEntityMapper;

    /** 团队数据访问器。 */
    private final TeamEntityMapper teamEntityMapper;

    /** 团队作品集渲染服务。 */
    private final TeamPortfolioRenderService teamPortfolioRenderService;

    /** 团队档期查询组件服务。 */
    private final TeamScheduleQueryComponentService teamScheduleQueryComponentService;

    /** 团队预留联系信息组件服务。 */
    private final TeamContactFormComponentService teamContactFormComponentService;

    /** 团队访问与事件服务。 */
    private final TeamPortfolioVisitService teamPortfolioVisitService;

    /** 共享访客身份与资料服务。 */
    private final VisitorService visitorService;

    /** 共享访客登录令牌服务。 */
    private final VisitorAuthTokenService visitorAuthTokenService;

    /** 团队拥有者实际可用积分门禁。 */
    private final PointBalanceGateService pointBalanceGateService;

    /** 作品集打开分段耗时日志器。 */
    private final PortfolioOpenPerformanceLogger portfolioOpenPerformanceLogger;

    /**
     * 打开已发布标准团队作品集。
     *
     * @param shareCode 分享编码
     * @param request 打开请求
     * @return 团队访客响应
     */
    public VisitorTeamPortfolioResponse openPortfolio(
            String shareCode,
            VisitorTeamPortfolioOpenRequest request
    ) {
        PortfolioOpenPerformanceLogger.Trace trace = portfolioOpenPerformanceLogger.start(
                PortfolioOpenPerformanceLogger.PortfolioType.TEAM);
        Throwable failure = null;
        try {
            PublishedPortfolio published = trace.measure(
                    PortfolioOpenPerformanceLogger.Phase.PORTFOLIO_LOOKUP,
                    () -> requirePublishedTeamPortfolio(shareCode));
            boolean nonPositive = trace.measure(
                    PortfolioOpenPerformanceLogger.Phase.POINT_GATE,
                    () -> pointBalanceGateService.isNonPositive(published.team().getOwnerUserId()));
            if (nonPositive) {
                VisitorTeamPortfolioResponse maintenanceResponse = trace.measure(
                        PortfolioOpenPerformanceLogger.Phase.RENDER,
                        () -> buildMaintenanceResponse(published));
                trace.outcome(PortfolioOpenPerformanceLogger.Outcome.MAINTENANCE);
                return maintenanceResponse;
            }
            // 匿名身份按数据库 ID 聚合，避免分享码变更后产生新的访客身份记录。
            VisitorService.VisitorSession session = visitorService.resolveForOpen(
                    request == null ? null : request.getLoginCode(),
                    request == null ? null : request.getAnonymousSessionId(),
                    TIMELINE_ANONYMOUS_SCOPE_PREFIX + published.portfolio().getId(),
                    trace);
            VisitorEntity visitor = session == null ? null : session.visitor();
            if (visitor == null || visitor.getId() == null || visitor.getId() <= 0
                    || !hasText(visitor.getVisitorKey())) {
                throw new BusinessException(PORTFOLIO_UNAVAILABLE_MESSAGE);
            }
            TeamPortfolioRenderDto render = trace.measure(
                    PortfolioOpenPerformanceLogger.Phase.RENDER,
                    () -> teamPortfolioRenderService.render(
                            published.portfolio().getPublishedConfigJson(), published.componentContext()));
            VisitRecordEntity visitRecord = trace.measure(
                    PortfolioOpenPerformanceLogger.Phase.VISIT_WRITE,
                    () -> teamPortfolioVisitService.recordOpen(
                            published.portfolio(), visitor.getId(), visitor.getVisitorKey(),
                            request == null ? null : request.getSourceType(),
                            request == null ? null : request.getIdempotencyKey()));
            fillRenderContext(render, published, visitRecord);
            VisitorTeamPortfolioResponse response = buildOpenResponse(published, session, visitRecord, render);
            // 匿名令牌按分享码绑定，可由鉴权切面直接与当前 URL 比对，无需再次查询作品集。
            VisitorAuthTokenService.VisitorLoginToken loginToken = session.anonymous()
                    ? visitorAuthTokenService.issueTimelineAnonymousToken(
                            visitor.getId(),
                            visitor.getVisitorKey(),
                            TIMELINE_ANONYMOUS_SCOPE_PREFIX + published.portfolio().getShareCode())
                    : visitorAuthTokenService.issueToken(visitor.getId(), visitor.getVisitorKey());
            response.setTokenType(loginToken.tokenType());
            response.setToken(loginToken.token());
            response.setExpiresInSeconds(loginToken.expiresInSeconds());
            if (response.isNeedVisitorProfile()) {
                response.setVisitorProfileToken(visitorService.createProfileToken(
                        visitor.getId(), published.portfolio().getId(), visitRecord.getId()));
            }
            trace.outcome(PortfolioOpenPerformanceLogger.Outcome.SUCCESS);
            return response;
        } catch (RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            trace.finish(failure);
        }
    }

    /**
     * 创建共享访客头像上传票据。
     */
    public VisitorAvatarUploadTicketResponse createVisitorAvatarUploadTicket(
            String shareCode,
            VisitorAvatarUploadTicketRequest request
    ) {
        PortfolioEntity portfolio = requirePublishedTeamPortfolio(shareCode).portfolio();
        return visitorService.createAvatarUploadTicket(portfolio.getId(), request);
    }

    /**
     * 更新共享访客头像昵称。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateVisitorProfile(String shareCode, VisitorProfileUpdateRequest request) {
        PortfolioEntity portfolio = requirePublishedTeamPortfolio(shareCode).portfolio();
        visitorService.saveProfile(portfolio.getId(), request);
    }

    /**
     * 记录已认证团队访客事件。
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordEvent(String shareCode, VisitorTeamPortfolioEventRequest request) {
        PublishedPortfolio published = requirePublishedTeamPortfolio(shareCode);
        if (request != null && VisitEventTypeDict.CONTACT_FORM_EXPOSED.getCode()
                .equals(request.getEventType())) {
            teamContactFormComponentService.validatePublishedComponent(
                    published.portfolio(), request.getComponentKey());
        }
        VisitorContext visitor = requireVisitorContext();
        teamPortfolioVisitService.recordEvent(
                published.portfolio(), visitor.getVisitorId(), visitor.getVisitorKey(), request);
    }

    /**
     * 查询已发布团队档期组件选项，不创建访问、事件或查档快照。
     */
    public JSONObject queryScheduleOptions(String shareCode, String componentKey) {
        PublishedPortfolio published = requirePublishedTeamPortfolio(shareCode);
        return teamScheduleQueryComponentService.previewOptions(
                published.componentContext(), published.config(), componentKey);
    }

    /**
     * 提交已发布团队作品集查档。
     */
    @Transactional(rollbackFor = Exception.class)
    public TeamPortfolioScheduleQueryResponse submitScheduleQuery(
            String shareCode,
            TeamPortfolioScheduleQueryRequest request
    ) {
        PublishedPortfolio published = requirePublishedTeamPortfolio(shareCode);
        VisitorContext visitor = requireVisitorContext();
        TeamPortfolioVisitService.EventRecordResult eventResult =
                teamPortfolioVisitService.recordScheduleQuery(
                        published.portfolio(), visitor.getVisitorId(), visitor.getVisitorKey(), request);
        TeamScheduleQueryComponentService.PublishedQueryRecordContext recordContext = eventResult.recorded()
                ? TeamScheduleQueryComponentService.PublishedQueryRecordContext.recordable(
                        eventResult.visitRecord().getId(),
                        eventResult.visitRecord().getSourceType(),
                        eventResult.occurredAt())
                : TeamScheduleQueryComponentService.PublishedQueryRecordContext.skipped();
        return teamScheduleQueryComponentService.queryPublished(
                published.portfolio(), published.config(), request, visitor, recordContext);
    }

    /**
     * 提交团队预留联系信息并幂等记录成功事件。
     */
    @Transactional(rollbackFor = Exception.class)
    public TeamContactFormComponentService.SubmitResult submitContactLead(
            String shareCode,
            TeamContactLeadSubmitRequest request
    ) {
        PublishedPortfolio published = requirePublishedTeamPortfolio(shareCode);
        VisitorContext visitor = requireVisitorContext();
        TeamContactFormComponentService.SubmitResult result =
                teamContactFormComponentService.submit(published.portfolio().getShareCode(), request, visitor);
        teamPortfolioVisitService.recordContactLeadSubmitted(
                published.portfolio(), visitor.getVisitorId(), visitor.getVisitorKey(),
                result.leadId(), request == null ? null : request.getIdempotencyKey());
        return result;
    }

    /**
     * 在读取配置前通过 SQL 与 Java 双重校验发布身份和有效团队。
     */
    PublishedPortfolio requirePublishedTeamPortfolio(String shareCode) {
        if (!teamPortfolioProperties.isEnabled()) {
            throw new BusinessException(TeamPortfolioMessage.FEATURE_DISABLED);
        }
        String normalizedShareCode = normalizeShareCode(shareCode);
        PortfolioEntity portfolio = portfolioEntityMapper.selectOne(
                Wrappers.lambdaQuery(PortfolioEntity.class)
                        .eq(PortfolioEntity::getShareCode, normalizedShareCode)
                        .eq(PortfolioEntity::getOwnerType, PortfolioOwnerTypeDict.TEAM.getCode())
                        .eq(PortfolioEntity::getTemplateType, PortfolioTemplateTypeDict.STANDARD.getCode())
                        .eq(PortfolioEntity::getSchemaVersion,
                                TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1)
                        .eq(PortfolioEntity::getPublicationStatus,
                                PortfolioPublicationStatusDict.PUBLISHED.getCode())
                        .eq(PortfolioEntity::getStatus, PortfolioStatusDict.ACTIVE.getCode())
                        .eq(PortfolioEntity::getDeleted, 0L)
                        .last(QUERY_LIMIT_ONE));
        if (!isPublishedTeamPortfolio(portfolio, normalizedShareCode)) {
            throw new BusinessException(PORTFOLIO_UNAVAILABLE_MESSAGE);
        }
        TeamEntity team = teamEntityMapper.selectById(portfolio.getOwnerId());
        if (team == null || !Objects.equals(team.getId(), portfolio.getOwnerId())
                || !TeamStatusDict.ACTIVE.getCode().equals(team.getStatus())) {
            throw new BusinessException(PORTFOLIO_UNAVAILABLE_MESSAGE);
        }
        TeamPortfolioConfigDto config = parsePublishedConfig(portfolio.getPublishedConfigJson());
        return new PublishedPortfolio(
                portfolio,
                team,
                config,
                new TeamPortfolioComponentContext(
                        portfolio.getOwnerId(), portfolio.getId(), portfolio.getPublishedRevision()));
    }

    /** Java 层复验全部团队作品集身份字段。 */
    private boolean isPublishedTeamPortfolio(PortfolioEntity portfolio, String shareCode) {
        return portfolio != null
                && portfolio.getId() != null && portfolio.getId() > 0
                && portfolio.getOwnerId() != null && portfolio.getOwnerId() > 0
                && Objects.equals(portfolio.getShareCode(), shareCode)
                && PortfolioOwnerTypeDict.TEAM.getCode().equals(portfolio.getOwnerType())
                && PortfolioTemplateTypeDict.STANDARD.getCode().equals(portfolio.getTemplateType())
                && TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1.equals(portfolio.getSchemaVersion())
                && PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())
                && PortfolioStatusDict.ACTIVE.getCode().equals(portfolio.getStatus())
                && Objects.equals(portfolio.getDeleted(), 0L)
                && portfolio.getPublishedRevision() != null && portfolio.getPublishedRevision() > 0
                && hasText(portfolio.getPublishedConfigJson());
    }

    /** 解析并复验已发布团队配置。 */
    private TeamPortfolioConfigDto parsePublishedConfig(String configJson) {
        try {
            TeamPortfolioConfigDto config = JSON.parseObject(configJson, TeamPortfolioConfigDto.class);
            if (config == null || !TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1
                    .equals(config.getSchemaVersion())) {
                throw new BusinessException(PORTFOLIO_UNAVAILABLE_MESSAGE);
            }
            return config;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(PORTFOLIO_UNAVAILABLE_MESSAGE, exception);
        }
    }

    /** 构建团队访客打开响应。 */
    private VisitorTeamPortfolioResponse buildOpenResponse(
            PublishedPortfolio published,
            VisitorService.VisitorSession session,
            VisitRecordEntity visitRecord,
            TeamPortfolioRenderDto render
    ) {
        VisitorEntity visitor = session.visitor();
        VisitorTeamPortfolioResponse response = new VisitorTeamPortfolioResponse();
        response.setShareCode(published.portfolio().getShareCode());
        response.setPortfolioId(published.portfolio().getId());
        response.setTeamId(published.team().getId());
        response.setTeamName(published.team().getName());
        response.setPublishedRevision(published.portfolio().getPublishedRevision());
        response.setTitle(resolveTitle(published.config()));
        response.setConfig(published.config());
        response.setRenderData(render);
        response.setVisitRecordId(visitRecord.getId());
        response.setVisitorKey(visitor.getVisitorKey());
        response.setNewVisitor(session.newVisitor());
        response.setNeedVisitorProfile(!session.anonymous()
                && (!hasText(visitor.getNickname()) || !hasText(visitor.getAvatarUrl())));
        return response;
    }

    /** 构建不创建访客会话、不记录打开事件的维护遮罩响应。 */
    private VisitorTeamPortfolioResponse buildMaintenanceResponse(PublishedPortfolio published) {
        TeamPortfolioRenderDto render = teamPortfolioRenderService.render(
                published.portfolio().getPublishedConfigJson(), published.componentContext());
        render.setShareCode(published.portfolio().getShareCode());
        render.setPortfolioId(published.portfolio().getId());
        render.setTeamId(published.team().getId());
        render.setTeamName(published.team().getName());
        render.setPreview(false);
        render.setUnderMaintenance(true);
        render.setVisitRecordId(null);
        TeamPortfolioRenderDto.Style maintenanceStyle = new TeamPortfolioRenderDto.Style();
        maintenanceStyle.setBackgroundColor(TeamPortfolioConfigDto.DEFAULT_BACKGROUND_COLOR);
        maintenanceStyle.setThemeMode(MAINTENANCE_THEME_MODE);
        render.setStyle(maintenanceStyle);
        render.setComponents(List.of());
        TeamPortfolioRenderDto.BottomNav maintenanceBottomNav = new TeamPortfolioRenderDto.BottomNav();
        maintenanceBottomNav.setEnabled(false);
        maintenanceBottomNav.setItems(List.of());
        render.setBottomNav(maintenanceBottomNav);
        VisitorTeamPortfolioResponse response = new VisitorTeamPortfolioResponse();
        response.setShareCode(published.portfolio().getShareCode());
        response.setPortfolioId(published.portfolio().getId());
        response.setTeamId(published.team().getId());
        response.setTeamName(published.team().getName());
        response.setPublishedRevision(published.portfolio().getPublishedRevision());
        response.setTitle(resolveTitle(published.config()));
        response.setUnderMaintenance(true);
        response.setMaintenanceReason(POINT_BALANCE_NON_POSITIVE);
        response.setRenderData(render);
        return response;
    }

    /** 填充团队访客渲染上下文。 */
    private void fillRenderContext(
            TeamPortfolioRenderDto render,
            PublishedPortfolio published,
            VisitRecordEntity visitRecord
    ) {
        if (render == null) {
            throw new BusinessException(PORTFOLIO_UNAVAILABLE_MESSAGE);
        }
        render.setShareCode(published.portfolio().getShareCode());
        render.setPortfolioId(published.portfolio().getId());
        render.setTeamId(published.team().getId());
        render.setTeamName(published.team().getName());
        render.setPreview(false);
        render.setUnderMaintenance(false);
        render.setVisitRecordId(visitRecord.getId());
    }

    /** 获取当前认证访客上下文。 */
    private VisitorContext requireVisitorContext() {
        return VisitorContextHolder.current()
                .orElseThrow(() -> new AuthenticationRequiredException("访客未登录"));
    }

    /** 解析团队作品集标题。 */
    private String resolveTitle(TeamPortfolioConfigDto config) {
        String title = config == null || config.getShare() == null ? null : config.getShare().getTitle();
        return hasText(title) ? title.strip() : DEFAULT_TITLE;
    }

    /** 规范化分享编码。 */
    private String normalizeShareCode(String shareCode) {
        if (!hasText(shareCode)) {
            throw new BusinessException(PORTFOLIO_UNAVAILABLE_MESSAGE);
        }
        return shareCode.strip();
    }

    /** 判断文本是否非空。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 已校验的发布团队作品集上下文。
     */
    record PublishedPortfolio(
            PortfolioEntity portfolio,
            TeamEntity team,
            TeamPortfolioConfigDto config,
            TeamPortfolioComponentContext componentContext
    ) {
    }
}
