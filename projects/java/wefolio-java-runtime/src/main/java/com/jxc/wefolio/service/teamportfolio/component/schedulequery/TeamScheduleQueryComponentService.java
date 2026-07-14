package com.jxc.wefolio.service.teamportfolio.component.schedulequery;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.common.auth.VisitorContext;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.PortfolioTypeDict;
import com.jxc.wefolio.dict.ScheduleStatusDict;
import com.jxc.wefolio.dict.SlotDefinitionStatusDict;
import com.jxc.wefolio.dict.TeamScheduleMemberStatusDict;
import com.jxc.wefolio.dict.TeamScheduleResultStatusDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.VisitSourceTypeDict;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryResponse;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.ScheduleEntity;
import com.jxc.wefolio.entity.SlotDefinitionEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.TeamScheduleQueryRecordEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.ScheduleEntityMapper;
import com.jxc.wefolio.mapper.SlotDefinitionEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.TeamScheduleQueryRecordEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 团队档期查询组件服务。
 */
@Service
public class TeamScheduleQueryComponentService {

    /** 查询范围配置键。 */
    private static final String CONFIG_KEY_QUERY_RANGE = "queryRange";

    /** 展示方式配置键。 */
    private static final String CONFIG_KEY_DISPLAY_MODE = "displayMode";

    /** 查询范围类型配置键。 */
    private static final String CONFIG_KEY_TYPE = "type";

    /** 未来天数配置键。 */
    private static final String CONFIG_KEY_FUTURE_DAYS = "futureDays";

    /** 开始日期配置键。 */
    private static final String CONFIG_KEY_START_DATE = "startDate";

    /** 结束日期配置键。 */
    private static final String CONFIG_KEY_END_DATE = "endDate";

    /** 不限制查询范围。 */
    private static final String QUERY_RANGE_UNLIMITED = "UNLIMITED";

    /** 限制未来天数查询范围。 */
    private static final String QUERY_RANGE_FUTURE_DAYS = "FUTURE_DAYS";

    /** 限制固定日期查询范围。 */
    private static final String QUERY_RANGE_DATE_RANGE = "DATE_RANGE";

    /** 作品集分享字段。 */
    private static final String CONFIG_KEY_SHARE = "share";

    /** 作品集标题字段。 */
    private static final String CONFIG_KEY_TITLE = "title";

    /** 查询日期必填提示。 */
    private static final String QUERY_DATE_REQUIRED_MESSAGE = "查询日期不能为空";

    /** 查询组件键必填提示。 */
    private static final String COMPONENT_KEY_REQUIRED_MESSAGE = "档期查询组件不能为空";

    /** 档期查询组件类型。 */
    private static final String COMPONENT_TYPE_SCHEDULE_QUERY = "SCHEDULE_QUERY";

    /** 预览组件不存在或不可用提示。 */
    private static final String PREVIEW_COMPONENT_UNAVAILABLE_MESSAGE = "档期查询组件不存在或不可用";

    /** 查询日期超出范围提示。 */
    private static final String QUERY_DATE_OUT_OF_RANGE_MESSAGE = "查询日期不在允许范围内";

    /** 查询上下文不合法提示。 */
    private static final String QUERY_CONTEXT_INVALID_MESSAGE = "团队档期查询上下文不正确";

    /** 已发布作品集不合法提示。 */
    private static final String PUBLISHED_PORTFOLIO_INVALID_MESSAGE = "已发布团队作品集不可用";

    /** 访客上下文不合法提示。 */
    private static final String VISITOR_CONTEXT_INVALID_MESSAGE = "团队档期查询访客上下文不正确";

    /** 发布记录上下文不合法提示。 */
    private static final String RECORD_CONTEXT_INVALID_MESSAGE = "团队档期查询记录上下文不正确";

    /** 幂等键不合法提示。 */
    private static final String IDEMPOTENCY_KEY_INVALID_MESSAGE = "档期查询幂等键不正确";

    /** 查询记录写入失败提示。 */
    private static final String RECORD_INSERT_FAILED_MESSAGE = "团队档期查询记录写入失败";

    /** 数据库访客键最大长度。 */
    private static final int VISITOR_KEY_MAX_LENGTH = 64;

    /** 事件幂等键最大长度。 */
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 64;

    /** 作品集标题最大 Unicode 字符数。 */
    private static final int PORTFOLIO_TITLE_MAX_LENGTH = 100;

    /** 团队成员 Mapper。 */
    private final TeamMemberEntityMapper teamMemberEntityMapper;

    /** 用户 Mapper。 */
    private final UserEntityMapper userEntityMapper;

    /** 档位定义 Mapper。 */
    private final SlotDefinitionEntityMapper slotDefinitionEntityMapper;

    /** 档期 Mapper。 */
    private final ScheduleEntityMapper scheduleEntityMapper;

    /** 团队查档记录 Mapper。 */
    private final TeamScheduleQueryRecordEntityMapper teamScheduleQueryRecordEntityMapper;

    /** 团队档期查询组件渲染器。 */
    private final TeamScheduleQueryComponentRenderer renderer;

    /** 统一业务时钟。 */
    private final Clock clock;

    /**
     * 创建 Spring 生产服务并使用系统默认时区时钟。
     *
     * @param teamMemberEntityMapper 团队成员 Mapper
     * @param userEntityMapper 用户 Mapper
     * @param slotDefinitionEntityMapper 档位定义 Mapper
     * @param scheduleEntityMapper 档期 Mapper
     * @param teamScheduleQueryRecordEntityMapper 团队查档记录 Mapper
     * @param renderer 团队档期查询组件渲染器
     */
    @Autowired
    public TeamScheduleQueryComponentService(
            TeamMemberEntityMapper teamMemberEntityMapper,
            UserEntityMapper userEntityMapper,
            SlotDefinitionEntityMapper slotDefinitionEntityMapper,
            ScheduleEntityMapper scheduleEntityMapper,
            TeamScheduleQueryRecordEntityMapper teamScheduleQueryRecordEntityMapper,
            TeamScheduleQueryComponentRenderer renderer
    ) {
        this(teamMemberEntityMapper, userEntityMapper, slotDefinitionEntityMapper, scheduleEntityMapper,
                teamScheduleQueryRecordEntityMapper, renderer, Clock.systemDefaultZone());
    }

    /**
     * 创建使用指定时钟的可测试服务。
     *
     * @param teamMemberEntityMapper 团队成员 Mapper
     * @param userEntityMapper 用户 Mapper
     * @param slotDefinitionEntityMapper 档位定义 Mapper
     * @param scheduleEntityMapper 档期 Mapper
     * @param teamScheduleQueryRecordEntityMapper 团队查档记录 Mapper
     * @param renderer 团队档期查询组件渲染器
     * @param clock 统一业务时钟
     */
    TeamScheduleQueryComponentService(
            TeamMemberEntityMapper teamMemberEntityMapper,
            UserEntityMapper userEntityMapper,
            SlotDefinitionEntityMapper slotDefinitionEntityMapper,
            ScheduleEntityMapper scheduleEntityMapper,
            TeamScheduleQueryRecordEntityMapper teamScheduleQueryRecordEntityMapper,
            TeamScheduleQueryComponentRenderer renderer,
            Clock clock
    ) {
        this.teamMemberEntityMapper = teamMemberEntityMapper;
        this.userEntityMapper = userEntityMapper;
        this.slotDefinitionEntityMapper = slotDefinitionEntityMapper;
        this.scheduleEntityMapper = scheduleEntityMapper;
        this.teamScheduleQueryRecordEntityMapper = teamScheduleQueryRecordEntityMapper;
        this.renderer = renderer;
        this.clock = clock;
    }

    /**
     * 执行维护预览查档，不写入团队查档记录。
     *
     * @param context 团队组件上下文
     * @param normalizedConfig 规范化组件配置
     * @param request 查档请求
     * @return 团队查档聚合结果
     */
    public TeamPortfolioScheduleQueryResponse queryPreview(
            TeamPortfolioComponentContext context,
            JSONObject normalizedConfig,
            TeamPortfolioScheduleQueryRequest request
    ) {
        validateQuery(context, normalizedConfig, request, false);
        return aggregate(context.teamId(), request.getQueriedDate());
    }

    /**
     * 从顶层团队配置定位档期组件并生成维护预览选项。
     *
     * @param context 团队组件上下文
     * @param portfolioConfig 顶层团队作品集配置
     * @param componentKey 组件实例键
     * @return 重新校验后的档期组件选项
     */
    public JSONObject previewOptions(
            TeamPortfolioComponentContext context,
            TeamPortfolioConfigDto portfolioConfig,
            String componentKey
    ) {
        TeamPortfolioConfigDto.ComponentEnvelope component = requirePreviewComponent(
                portfolioConfig, componentKey);
        return renderer.render(component.getConfig(), context);
    }

    /**
     * 从顶层团队配置定位档期组件并执行维护预览查档。
     *
     * @param context 团队组件上下文
     * @param portfolioConfig 顶层团队作品集配置
     * @param request 查档请求
     * @return 团队查档聚合结果
     */
    public TeamPortfolioScheduleQueryResponse queryPreview(
            TeamPortfolioComponentContext context,
            TeamPortfolioConfigDto portfolioConfig,
            TeamPortfolioScheduleQueryRequest request
    ) {
        TeamPortfolioConfigDto.ComponentEnvelope component = requirePreviewComponent(
                portfolioConfig, request == null ? null : request.getComponentKey());
        return queryPreview(context, component.getConfig(), request);
    }

    /**
     * 定位已启用且配置完整的档期查询组件。
     */
    private TeamPortfolioConfigDto.ComponentEnvelope requirePreviewComponent(
            TeamPortfolioConfigDto portfolioConfig,
            String componentKey
    ) {
        if (portfolioConfig == null
                || !TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1.equals(
                        portfolioConfig.getSchemaVersion())
                || !hasText(componentKey) || portfolioConfig.getComponents() == null) {
            throw new BusinessException(PREVIEW_COMPONENT_UNAVAILABLE_MESSAGE);
        }
        return portfolioConfig.getComponents().stream()
                .filter(Objects::nonNull)
                .filter(component -> componentKey.equals(component.getComponentKey()))
                .filter(component -> Boolean.TRUE.equals(component.getEnabled()))
                .filter(component -> COMPONENT_TYPE_SCHEDULE_QUERY.equals(component.getComponentType()))
                .filter(component -> component.getConfig() != null)
                .findFirst()
                .orElseThrow(() -> new BusinessException(PREVIEW_COMPONENT_UNAVAILABLE_MESSAGE));
    }

    /**
     * 从顶层已发布配置定位档期组件并执行访客查档。
     *
     * @param portfolio 已发布团队作品集
     * @param portfolioConfig 顶层已发布团队配置
     * @param request 查档请求
     * @param visitor 访客上下文
     * @param recordContext 访问事件产生的快照记录上下文
     * @return 团队查档聚合结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TeamPortfolioScheduleQueryResponse queryPublished(
            PortfolioEntity portfolio,
            TeamPortfolioConfigDto portfolioConfig,
            TeamPortfolioScheduleQueryRequest request,
            VisitorContext visitor,
            PublishedQueryRecordContext recordContext
    ) {
        TeamPortfolioConfigDto.ComponentEnvelope component = requirePreviewComponent(
                portfolioConfig, request == null ? null : request.getComponentKey());
        return queryPublished(portfolio, component.getConfig(), request, visitor, recordContext);
    }

    /**
     * 执行已发布团队作品集查档，并写入独立团队查档记录。
     *
     * @param portfolio 已发布团队作品集
     * @param normalizedConfig 规范化组件配置
     * @param request 查档请求
     * @param visitor 访客上下文
     * @param recordContext 访问事件产生的快照记录上下文
     * @return 团队查档聚合结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TeamPortfolioScheduleQueryResponse queryPublished(
            PortfolioEntity portfolio,
            JSONObject normalizedConfig,
            TeamPortfolioScheduleQueryRequest request,
            VisitorContext visitor,
            PublishedQueryRecordContext recordContext
    ) {
        String portfolioTitle = validatePublishedContext(portfolio, visitor, recordContext);
        int revision = portfolio.getPublishedRevision();
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(portfolio.getOwnerId(), portfolio.getId(), revision);
        JSONObject renderedConfig = validateQuery(context, normalizedConfig, request, true);
        TeamPortfolioScheduleQueryResponse response = aggregate(context.teamId(), request.getQueriedDate());
        if (recordContext.snapshotRecordable()) {
            persistRecord(portfolio, portfolioTitle, renderedConfig, request, visitor, recordContext, response);
        }
        return response;
    }

    /**
     * 校验已发布作品集、访客和访问事件上下文，并解析标题快照。
     */
    private String validatePublishedContext(
            PortfolioEntity portfolio,
            VisitorContext visitor,
            PublishedQueryRecordContext recordContext
    ) {
        if (portfolio == null || portfolio.getId() == null || portfolio.getId() <= 0
                || portfolio.getOwnerId() == null || portfolio.getOwnerId() <= 0
                || !PortfolioOwnerTypeDict.TEAM.getCode().equals(portfolio.getOwnerType())
                || !PortfolioTemplateTypeDict.STANDARD.getCode().equals(portfolio.getTemplateType())
                || !PortfolioStatusDict.ACTIVE.getCode().equals(portfolio.getStatus())
                || !TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1.equals(portfolio.getSchemaVersion())
                || !PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())
                || portfolio.getPublishedRevision() == null || portfolio.getPublishedRevision() <= 0) {
            throw new BusinessException(PUBLISHED_PORTFOLIO_INVALID_MESSAGE);
        }
        if (visitor == null || !hasText(visitor.getVisitorKey())
                || visitor.getVisitorKey().length() > VISITOR_KEY_MAX_LENGTH
                || visitor.getVisitorId() != null && visitor.getVisitorId() <= 0) {
            throw new BusinessException(VISITOR_CONTEXT_INVALID_MESSAGE);
        }
        if (recordContext == null || recordContext.snapshotRecordable()
                && (recordContext.visitRecordId() == null
                || recordContext.visitRecordId() <= 0
                || recordContext.occurredAt() == null)) {
            throw new BusinessException(RECORD_CONTEXT_INVALID_MESSAGE);
        }
        return resolvePortfolioTitle(portfolio);
    }

    /**
     * 在执行数据库查询前重新严格校验组件配置和查询请求。
     *
     * @param context 团队组件上下文
     * @param normalizedConfig 组件配置
     * @param request 查询请求
     * @return 重新渲染的配置快照
     */
    private JSONObject validateQuery(
            TeamPortfolioComponentContext context,
            JSONObject normalizedConfig,
            TeamPortfolioScheduleQueryRequest request,
            boolean requireIdempotencyKey
    ) {
        if (context == null || context.teamId() <= 0 || context.portfolioId() <= 0) {
            throw new BusinessException(QUERY_CONTEXT_INVALID_MESSAGE);
        }
        if (request == null || request.getQueriedDate() == null) {
            throw new BusinessException(QUERY_DATE_REQUIRED_MESSAGE);
        }
        if (!hasText(request.getComponentKey())) {
            throw new BusinessException(COMPONENT_KEY_REQUIRED_MESSAGE);
        }
        if (requireIdempotencyKey && (!hasText(request.getIdempotencyKey())
                || request.getIdempotencyKey().length() > IDEMPOTENCY_KEY_MAX_LENGTH)) {
            throw new BusinessException(IDEMPOTENCY_KEY_INVALID_MESSAGE);
        }
        JSONObject renderedConfig = renderer.render(normalizedConfig, context);
        validateQueriedDateRange(request.getQueriedDate(), renderedConfig.getJSONObject(CONFIG_KEY_QUERY_RANGE));
        return renderedConfig;
    }

    /**
     * 校验查询日期是否处在组件允许范围。
     *
     * @param queriedDate 查询日期
     * @param range 查询范围配置
     */
    private void validateQueriedDateRange(LocalDate queriedDate, JSONObject range) {
        String type = range == null ? QUERY_RANGE_UNLIMITED : range.getString(CONFIG_KEY_TYPE);
        if (QUERY_RANGE_UNLIMITED.equals(type)) {
            return;
        }
        if (QUERY_RANGE_FUTURE_DAYS.equals(type)) {
            Integer futureDays = range.getInteger(CONFIG_KEY_FUTURE_DAYS);
            LocalDate today = LocalDate.now(clock);
            if (futureDays == null || queriedDate.isBefore(today) || queriedDate.isAfter(today.plusDays(futureDays))) {
                throw new BusinessException(QUERY_DATE_OUT_OF_RANGE_MESSAGE);
            }
            return;
        }
        if (QUERY_RANGE_DATE_RANGE.equals(type)) {
            LocalDate startDate = parseDate(range.getString(CONFIG_KEY_START_DATE));
            LocalDate endDate = parseDate(range.getString(CONFIG_KEY_END_DATE));
            if (queriedDate.isBefore(startDate) || queriedDate.isAfter(endDate)) {
                throw new BusinessException(QUERY_DATE_OUT_OF_RANGE_MESSAGE);
            }
            return;
        }
        throw new BusinessException(QUERY_DATE_OUT_OF_RANGE_MESSAGE);
    }

    /**
     * 聚合当前有效团队成员的档期。
     *
     * @param teamId 团队 ID
     * @param queriedDate 查询日期
     * @return 团队查档结果
     */
    private TeamPortfolioScheduleQueryResponse aggregate(long teamId, LocalDate queriedDate) {
        List<UserEntity> activeMembers = loadActiveMembers(teamId);
        if (activeMembers.isEmpty()) {
            return buildResponse(queriedDate, List.of());
        }
        LinkedHashSet<Long> memberUserIds = activeMembers.stream()
                .map(UserEntity::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<SlotDefinitionEntity> slots = safeList(slotDefinitionEntityMapper.selectActiveByUserIds(memberUserIds));
        List<ScheduleEntity> schedules = safeList(scheduleEntityMapper.selectByUserIdsAndDate(memberUserIds, queriedDate));
        Map<Long, List<SlotDefinitionEntity>> slotsByUser = groupValidSlots(slots, memberUserIds);
        Map<ScheduleKey, Boolean> unavailableScheduleByKey = groupUnavailableSchedules(schedules, memberUserIds, queriedDate);
        List<TeamPortfolioScheduleQueryResponse.Member> members = new ArrayList<>();
        for (UserEntity activeMember : activeMembers) {
            long userId = activeMember.getId();
            List<SlotDefinitionEntity> memberSlots = slotsByUser.getOrDefault(userId, List.of());
            int availableSlotCount = 0;
            for (SlotDefinitionEntity slot : memberSlots) {
                if (!Boolean.TRUE.equals(unavailableScheduleByKey.get(new ScheduleKey(userId, slot.getId())))) {
                    availableSlotCount++;
                }
            }
            members.add(buildMember(activeMember, memberSlots.size(), availableSlotCount));
        }
        return buildResponse(queriedDate, members);
    }

    /**
     * 读取已加入且账号正常的团队成员，并以成员关系主键稳定排序。
     *
     * @param teamId 团队 ID
     * @return 有效成员列表
     */
    private List<UserEntity> loadActiveMembers(long teamId) {
        List<TeamMemberEntity> memberships = safeList(teamMemberEntityMapper.selectList(
                Wrappers.lambdaQuery(TeamMemberEntity.class)
                        .eq(TeamMemberEntity::getTeamId, teamId)
                        .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
                        .orderByAsc(TeamMemberEntity::getId)
        ));
        List<TeamMemberEntity> validMemberships = memberships.stream()
                .filter(member -> member != null && Objects.equals(member.getTeamId(), teamId)
                        && member.getId() != null && member.getUserId() != null
                        && JoinStatusDict.JOINED.getCode().equals(member.getJoinStatus()))
                .sorted(Comparator.comparing(TeamMemberEntity::getId))
                .toList();
        LinkedHashSet<Long> requestedUserIds = validMemberships.stream()
                .map(TeamMemberEntity::getUserId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (requestedUserIds.isEmpty()) {
            return List.of();
        }
        Map<Long, UserEntity> activeUsers = new LinkedHashMap<>();
        for (UserEntity user : safeList(userEntityMapper.selectBatchIds(requestedUserIds))) {
            if (user != null && user.getId() != null && requestedUserIds.contains(user.getId())
                    && UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
                activeUsers.putIfAbsent(user.getId(), user);
            }
        }
        Set<Long> seenUserIds = new LinkedHashSet<>();
        List<UserEntity> result = new ArrayList<>();
        for (TeamMemberEntity membership : validMemberships) {
            UserEntity user = activeUsers.get(membership.getUserId());
            if (user != null && seenUserIds.add(user.getId())) {
                result.add(user);
            }
        }
        return result;
    }

    /**
     * 按成员分组有效档位，并过滤空行、错归属行和重复档位。
     *
     * @param slots Mapper 返回的档位
     * @param memberUserIds 有效成员用户 ID 集合
     * @return 用户到档位列表映射
     */
    private Map<Long, List<SlotDefinitionEntity>> groupValidSlots(
            List<SlotDefinitionEntity> slots,
            Set<Long> memberUserIds
    ) {
        Map<Long, LinkedHashMap<Long, SlotDefinitionEntity>> mutable = new LinkedHashMap<>();
        for (SlotDefinitionEntity slot : slots) {
            if (slot == null || slot.getId() == null || slot.getUserId() == null
                    || !memberUserIds.contains(slot.getUserId())
                    || !SlotDefinitionStatusDict.ACTIVE.getCode().equals(slot.getStatus())) {
                continue;
            }
            mutable.computeIfAbsent(slot.getUserId(), ignored -> new LinkedHashMap<>()).putIfAbsent(slot.getId(), slot);
        }
        Map<Long, List<SlotDefinitionEntity>> result = new LinkedHashMap<>();
        for (Map.Entry<Long, LinkedHashMap<Long, SlotDefinitionEntity>> entry : mutable.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue().values()));
        }
        return result;
    }

    /**
     * 按用户和档位建立不可约状态映射，未知或异常状态按不可约保守处理。
     *
     * @param schedules Mapper 返回的档期
     * @param memberUserIds 有效成员用户 ID 集合
     * @param queriedDate 查询日期
     * @return 用户和档位到不可约标识的映射
     */
    private Map<ScheduleKey, Boolean> groupUnavailableSchedules(
            List<ScheduleEntity> schedules,
            Set<Long> memberUserIds,
            LocalDate queriedDate
    ) {
        Map<ScheduleKey, Boolean> result = new LinkedHashMap<>();
        for (ScheduleEntity schedule : schedules) {
            if (schedule == null || schedule.getUserId() == null || schedule.getSlotDefinitionId() == null
                    || !memberUserIds.contains(schedule.getUserId()) || !Objects.equals(queriedDate, schedule.getScheduleDate())) {
                continue;
            }
            ScheduleKey key = new ScheduleKey(schedule.getUserId(), schedule.getSlotDefinitionId());
            boolean unavailable = isUnavailable(schedule.getStatus());
            result.merge(key, unavailable, (left, right) -> left || right);
        }
        return result;
    }

    /**
     * 判断档期状态是否不可约，未知状态按不可约处理。
     *
     * @param status 档期状态
     * @return true 表示不可约
     */
    private boolean isUnavailable(String status) {
        return ScheduleStatusDict.BOOKED.getCode().equals(status)
                || ScheduleStatusDict.TENTATIVE.getCode().equals(status)
                || ScheduleStatusDict.REST.getCode().equals(status)
                || ScheduleStatusDict.fromCode(status) == null;
    }

    /**
     * 构建单个成员档期结果。
     *
     * @param user 成员用户
     * @param totalSlotCount 生效档位数量
     * @param availableSlotCount 可约档位数量
     * @return 成员档期结果
     */
    private TeamPortfolioScheduleQueryResponse.Member buildMember(
            UserEntity user,
            int totalSlotCount,
            int availableSlotCount
    ) {
        TeamScheduleMemberStatusDict status = totalSlotCount == 0
                ? TeamScheduleMemberStatusDict.AVAILABLE
                : availableSlotCount == 0
                ? TeamScheduleMemberStatusDict.FULL
                : availableSlotCount == totalSlotCount
                ? TeamScheduleMemberStatusDict.AVAILABLE : TeamScheduleMemberStatusDict.PARTIAL_AVAILABLE;
        TeamPortfolioScheduleQueryResponse.Member member = new TeamPortfolioScheduleQueryResponse.Member();
        member.setMemberUserId(user.getId());
        member.setDisplayName(user.getNickname());
        member.setAvatarUrl(user.getAvatarUrl());
        member.setStatus(status.getCode());
        member.setStatusText(status.getDisplayName());
        member.setTotalSlotCount(totalSlotCount);
        member.setAvailableSlotCount(availableSlotCount);
        member.setEmptySlotDefinition(totalSlotCount == 0);
        return member;
    }

    /**
     * 根据成员结果构建团队结果。
     *
     * @param queriedDate 查询日期
     * @param members 成员结果
     * @return 团队结果
     */
    private TeamPortfolioScheduleQueryResponse buildResponse(
            LocalDate queriedDate,
            List<TeamPortfolioScheduleQueryResponse.Member> members
    ) {
        long availableCount = members.stream()
                .filter(member -> TeamScheduleMemberStatusDict.AVAILABLE.getCode().equals(member.getStatus())).count();
        TeamScheduleResultStatusDict status = members.isEmpty()
                || members.stream().allMatch(member -> TeamScheduleMemberStatusDict.FULL.getCode().equals(member.getStatus()))
                ? TeamScheduleResultStatusDict.TEAM_FULL
                : availableCount == members.size()
                ? TeamScheduleResultStatusDict.TEAM_AVAILABLE : TeamScheduleResultStatusDict.TEAM_PARTIAL_AVAILABLE;
        TeamPortfolioScheduleQueryResponse response = new TeamPortfolioScheduleQueryResponse();
        response.setPortfolioType(PortfolioTypeDict.TEAM.getCode());
        response.setQueriedDate(queriedDate);
        response.setStatus(status.getCode());
        response.setStatusText(status.getDisplayName());
        response.setAvailable(status.getAvailable());
        response.setMembers(List.copyOf(members));
        return response;
    }

    /**
     * 写入已发布团队作品集查档快照。
     *
     * @param portfolio 作品集
     * @param portfolioTitle 作品集标题快照
     * @param renderedConfig 渲染后的组件配置
     * @param request 查询请求
     * @param visitor 访客上下文
     * @param recordContext 快照记录上下文
     * @param response 查询响应
     */
    private void persistRecord(
            PortfolioEntity portfolio,
            String portfolioTitle,
            JSONObject renderedConfig,
            TeamPortfolioScheduleQueryRequest request,
            VisitorContext visitor,
            PublishedQueryRecordContext recordContext,
            TeamPortfolioScheduleQueryResponse response
    ) {
        TeamScheduleQueryRecordEntity record = new TeamScheduleQueryRecordEntity();
        record.setPortfolioId(portfolio.getId());
        record.setPortfolioRevision(portfolio.getPublishedRevision());
        record.setPortfolioTitleSnapshot(portfolioTitle);
        record.setTeamId(portfolio.getOwnerId());
        record.setVisitRecordId(recordContext.visitRecordId());
        record.setVisitorId(visitor.getVisitorId());
        record.setVisitorKey(visitor.getVisitorKey());
        record.setSourceType(defaultSourceType(recordContext.sourceType()));
        record.setDisplayMode(renderedConfig.getString(CONFIG_KEY_DISPLAY_MODE));
        record.setQueriedDate(request.getQueriedDate());
        record.setResultStatus(response.getStatus());
        record.setResultStatusText(response.getStatusText());
        record.setAvailable(response.isAvailable() ? 1 : 0);
        record.setResultMessage(response.getStatusText());
        record.setTeamResultJson(JSON.toJSONString(response.getMembers(), JSONWriter.Feature.WriteNulls));
        record.setAvailableMemberCount((int) response.getMembers().stream()
                .filter(member -> TeamScheduleMemberStatusDict.AVAILABLE.getCode().equals(member.getStatus())).count());
        record.setPartialAvailableMemberCount((int) response.getMembers().stream()
                .filter(member -> TeamScheduleMemberStatusDict.PARTIAL_AVAILABLE.getCode().equals(member.getStatus())).count());
        record.setFullMemberCount((int) response.getMembers().stream()
                .filter(member -> TeamScheduleMemberStatusDict.FULL.getCode().equals(member.getStatus())).count());
        record.setQueriedAt(recordContext.occurredAt());
        if (teamScheduleQueryRecordEntityMapper.insert(record) != 1) {
            throw new BusinessException(RECORD_INSERT_FAILED_MESSAGE);
        }
    }

    /**
     * 解析团队作品集标题快照。
     *
     * @param portfolio 团队作品集
     * @return 标题快照
     */
    private String resolvePortfolioTitle(PortfolioEntity portfolio) {
        if (!hasText(portfolio.getPublishedConfigJson())) {
            throw new BusinessException(PUBLISHED_PORTFOLIO_INVALID_MESSAGE);
        }
        try {
            JSONObject root = JSON.parseObject(portfolio.getPublishedConfigJson());
            Object rawShare = root == null ? null : root.get(CONFIG_KEY_SHARE);
            if (!(rawShare instanceof JSONObject share)) {
                throw new BusinessException(PUBLISHED_PORTFOLIO_INVALID_MESSAGE);
            }
            Object rawTitle = share.get(CONFIG_KEY_TITLE);
            if (!(rawTitle instanceof String title)) {
                throw new BusinessException(PUBLISHED_PORTFOLIO_INVALID_MESSAGE);
            }
            String normalizedTitle = title.strip();
            if (normalizedTitle.isBlank()
                    || normalizedTitle.codePointCount(0, normalizedTitle.length()) > PORTFOLIO_TITLE_MAX_LENGTH) {
                throw new BusinessException(PUBLISHED_PORTFOLIO_INVALID_MESSAGE);
            }
            return normalizedTitle;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(PUBLISHED_PORTFOLIO_INVALID_MESSAGE, exception);
        }
    }

    /**
     * 将来源类型规范化为已支持的访问来源。
     *
     * @param sourceType 原始来源类型
     * @return 可持久化来源类型
     */
    private String defaultSourceType(String sourceType) {
        return VisitSourceTypeDict.fromCode(sourceType) == null ? VisitSourceTypeDict.UNKNOWN.getCode() : sourceType;
    }

    /**
     * 解析 ISO 日期。
     *
     * @param value 日期文本
     * @return 日期
     */
    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new BusinessException(QUERY_DATE_OUT_OF_RANGE_MESSAGE, exception);
        }
    }

    /**
     * 判断文本是否非空白。
     *
     * @param value 文本
     * @return true 表示非空白
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 将可能为空的 Mapper 结果转换为安全列表。
     *
     * @param values 原列表
     * @param <T> 元素类型
     * @return 非空列表
     */
    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    /**
     * 档期索引键。
     *
     * @param userId 用户 ID
     * @param slotDefinitionId 档位定义 ID
     */
    private record ScheduleKey(Long userId, Long slotDefinitionId) {
    }

    /**
     * Task 8 访问事件结果传入的团队查档快照门控上下文。
     *
     * @param visitRecordId 访问汇总记录 ID；跳过快照时为空
     * @param sourceType 访问来源类型
     * @param snapshotRecordable 是否允许写入业务快照
     * @param occurredAt 统一事件发生时间
     */
    public record PublishedQueryRecordContext(
            Long visitRecordId,
            String sourceType,
            boolean snapshotRecordable,
            LocalDateTime occurredAt
    ) {

        /**
         * 创建允许写入快照的事件上下文。
         *
         * @param visitRecordId 访问汇总记录 ID
         * @param sourceType 访问来源类型
         * @param occurredAt 事件发生时间
         * @return 可记录上下文
         */
        public static PublishedQueryRecordContext recordable(
                long visitRecordId,
                String sourceType,
                LocalDateTime occurredAt
        ) {
            return new PublishedQueryRecordContext(visitRecordId, sourceType, true, occurredAt);
        }

        /**
         * 创建重复幂等事件使用的跳过快照上下文。
         *
         * @return 不可记录上下文
         */
        public static PublishedQueryRecordContext skipped() {
            return new PublishedQueryRecordContext(null, null, false, null);
        }
    }
}
