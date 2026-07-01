package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.common.UniqueCodeGenerator;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.MessageActionTypeDict;
import com.jxc.wefolio.dict.MessageCategoryDict;
import com.jxc.wefolio.dict.MessageReadStatusDict;
import com.jxc.wefolio.dict.MessageTypeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dict.TeamMemberChangeStatusDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.dict.TeamStatusDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dto.FileUploadResponse;
import com.jxc.wefolio.dto.MineTeamInvitationResponse;
import com.jxc.wefolio.dto.MineTeamMemberChangeCreateRequest;
import com.jxc.wefolio.dto.MineTeamMemberChangeDetailRequest;
import com.jxc.wefolio.dto.MineTeamMemberChangeDetailResponse;
import com.jxc.wefolio.dto.MineTeamMemberCandidateResponse;
import com.jxc.wefolio.dto.MineTeamMemberInviteRequest;
import com.jxc.wefolio.dto.MineTeamMemberRemoveRequest;
import com.jxc.wefolio.dto.MineTeamCreateRequest;
import com.jxc.wefolio.dto.MineTeamDetailResponse;
import com.jxc.wefolio.dto.MineTeamListResponse;
import com.jxc.wefolio.dto.MineTeamUpdateRequest;
import com.jxc.wefolio.dto.MineTeamOwnerTransferRequest;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.SystemMessageEntity;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.TeamMemberChangeRequestEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.mapper.SystemMessageEntityMapper;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberChangeRequestEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 我的团队服务 — 负责团队列表、创建和维护资料。
 *
 * <p>该服务承接小程序“我的团队”和“团队维护”两个页面所需的数据聚合，
 * 并把团队权限校验、团队唯一码生成、团队 COS 目录初始化放在后端统一处理。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MineTeamService {

    /** 团队名称最大长度 */
    private static final int NAME_MAX_LENGTH = 100;

    /** 团队简介最大长度 */
    private static final int INTRO_MAX_LENGTH = 1000;

    /** 团队身份最大长度 */
    private static final int MEMBER_PROFESSION_MAX_LENGTH = 50;

    /** 团队身份字段名称 */
    private static final String MEMBER_PROFESSION_FIELD_NAME = "团队身份";

    /** 唯一码最大长度 */
    private static final int UNIQUE_CODE_MAX_LENGTH = 16;

    /** 团队图标地址最大长度 */
    private static final int AVATAR_URL_MAX_LENGTH = 512;

    /** 更新时间展示格式 */
    private static final DateTimeFormatter UPDATED_FORMATTER = DateTimeFormatter.ofPattern("MM-dd");

    /** 详情时间展示格式 */
    private static final DateTimeFormatter DETAIL_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 团队邀请消息标题 */
    private static final String INVITATION_MESSAGE_TITLE = "团队邀请";

    /** 团队邀请消息业务类型 */
    private static final String INVITATION_MESSAGE_BIZ_TYPE = "TEAM_MEMBER";

    /** 团队邀请消息正文中段 */
    private static final String INVITATION_CONTENT_MIDDLE = "邀请你加入团队「";

    /** 团队邀请消息正文后缀 */
    private static final String INVITATION_CONTENT_SUFFIX = "」";

    /** 邀请人兜底名称 */
    private static final String INVITER_FALLBACK_NAME = "团队拥有者";

    /** 团队邀请消息跳转路径前缀 */
    private static final String INVITATION_ACTION_URL_PREFIX = "/pages/team-invitations/team-invitations?memberId=";

    /** 团队邀请消息幂等键前缀 */
    private static final String INVITATION_IDEMPOTENCY_PREFIX = "team_invitation:";

    /** 团队成员信息变更消息标题 */
    private static final String MEMBER_CHANGE_MESSAGE_TITLE = "团队信息变更待确认";

    /** 团队成员信息变更消息业务类型 */
    private static final String MEMBER_CHANGE_MESSAGE_BIZ_TYPE = "TEAM_MEMBER_CHANGE_REQUEST";

    /** 团队成员信息变更消息跳转路径前缀 */
    private static final String MEMBER_CHANGE_ACTION_URL_PREFIX =
            "/pages/team-member-change/team-member-change?changeRequestId=";

    /** 团队成员信息变更消息幂等键前缀 */
    private static final String MEMBER_CHANGE_IDEMPOTENCY_PREFIX = "team_member_change:";

    /** 空动作地址 */
    private static final String EMPTY_ACTION_URL = "";

    /** 团队拥有者转让消息标题 */
    private static final String OWNER_TRANSFER_MESSAGE_TITLE = "团队拥有者变更";

    /** 团队成员移除消息标题 */
    private static final String MEMBER_REMOVED_MESSAGE_TITLE = "团队成员移除通知";

    /** 团队成员移除原因 */
    private static final String MEMBER_REMOVED_REASON = "团队拥有者移除";

    /** 团队通知消息业务类型 */
    private static final String TEAM_NOTICE_BIZ_TYPE = "TEAM_NOTICE";

    /** 团队拥有者转让消息幂等键前缀 */
    private static final String OWNER_TRANSFER_IDEMPOTENCY_PREFIX = "team_owner_transfer:";

    /** 团队成员移除消息幂等键前缀 */
    private static final String MEMBER_REMOVED_IDEMPOTENCY_PREFIX = "team_member_removed:";

    /** 成员信息变更待同意提示 */
    private static final String PENDING_MEMBER_CHANGE_TEXT = "信息变更待同意";

    /** 成员信息变更保存失败提示 */
    private static final String MEMBER_CHANGE_SAVE_FAILED_MESSAGE = "成员信息变更保存失败，请重试";

    /** 并发邀请已存在时的提示 */
    private static final String PENDING_INVITATION_EXISTS_MESSAGE = "已有待确认邀请，请刷新后查看";

    /** 团队邀请保存失败提示 */
    private static final String TEAM_INVITATION_SAVE_FAILED_MESSAGE = "团队邀请保存失败，请重试";

    /** 团队成员状态列 */
    private static final String COLUMN_JOIN_STATUS = "join_status";

    /** 团队 ID 列 */
    private static final String COLUMN_TEAM_ID = "team_id";

    /** 团队成员关系 ID 列 */
    private static final String COLUMN_MEMBER_ID = "member_id";

    /** 变更请求状态列 */
    private static final String COLUMN_STATUS = "status";

    /** 团队成员角色列 */
    private static final String COLUMN_ROLE = "role";

    /** 团队成员职业列 */
    private static final String COLUMN_PROFESSION = "profession";

    /** 允许引用个人作品集列 */
    private static final String COLUMN_ALLOW_PORTFOLIO = "allow_portfolio";

    /** 允许引用头像资料列 */
    private static final String COLUMN_ALLOW_PROFILE = "allow_profile";

    /** 允许引用个人作品素材列 */
    private static final String COLUMN_ALLOW_WORKS = "allow_works";

    /** 邀请人列 */
    private static final String COLUMN_INVITED_BY = "invited_by";

    /** 邀请时间列 */
    private static final String COLUMN_INVITED_AT = "invited_at";

    /** 响应时间列 */
    private static final String COLUMN_RESPONDED_AT = "responded_at";

    /** 加入时间列 */
    private static final String COLUMN_JOINED_AT = "joined_at";

    /** 移除时间列 */
    private static final String COLUMN_REMOVED_AT = "removed_at";

    /** 移除原因列 */
    private static final String COLUMN_REMOVAL_REASON = "removal_reason";

    /** 主键列 */
    private static final String COLUMN_ID = "id";

    /** 用户 ID 列 */
    private static final String COLUMN_USER_ID = "user_id";

    /** 团队消息幂等键列 */
    private static final String COLUMN_IDEMPOTENCY_KEY = "idempotency_key";

    /** 消息已读状态列 */
    private static final String COLUMN_READ_STATUS = "read_status";

    /** 消息已读时间列 */
    private static final String COLUMN_READ_AT = "read_at";

    /** 消息标题列 */
    private static final String COLUMN_TITLE = "title";

    /** 消息正文列 */
    private static final String COLUMN_CONTENT = "content";

    /** 消息动作类型列 */
    private static final String COLUMN_ACTION_TYPE = "action_type";

    /** 消息动作地址列 */
    private static final String COLUMN_ACTION_URL = "action_url";

    /** 消息业务 ID 列 */
    private static final String COLUMN_BIZ_ID = "biz_id";

    /** 团队当前拥有者列 */
    private static final String COLUMN_OWNER_USER_ID = "owner_user_id";

    /** 乐观锁版本列 */
    private static final String COLUMN_VERSION = "version";

    /** 乐观锁版本递增表达式 */
    private static final String VERSION_INCREMENT_SQL = COLUMN_VERSION + " = " + COLUMN_VERSION + " + 1";

    /** 团队 Mapper */
    private final TeamEntityMapper teamEntityMapper;

    /** 团队成员 Mapper */
    private final TeamMemberEntityMapper teamMemberEntityMapper;

    /** 用户 Mapper */
    private final UserEntityMapper userEntityMapper;

    /** 系统消息 Mapper */
    private final SystemMessageEntityMapper systemMessageEntityMapper;

    /** 团队成员信息变更请求 Mapper */
    private final TeamMemberChangeRequestEntityMapper teamMemberChangeRequestEntityMapper;

    /** 团队成员信息变更事务辅助服务 */
    private final TeamMemberChangeRequestTransactionService teamMemberChangeRequestTransactionService;

    /** 作品集 Mapper */
    private final PortfolioEntityMapper portfolioEntityMapper;

    /** 作品集引用 Mapper */
    private final PortfolioReferenceEntityMapper portfolioReferenceEntityMapper;

    /** 作品 Mapper */
    private final WorkEntityMapper workEntityMapper;

    /** COS 文件服务 */
    private final CosService cosService;

    /** 团队注册事务服务 */
    private final TeamRegistrationService teamRegistrationService;

    /** 积分服务 */
    private final PointService pointService;

    /** 唯一码生成器 */
    private final UniqueCodeGenerator uniqueCodeGenerator;

    /**
     * 获取当前用户加入的团队列表。
     *
     * @return 团队列表响应
     */
    public MineTeamListResponse listTeams() {
        Long userId = AuthContextHolder.requireUserId();
        // 先读取当前用户已加入的成员关系，再批量读取团队，避免把待确认/已退出成员展示在列表里。
        List<TeamMemberEntity> memberships = safeList(teamMemberEntityMapper.selectList(
                Wrappers.lambdaQuery(TeamMemberEntity.class)
                        .eq(TeamMemberEntity::getUserId, userId)
                        .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
        ));
        if (memberships.isEmpty()) {
            return emptyListResponse();
        }

        // 团队主表只保留启用中的团队，防止已停用团队通过成员关系残留继续出现在小程序。
        List<Long> teamIds = memberships.stream()
                .map(TeamMemberEntity::getTeamId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, TeamEntity> teamMap = safeList(teamEntityMapper.selectBatchIds(teamIds)).stream()
                .filter(team -> TeamStatusDict.ACTIVE.getCode().equals(team.getStatus()))
                .collect(Collectors.toMap(TeamEntity::getId, team -> team, (left, right) -> left, LinkedHashMap::new));
        List<TeamMemberEntity> allMembers = teamMap.isEmpty()
                ? Collections.emptyList()
                : safeList(teamMemberEntityMapper.selectList(
                        Wrappers.lambdaQuery(TeamMemberEntity.class)
                                .in(TeamMemberEntity::getTeamId, teamMap.keySet())
                                .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
                ));
        // 列表页需要展示成员数量，这里统一按已加入成员聚合，避免前端再发额外请求。
        Map<Long, Long> memberCounts = allMembers.stream()
                .collect(Collectors.groupingBy(TeamMemberEntity::getTeamId, Collectors.counting()));

        List<MineTeamListResponse.TeamItem> items = new ArrayList<>();
        for (TeamMemberEntity membership : memberships) {
            TeamEntity team = teamMap.get(membership.getTeamId());
            if (team == null) {
                continue;
            }
            items.add(buildTeamItem(team, membership, memberCounts.getOrDefault(team.getId(), 0L).intValue()));
        }

        MineTeamListResponse response = new MineTeamListResponse();
        response.setTeams(items);
        response.setSummary(buildSummary(items));
        return response;
    }

    /**
     * 创建团队，并创建当前用户的拥有者成员关系。
     *
     * @param request 创建请求
     * @return 团队维护详情响应
     */
    public MineTeamDetailResponse createTeam(MineTeamCreateRequest request) {
        if (request == null) {
            throw new BusinessException("团队内容不能为空");
        }
        Long userId = AuthContextHolder.requireUserId();
        String name = normalizeRequiredString(request.getName(), NAME_MAX_LENGTH, "团队名称");
        String intro = normalizeOptionalString(request.getIntro(), INTRO_MAX_LENGTH, "团队简介");
        String avatarUrl = normalizeOptionalString(request.getAvatarUrl(), AVATAR_URL_MAX_LENGTH, "团队图标");
        String uniqueCode = generateUniqueTeamCode();

        pointService.assertCanConsume(userId, PointSceneCodeDict.CREATE_TEAM.getCode(), 1);
        try {
            // 积分预校验通过后初始化团队 COS 目录；事务内仍会二次扣分校验，避免并发余额变化。
            cosService.initTeamStorage(uniqueCode);
        } catch (Exception e) {
            throw new BusinessException("团队存储初始化失败，请重试", e);
        }

        TeamRegistrationService.TeamCreationResult result = teamRegistrationService.createTeamWithOwner(
                uniqueCode, userId, name, intro, avatarUrl);
        return buildDetailResponse(result.getTeam(), result.getOwnerMembership(),
                List.of(result.getOwnerMembership()), Collections.emptyMap());
    }

    /**
     * 获取团队维护详情。
     *
     * @param teamId 团队 ID
     * @return 团队详情响应
     */
    public MineTeamDetailResponse getTeamDetail(Long teamId) {
        Long userId = AuthContextHolder.requireUserId();
        TeamEntity team = requireActiveTeam(teamId);
        TeamMemberEntity currentMembership = requireJoinedMembership(teamId, userId, "团队不存在或无访问权限");
        return buildDetailResponseWithMembers(team, currentMembership);
    }

    /**
     * 保存团队资料。
     *
     * @param teamId 团队 ID
     * @param request 更新请求
     * @return 团队详情响应
     */
    public MineTeamDetailResponse updateTeam(Long teamId, MineTeamUpdateRequest request) {
        if (request == null) {
            throw new BusinessException("团队内容不能为空");
        }
        Long userId = AuthContextHolder.requireUserId();
        TeamEntity team = requireActiveTeam(teamId);
        TeamMemberEntity membership = requireJoinedMembership(teamId, userId, "团队不存在或无访问权限");
        if (!canMaintain(membership.getRole())) {
            throw new BusinessException("无团队维护权限");
        }
        if (team.getVersion() == null) {
            throw new BusinessException("团队版本号异常，请刷新后重试");
        }

        // 更新请求按“字段存在才覆盖”处理，便于团队图标上传后只补写 avatarUrl。
        TeamEntity updateEntity = new TeamEntity();
        updateEntity.setVersion(team.getVersion());
        UpdateWrapper<TeamEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(COLUMN_ID, teamId);
        applyStringField(request.getName(), NAME_MAX_LENGTH, "团队名称",
                team::setName, updateEntity::setName);
        applyOptionalStringField(request.getIntro(), INTRO_MAX_LENGTH, "团队简介",
                team::setIntro, updateEntity::setIntro);
        applyOptionalStringField(request.getAvatarUrl(), AVATAR_URL_MAX_LENGTH, "团队图标",
                team::setAvatarUrl, updateEntity::setAvatarUrl);
        int updated = teamEntityMapper.update(updateEntity, updateWrapper);
        if (updated <= 0) {
            throw new BusinessException("团队资料已被其他管理员更新，请刷新后重试");
        }
        return buildDetailResponseWithMembers(team, membership);
    }

    /**
     * 上传团队图标到团队 others 目录。
     *
     * @param teamId 团队 ID
     * @param file 图标文件
     * @return 文件上传响应
     */
    public FileUploadResponse uploadTeamAvatar(Long teamId, MultipartFile file) {
        Long userId = AuthContextHolder.requireUserId();
        TeamEntity team = requireActiveTeam(teamId);
        TeamMemberEntity membership = requireJoinedMembership(teamId, userId, "团队不存在或无访问权限");
        if (!canMaintain(membership.getRole())) {
            throw new BusinessException("无团队维护权限");
        }
        // 团队图标属于团队杂项素材，固定写入 {teamCode}/others 目录。
        String key = cosService.upload(file, team.getUniqueCode() + "/others");
        FileUploadResponse response = new FileUploadResponse();
        response.setKey(key);
        response.setUrl(cosService.publicUrl(key));
        return response;
    }

    /**
     * 查询可邀请成员候选人。
     *
     * @param teamId 团队 ID
     * @param uniqueCode 个人唯一码
     * @return 候选人响应
     */
    public MineTeamMemberCandidateResponse getMemberCandidate(Long teamId, String uniqueCode) {
        Long userId = AuthContextHolder.requireUserId();
        requireActiveTeam(teamId);
        requireOwnerMembership(teamId, userId);
        UserEntity candidate = requireActiveUserByUniqueCode(uniqueCode);
        TeamMemberEntity existing = findMembership(teamId, candidate.getId());
        MineTeamMemberCandidateResponse response = new MineTeamMemberCandidateResponse();
        response.setUserId(candidate.getId());
        response.setUniqueCode(defaultString(candidate.getUniqueCode()));
        response.setNickname(defaultString(candidate.getNickname(), "微信用户"));
        response.setAvatarUrl(defaultString(candidate.getAvatarUrl()));
        response.setProfession(defaultString(candidate.getProfession()));
        response.setCity(defaultString(candidate.getCity()));
        response.setUserStatus(defaultString(candidate.getStatus(), UserStatusDict.ACTIVE.getCode()));
        response.setDisplayName(buildDisplayName(response.getNickname(), response.getProfession()));
        response.setMemberId(existing == null ? null : existing.getId());
        response.setExistingJoinStatus(existing == null ? "" : defaultString(existing.getJoinStatus()));
        response.setExistingJoinStatusText(existing == null ? "" : joinStatusText(existing.getJoinStatus()));
        applyCandidateInviteState(response, userId, existing);
        return response;
    }

    /**
     * 邀请成员加入团队。
     *
     * @param teamId 团队 ID
     * @param request 邀请请求
     * @return 最新团队维护详情
     */
    @Transactional(rollbackFor = Exception.class)
    public MineTeamDetailResponse inviteMember(Long teamId, MineTeamMemberInviteRequest request) {
        if (request == null) {
            throw new BusinessException("成员邀请内容不能为空");
        }
        Long userId = AuthContextHolder.requireUserId();
        TeamEntity team = requireActiveTeam(teamId);
        TeamMemberEntity ownerMembership = requireOwnerMembership(teamId, userId);
        UserEntity invitee = requireActiveUserByUniqueCode(request.getUniqueCode());
        if (userId.equals(invitee.getId())) {
            throw new BusinessException("不能邀请自己加入团队");
        }
        String role = normalizeInviteRole(request.getRole());
        String profession = firstPresent(
                normalizeOptionalString(request.getProfession(), MEMBER_PROFESSION_MAX_LENGTH, MEMBER_PROFESSION_FIELD_NAME),
                invitee.getProfession()
        );
        TeamMemberEntity membership = findMembership(teamId, invitee.getId());
        if (membership == null) {
            membership = insertPendingMembership(teamId, userId, invitee.getId(), role, profession, request);
        } else {
            restorePendingMembership(membership, userId, role, profession, request);
        }
        createInvitationMessage(team, membership, invitee, loadUser(userId));
        return buildDetailResponseWithMembers(team, ownerMembership);
    }

    /**
     * 获取团队邀请详情。
     *
     * @param memberId 团队成员关系 ID
     * @return 团队邀请响应
     */
    public MineTeamInvitationResponse getInvitation(Long memberId) {
        Long userId = AuthContextHolder.requireUserId();
        TeamMemberEntity invitation = requireInvitationForCurrentUser(memberId, userId);
        TeamEntity team = requireActiveTeam(invitation.getTeamId());
        markInvitationMessageRead(memberId, userId);
        return buildInvitationResponse(invitation, team);
    }

    /**
     * 接受团队邀请。
     *
     * @param memberId 团队成员关系 ID
     * @return 团队邀请响应
     */
    @Transactional(rollbackFor = Exception.class)
    public MineTeamInvitationResponse acceptInvitation(Long memberId) {
        Long userId = AuthContextHolder.requireUserId();
        TeamMemberEntity invitation = requirePendingInvitationForCurrentUser(memberId, userId);
        TeamEntity team = requireActiveTeam(invitation.getTeamId());
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<TeamMemberEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(COLUMN_ID, memberId)
                .eq(COLUMN_USER_ID, userId)
                .eq(COLUMN_JOIN_STATUS, JoinStatusDict.PENDING_CONFIRMATION.getCode())
                .set(COLUMN_JOIN_STATUS, JoinStatusDict.JOINED.getCode())
                .set(COLUMN_RESPONDED_AT, now)
                .set(COLUMN_JOINED_AT, now);
        int updated = teamMemberEntityMapper.update(new TeamMemberEntity(), updateWrapper);
        if (updated <= 0) {
            throw new BusinessException("团队邀请状态已变化，请刷新后重试");
        }
        invitation.setJoinStatus(JoinStatusDict.JOINED.getCode());
        invitation.setRespondedAt(now);
        invitation.setJoinedAt(now);
        clearInvitationMessageAction(memberId, userId);
        return buildInvitationResponse(invitation, team);
    }

    /**
     * 拒绝团队邀请。
     *
     * @param memberId 团队成员关系 ID
     * @return 团队邀请响应
     */
    @Transactional(rollbackFor = Exception.class)
    public MineTeamInvitationResponse rejectInvitation(Long memberId) {
        Long userId = AuthContextHolder.requireUserId();
        TeamMemberEntity invitation = requirePendingInvitationForCurrentUser(memberId, userId);
        TeamEntity team = requireActiveTeam(invitation.getTeamId());
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<TeamMemberEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(COLUMN_ID, memberId)
                .eq(COLUMN_USER_ID, userId)
                .eq(COLUMN_JOIN_STATUS, JoinStatusDict.PENDING_CONFIRMATION.getCode())
                .set(COLUMN_JOIN_STATUS, JoinStatusDict.REJECTED.getCode())
                .set(COLUMN_RESPONDED_AT, now);
        int updated = teamMemberEntityMapper.update(new TeamMemberEntity(), updateWrapper);
        if (updated <= 0) {
            throw new BusinessException("团队邀请状态已变化，请刷新后重试");
        }
        invitation.setJoinStatus(JoinStatusDict.REJECTED.getCode());
        invitation.setRespondedAt(now);
        clearInvitationMessageAction(memberId, userId);
        return buildInvitationResponse(invitation, team);
    }

    /**
     * 发起团队成员信息变更请求。
     *
     * @param request 成员信息变更请求
     * @return 最新团队维护详情
     */
    @Transactional(rollbackFor = Exception.class)
    public MineTeamDetailResponse createMemberChangeRequest(MineTeamMemberChangeCreateRequest request) {
        if (request == null) {
            throw new BusinessException("成员信息变更内容不能为空");
        }
        Long userId = AuthContextHolder.requireUserId();
        TeamEntity team = requireActiveTeam(request.getTeamId());
        TeamMemberEntity ownerMembership = requireOwnerMembership(team.getId(), userId);
        TeamMemberEntity target = requireJoinedTeamMemberById(team.getId(), request.getMemberId(), "成员不存在或已不在团队中");
        if (TeamRoleDict.OWNER.getCode().equals(target.getRole())) {
            throw new BusinessException("不能修改团队拥有者信息");
        }
        String roleAfter = normalizeInviteRole(request.getRole());
        String professionAfter = normalizeOptionalString(
                request.getProfession(),
                MEMBER_PROFESSION_MAX_LENGTH,
                MEMBER_PROFESSION_FIELD_NAME
        );
        int allowPortfolioAfter = permissionFlag(request.getAllowPortfolio(), isEnabled(target.getAllowPortfolio()));
        int allowProfileAfter = permissionFlag(request.getAllowProfile(), isEnabled(target.getAllowProfile()));
        int allowWorksAfter = permissionFlag(request.getAllowWorks(), isEnabled(target.getAllowWorks()));
        if (!hasMemberChange(target, roleAfter, professionAfter, allowPortfolioAfter, allowProfileAfter, allowWorksAfter)) {
            throw new BusinessException("成员信息没有变化");
        }
        assertNoPendingMemberChange(target.getId());

        TeamMemberChangeRequestEntity changeRequest = new TeamMemberChangeRequestEntity();
        LocalDateTime now = LocalDateTime.now();
        changeRequest.setTeamId(team.getId());
        changeRequest.setMemberId(target.getId());
        changeRequest.setTargetUserId(target.getUserId());
        changeRequest.setRequestedByUserId(userId);
        changeRequest.setMemberVersionBefore(target.getVersion() == null ? 0 : target.getVersion());
        changeRequest.setRoleBefore(defaultString(target.getRole(), TeamRoleDict.MEMBER.getCode()));
        changeRequest.setRoleAfter(roleAfter);
        changeRequest.setProfessionBefore(defaultString(target.getProfession()));
        changeRequest.setProfessionAfter(professionAfter);
        changeRequest.setAllowPortfolioBefore(flagOrZero(target.getAllowPortfolio()));
        changeRequest.setAllowPortfolioAfter(allowPortfolioAfter);
        changeRequest.setAllowProfileBefore(flagOrZero(target.getAllowProfile()));
        changeRequest.setAllowProfileAfter(allowProfileAfter);
        changeRequest.setAllowWorksBefore(flagOrZero(target.getAllowWorks()));
        changeRequest.setAllowWorksAfter(allowWorksAfter);
        changeRequest.setStatus(TeamMemberChangeStatusDict.PENDING_CONFIRMATION.getCode());
        changeRequest.setRequestedAt(now);
        try {
            teamMemberChangeRequestEntityMapper.insert(changeRequest);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("已有信息变更待同意", e);
        }
        createMemberChangeMessage(team, changeRequest);
        return buildDetailResponseWithMembers(team, ownerMembership);
    }

    /**
     * 查看团队成员信息变更详情，打开时同步标记站内信为已读。
     *
     * @param request 详情请求
     * @return 变更详情
     */
    public MineTeamMemberChangeDetailResponse getMemberChangeRequestDetail(MineTeamMemberChangeDetailRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        TeamMemberChangeRequestEntity changeRequest = requireMemberChangeForCurrentUser(request, userId);
        TeamEntity team = requireActiveTeam(changeRequest.getTeamId());
        markMemberChangeMessageRead(changeRequest.getId(), userId);
        return buildMemberChangeDetailResponse(changeRequest, team, userId);
    }

    /**
     * 同意团队成员信息变更。
     *
     * @param request 详情请求
     * @return 处理后的变更详情
     */
    @Transactional(rollbackFor = Exception.class)
    public MineTeamMemberChangeDetailResponse acceptMemberChangeRequest(MineTeamMemberChangeDetailRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        TeamMemberChangeRequestEntity changeRequest = requirePendingMemberChangeForCurrentUser(request, userId);
        TeamEntity team = requireActiveTeam(changeRequest.getTeamId());
        TeamMemberEntity member = requireJoinedTeamMemberById(team.getId(), changeRequest.getMemberId(), "成员信息已变化，请联系团队拥有者重新发起。");
        if (!Objects.equals(member.getVersion(), changeRequest.getMemberVersionBefore())) {
            teamMemberChangeRequestTransactionService.invalidateMemberChangeAndClearAction(
                    changeRequest.getId(), userId);
            changeRequest.setStatus(TeamMemberChangeStatusDict.INVALIDATED.getCode());
            throw new BusinessException("成员信息已变化，请联系团队拥有者重新发起。");
        }

        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<TeamMemberEntity> memberUpdate = new UpdateWrapper<>();
        memberUpdate.eq(COLUMN_ID, changeRequest.getMemberId())
                .eq(COLUMN_TEAM_ID, changeRequest.getTeamId())
                .eq(COLUMN_USER_ID, userId)
                .eq(COLUMN_JOIN_STATUS, JoinStatusDict.JOINED.getCode())
                .eq(COLUMN_ROLE, changeRequest.getRoleBefore())
                .eq(COLUMN_VERSION, changeRequest.getMemberVersionBefore())
                .set(COLUMN_ROLE, changeRequest.getRoleAfter())
                .set(COLUMN_PROFESSION, defaultString(changeRequest.getProfessionAfter()))
                .set(COLUMN_ALLOW_PORTFOLIO, flagOrZero(changeRequest.getAllowPortfolioAfter()))
                .set(COLUMN_ALLOW_PROFILE, flagOrZero(changeRequest.getAllowProfileAfter()))
                .set(COLUMN_ALLOW_WORKS, flagOrZero(changeRequest.getAllowWorksAfter()))
                .setSql(VERSION_INCREMENT_SQL);
        int memberUpdated = teamMemberEntityMapper.update(new TeamMemberEntity(), memberUpdate);
        if (memberUpdated <= 0) {
            teamMemberChangeRequestTransactionService.invalidateMemberChangeAndClearAction(
                    changeRequest.getId(), userId);
            changeRequest.setStatus(TeamMemberChangeStatusDict.INVALIDATED.getCode());
            throw new BusinessException("成员信息已变化，请联系团队拥有者重新发起。");
        }
        updateMemberChangeStatus(changeRequest.getId(), TeamMemberChangeStatusDict.ACCEPTED.getCode(), now);
        changeRequest.setStatus(TeamMemberChangeStatusDict.ACCEPTED.getCode());
        changeRequest.setRespondedAt(now);
        clearMemberChangeMessageAction(changeRequest.getId(), userId);
        return buildMemberChangeDetailResponse(changeRequest, team, userId);
    }

    /**
     * 拒绝团队成员信息变更。
     *
     * @param request 详情请求
     * @return 处理后的变更详情
     */
    @Transactional(rollbackFor = Exception.class)
    public MineTeamMemberChangeDetailResponse rejectMemberChangeRequest(MineTeamMemberChangeDetailRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        TeamMemberChangeRequestEntity changeRequest = requirePendingMemberChangeForCurrentUser(request, userId);
        TeamEntity team = requireActiveTeam(changeRequest.getTeamId());
        LocalDateTime now = LocalDateTime.now();
        updateMemberChangeStatus(changeRequest.getId(), TeamMemberChangeStatusDict.REJECTED.getCode(), now);
        changeRequest.setStatus(TeamMemberChangeStatusDict.REJECTED.getCode());
        changeRequest.setRespondedAt(now);
        clearMemberChangeMessageAction(changeRequest.getId(), userId);
        return buildMemberChangeDetailResponse(changeRequest, team, userId);
    }

    /**
     * 转让团队拥有者。
     *
     * @param request 转让请求
     * @return 最新团队维护详情
     */
    @Transactional(rollbackFor = Exception.class)
    public MineTeamDetailResponse transferOwner(MineTeamOwnerTransferRequest request) {
        if (request == null) {
            throw new BusinessException("团队转让内容不能为空");
        }
        Long userId = AuthContextHolder.requireUserId();
        TeamEntity team = requireActiveTeam(request.getTeamId());
        TeamMemberEntity ownerMembership = requireOwnerMembership(team.getId(), userId);
        TeamMemberEntity target = requireJoinedTeamMemberById(team.getId(), request.getMemberId(), "转让成员不存在或已不在团队中");
        if (userId.equals(target.getUserId())) {
            throw new BusinessException("不能转让给自己");
        }
        if (TeamRoleDict.OWNER.getCode().equals(target.getRole())) {
            throw new BusinessException("对方已是团队拥有者");
        }

        String noticeIdempotencyKey = buildOwnerTransferIdempotencyKey(team, target);
        updateMemberRole(ownerMembership.getId(), ownerMembership.getVersion(), TeamRoleDict.OWNER.getCode(),
                TeamRoleDict.MANAGER.getCode(), "团队拥有者变更失败，请刷新后重试");
        updateMemberRole(target.getId(), target.getVersion(), target.getRole(), TeamRoleDict.OWNER.getCode(),
                "团队拥有者变更失败，请刷新后重试");
        UpdateWrapper<TeamEntity> teamUpdate = new UpdateWrapper<>();
        teamUpdate.eq(COLUMN_ID, team.getId())
                .eq(COLUMN_VERSION, team.getVersion())
                .set(COLUMN_OWNER_USER_ID, target.getUserId())
                .setSql(VERSION_INCREMENT_SQL);
        int teamUpdated = teamEntityMapper.update(new TeamEntity(), teamUpdate);
        if (teamUpdated <= 0) {
            throw new BusinessException("团队拥有者变更失败，请刷新后重试");
        }
        ownerMembership.setRole(TeamRoleDict.MANAGER.getCode());
        ownerMembership.setVersion(incrementVersion(ownerMembership.getVersion()));
        target.setRole(TeamRoleDict.OWNER.getCode());
        target.setVersion(incrementVersion(target.getVersion()));
        team.setOwnerUserId(target.getUserId());
        team.setVersion(incrementVersion(team.getVersion()));
        invalidatePendingChangesForMember(target.getId());
        createPlainTeamNotice(
                target.getUserId(),
                OWNER_TRANSFER_MESSAGE_TITLE,
                "你已被指定为「" + defaultString(team.getName(), "未命名团队") + "」的拥有者。",
                team.getId(),
                noticeIdempotencyKey
        );
        return buildDetailResponseWithMembers(team, ownerMembership);
    }

    /**
     * 移除团队成员。
     *
     * @param request 移除请求
     * @return 最新团队维护详情
     */
    @Transactional(rollbackFor = Exception.class)
    public MineTeamDetailResponse removeMember(MineTeamMemberRemoveRequest request) {
        if (request == null) {
            throw new BusinessException("成员移除内容不能为空");
        }
        Long userId = AuthContextHolder.requireUserId();
        TeamEntity team = requireActiveTeam(request.getTeamId());
        TeamMemberEntity ownerMembership = requireOwnerMembership(team.getId(), userId);
        TeamMemberEntity target = requireJoinedTeamMemberById(team.getId(), request.getMemberId(), "成员不存在或已不在团队中");
        if (userId.equals(target.getUserId())) {
            throw new BusinessException("不能移除自己");
        }
        if (TeamRoleDict.OWNER.getCode().equals(target.getRole())) {
            throw new BusinessException("不能移除团队拥有者");
        }
        assertMemberContentNotReferenced(team.getId(), target.getUserId());

        LocalDateTime now = LocalDateTime.now();
        String noticeIdempotencyKey = buildMemberRemovedIdempotencyKey(target);
        UpdateWrapper<TeamMemberEntity> memberUpdate = new UpdateWrapper<>();
        memberUpdate.eq(COLUMN_ID, target.getId())
                .eq(COLUMN_TEAM_ID, team.getId())
                .eq(COLUMN_JOIN_STATUS, JoinStatusDict.JOINED.getCode())
                .eq(COLUMN_VERSION, target.getVersion())
                .set(COLUMN_JOIN_STATUS, JoinStatusDict.REMOVED.getCode())
                .set(COLUMN_REMOVED_AT, now)
                .set(COLUMN_REMOVAL_REASON, MEMBER_REMOVED_REASON)
                .setSql(VERSION_INCREMENT_SQL);
        int updated = teamMemberEntityMapper.update(new TeamMemberEntity(), memberUpdate);
        if (updated <= 0) {
            throw new BusinessException("成员状态已变化，请刷新后重试");
        }
        target.setJoinStatus(JoinStatusDict.REMOVED.getCode());
        target.setRemovedAt(now);
        target.setRemovalReason(MEMBER_REMOVED_REASON);
        target.setVersion(incrementVersion(target.getVersion()));
        invalidatePendingChangesForMember(target.getId());
        createPlainTeamNotice(
                target.getUserId(),
                MEMBER_REMOVED_MESSAGE_TITLE,
                "你已被移出「" + defaultString(team.getName(), "未命名团队") + "」。",
                team.getId(),
                noticeIdempotencyKey
        );
        return buildDetailResponseWithMembers(team, ownerMembership);
    }

    /**
     * 构建团队列表项。
     *
     * @param team 团队实体
     * @param membership 当前用户成员关系
     * @param memberCount 成员数
     * @return 团队列表项
     */
    private MineTeamListResponse.TeamItem buildTeamItem(
            TeamEntity team,
            TeamMemberEntity membership,
            int memberCount
    ) {
        MineTeamListResponse.TeamItem item = new MineTeamListResponse.TeamItem();
        item.setTeamId(team.getId());
        item.setUniqueCode(defaultString(team.getUniqueCode()));
        item.setName(defaultString(team.getName()));
        item.setAvatarUrl(defaultString(team.getAvatarUrl()));
        item.setIntro(defaultString(team.getIntro()));
        item.setCity(defaultString(team.getCity()));
        item.setRole(defaultString(membership.getRole()));
        item.setRoleText(roleText(membership.getRole()));
        item.setCanMaintain(canMaintain(membership.getRole()));
        item.setMaintainText(item.isCanMaintain() ? "维护" : "查看");
        item.setMemberCount(memberCount);
        item.setMemberCountText(memberCount + " 位成员");
        item.setUpdatedText(formatUpdatedAt(team.getUpdatedAt()));
        return item;
    }

    /**
     * 读取团队成员并构建团队维护详情。
     *
     * <p>团队资料保存后也走这条路径，保证返回给小程序的成员数量和成员列表都是真实数据，
     * 避免使用空列表兜底成 1 位成员。</p>
     *
     * @param team 团队实体
     * @param currentMembership 当前用户成员关系
     * @return 团队维护详情
     */
    private MineTeamDetailResponse buildDetailResponseWithMembers(
            TeamEntity team,
            TeamMemberEntity currentMembership
    ) {
        List<TeamMemberEntity> members = safeList(teamMemberEntityMapper.selectList(
                Wrappers.lambdaQuery(TeamMemberEntity.class)
                        .eq(TeamMemberEntity::getTeamId, team.getId())
                        .in(TeamMemberEntity::getJoinStatus,
                                JoinStatusDict.JOINED.getCode(),
                                JoinStatusDict.PENDING_CONFIRMATION.getCode())
        ));
        Map<Long, UserEntity> userMap = loadUsers(members.stream().map(TeamMemberEntity::getUserId).toList());
        Map<Long, TeamMemberChangeRequestEntity> pendingChangeMap =
                canManageMembers(currentMembership.getRole())
                        ? loadPendingChangeMap(team.getId(), members.stream().map(TeamMemberEntity::getId).toList())
                        : Collections.emptyMap();
        return buildDetailResponse(team, currentMembership, members, userMap, pendingChangeMap);
    }

    /**
     * 构建团队详情响应。
     *
     * @param team 团队实体
     * @param membership 当前用户成员关系
     * @param members 成员关系列表
     * @param userMap 用户资料映射
     * @return 团队详情响应
     */
    private MineTeamDetailResponse buildDetailResponse(
            TeamEntity team,
            TeamMemberEntity membership,
            List<TeamMemberEntity> members,
            Map<Long, UserEntity> userMap
    ) {
        return buildDetailResponse(team, membership, members, userMap, Collections.emptyMap());
    }

    /**
     * 构建团队详情响应。
     *
     * @param team 团队实体
     * @param membership 当前用户成员关系
     * @param members 成员关系列表
     * @param userMap 用户资料映射
     * @param pendingChangeMap 待确认成员信息变更映射
     * @return 团队详情响应
     */
    private MineTeamDetailResponse buildDetailResponse(
            TeamEntity team,
            TeamMemberEntity membership,
            List<TeamMemberEntity> members,
            Map<Long, UserEntity> userMap,
            Map<Long, TeamMemberChangeRequestEntity> pendingChangeMap
    ) {
        // 成员展示时拥有者、管理者靠前，其余成员按成员关系 ID 稳定排序。
        List<TeamMemberEntity> safeMembers = safeList(members).stream()
                .sorted(Comparator.comparing(this::roleOrder).thenComparing(TeamMemberEntity::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        MineTeamDetailResponse response = new MineTeamDetailResponse();
        MineTeamDetailResponse.TeamInfo info = new MineTeamDetailResponse.TeamInfo();
        info.setTeamId(team.getId());
        info.setUniqueCode(defaultString(team.getUniqueCode()));
        info.setName(defaultString(team.getName()));
        info.setAvatarUrl(defaultString(team.getAvatarUrl()));
        info.setIntro(defaultString(team.getIntro()));
        info.setCity(defaultString(team.getCity()));
        info.setRole(defaultString(membership.getRole()));
        info.setRoleText(roleText(membership.getRole()));
        info.setCanMaintain(canMaintain(membership.getRole()));
        info.setCanManageMembers(canManageMembers(membership.getRole()));
        info.setMemberCount(safeMembers.isEmpty() ? 1 : safeMembers.size());
        info.setMemberCountText(info.getMemberCount() + " 位成员");
        response.setTeam(info);
        response.setMembers(safeMembers.stream()
                .map(member -> buildMemberItem(member, userMap.get(member.getUserId()), pendingChangeMap.get(member.getId())))
                .toList());
        return response;
    }

    /**
     * 构建成员列表项。
     *
     * @param member 成员关系
     * @param user 用户资料
     * @return 成员列表项
     */
    private MineTeamDetailResponse.MemberItem buildMemberItem(
            TeamMemberEntity member,
            UserEntity user,
            TeamMemberChangeRequestEntity pendingChange
    ) {
        String profession = firstPresent(member.getProfession(), user == null ? "" : user.getProfession());
        String nickname = user == null ? "微信用户" : defaultString(user.getNickname(), "微信用户");
        String userStatus = user == null ? "" : defaultString(user.getStatus(), UserStatusDict.ACTIVE.getCode());
        MineTeamDetailResponse.MemberItem item = new MineTeamDetailResponse.MemberItem();
        item.setMemberId(member.getId());
        item.setUserId(member.getUserId());
        item.setUniqueCode(user == null ? "" : defaultString(user.getUniqueCode()));
        item.setNickname(nickname);
        item.setAvatarUrl(user == null ? "" : defaultString(user.getAvatarUrl()));
        // 账号状态和团队加入状态是两套独立标签：停用账号仍保留真实资料，前端额外显示“已停用”。
        item.setUserStatus(userStatus);
        item.setUserStatusText(userStatusText(userStatus));
        item.setUserStatusTone(userStatusTone(userStatus));
        item.setProfession(profession);
        item.setDisplayName(buildDisplayName(nickname, profession));
        item.setRole(defaultString(member.getRole()));
        item.setRoleText(roleText(member.getRole()));
        // 加入状态标签始终展示，statusTone 直接对应小程序 .role-pill 的颜色 token。
        item.setJoinStatus(defaultString(member.getJoinStatus()));
        item.setJoinStatusText(joinStatusText(member.getJoinStatus()));
        item.setStatusTone(statusTone(member.getJoinStatus()));
        item.setAllowPortfolio(isEnabled(member.getAllowPortfolio()));
        item.setAllowProfile(isEnabled(member.getAllowProfile()));
        item.setAllowWorks(isEnabled(member.getAllowWorks()));
        if (pendingChange != null) {
            item.setPendingChange(true);
            item.setPendingChangeId(pendingChange.getId());
            item.setPendingChangeText(PENDING_MEMBER_CHANGE_TEXT);
        }
        return item;
    }

    /**
     * 构建团队摘要。
     *
     * @param items 团队列表项
     * @return 摘要
     */
    private MineTeamListResponse.Summary buildSummary(List<MineTeamListResponse.TeamItem> items) {
        int ownerCount = (int) items.stream()
                .filter(item -> TeamRoleDict.OWNER.getCode().equals(item.getRole()))
                .count();
        int manageableCount = (int) items.stream()
                .filter(MineTeamListResponse.TeamItem::isCanMaintain)
                .count();
        MineTeamListResponse.Summary summary = new MineTeamListResponse.Summary();
        summary.setJoinedCount(items.size());
        summary.setOwnerCount(ownerCount);
        summary.setManageableCount(manageableCount);
        summary.setSummaryText("已加入 " + items.size() + " 个团队，其中 " + ownerCount + " 个为拥有者。");
        return summary;
    }

    /**
     * 构建空列表响应。
     *
     * @return 空列表响应
     */
    private MineTeamListResponse emptyListResponse() {
        MineTeamListResponse response = new MineTeamListResponse();
        response.setTeams(Collections.emptyList());
        response.setSummary(buildSummary(Collections.emptyList()));
        return response;
    }

    /**
     * 查询活跃团队。
     *
     * @param teamId 团队 ID
     * @return 活跃团队
     */
    private TeamEntity requireActiveTeam(Long teamId) {
        if (teamId == null) {
            throw new BusinessException("团队不存在或无访问权限");
        }
        TeamEntity team = teamEntityMapper.selectById(teamId);
        if (team == null || !TeamStatusDict.ACTIVE.getCode().equals(team.getStatus())) {
            throw new BusinessException("团队不存在或无访问权限");
        }
        return team;
    }

    /**
     * 查询当前用户已加入的团队成员关系。
     *
     * @param teamId 团队 ID
     * @param userId 用户 ID
     * @param message 异常文案
     * @return 成员关系
     */
    private TeamMemberEntity requireJoinedMembership(Long teamId, Long userId, String message) {
        TeamMemberEntity membership = teamMemberEntityMapper.selectOne(
                Wrappers.lambdaQuery(TeamMemberEntity.class)
                        .eq(TeamMemberEntity::getTeamId, teamId)
                        .eq(TeamMemberEntity::getUserId, userId)
                        .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
                        .last("LIMIT 1")
        );
        if (membership == null) {
            throw new BusinessException(message);
        }
        return membership;
    }

    /**
     * 查询当前用户的拥有者成员关系。
     *
     * @param teamId 团队 ID
     * @param userId 用户 ID
     * @return 拥有者成员关系
     */
    private TeamMemberEntity requireOwnerMembership(Long teamId, Long userId) {
        TeamMemberEntity membership = requireJoinedMembership(teamId, userId, "团队不存在或无访问权限");
        if (!canManageMembers(membership.getRole())) {
            throw new BusinessException("无团队成员维护权限");
        }
        return membership;
    }

    /**
     * 查询团队内指定用户的成员关系。
     *
     * @param teamId 团队 ID
     * @param userId 用户 ID
     * @return 成员关系，不存在时返回 null
     */
    private TeamMemberEntity findMembership(Long teamId, Long userId) {
        return teamMemberEntityMapper.selectOne(
                Wrappers.lambdaQuery(TeamMemberEntity.class)
                        .eq(TeamMemberEntity::getTeamId, teamId)
                        .eq(TeamMemberEntity::getUserId, userId)
                        .last("LIMIT 1")
        );
    }

    /**
     * 按成员关系 ID 查询团队内已加入成员。
     *
     * @param teamId 团队 ID
     * @param memberId 成员关系 ID
     * @param message 异常文案
     * @return 成员关系
     */
    private TeamMemberEntity requireJoinedTeamMemberById(Long teamId, Long memberId, String message) {
        if (memberId == null) {
            throw new BusinessException(message);
        }
        TeamMemberEntity member = teamMemberEntityMapper.selectById(memberId);
        if (member == null
                || !Objects.equals(teamId, member.getTeamId())
                || !JoinStatusDict.JOINED.getCode().equals(member.getJoinStatus())) {
            throw new BusinessException(message);
        }
        return member;
    }

    /**
     * 确认成员没有待处理的信息变更。
     *
     * @param memberId 成员关系 ID
     */
    private void assertNoPendingMemberChange(Long memberId) {
        Long count = teamMemberChangeRequestEntityMapper.selectCount(
                Wrappers.lambdaQuery(TeamMemberChangeRequestEntity.class)
                        .eq(TeamMemberChangeRequestEntity::getMemberId, memberId)
                        .eq(TeamMemberChangeRequestEntity::getStatus,
                                TeamMemberChangeStatusDict.PENDING_CONFIRMATION.getCode())
        );
        if (count != null && count > 0) {
            throw new BusinessException("已有信息变更待同意");
        }
    }

    /**
     * 判断提交内容是否改变成员信息。
     *
     * @param member 当前成员
     * @param roleAfter 修改后角色
     * @param professionAfter 修改后团队身份
     * @param allowPortfolioAfter 修改后作品集权限
     * @param allowProfileAfter 修改后资料权限
     * @param allowWorksAfter 修改后作品素材权限
     * @return 是否发生变化
     */
    private boolean hasMemberChange(
            TeamMemberEntity member,
            String roleAfter,
            String professionAfter,
            int allowPortfolioAfter,
            int allowProfileAfter,
            int allowWorksAfter
    ) {
        return !Objects.equals(defaultString(member.getRole()), roleAfter)
                || !Objects.equals(defaultString(member.getProfession()), professionAfter)
                || flagOrZero(member.getAllowPortfolio()) != allowPortfolioAfter
                || flagOrZero(member.getAllowProfile()) != allowProfileAfter
                || flagOrZero(member.getAllowWorks()) != allowWorksAfter;
    }

    /**
     * 按个人唯一码查询可邀请的启用用户。
     *
     * @param uniqueCode 个人唯一码
     * @return 启用用户
     */
    private UserEntity requireActiveUserByUniqueCode(String uniqueCode) {
        String normalized = normalizeRequiredString(uniqueCode, UNIQUE_CODE_MAX_LENGTH, "个人唯一码");
        UserEntity user = userEntityMapper.selectOne(
                Wrappers.lambdaQuery(UserEntity.class)
                        .eq(UserEntity::getUniqueCode, normalized)
                        .eq(UserEntity::getStatus, UserStatusDict.ACTIVE.getCode())
                        .last("LIMIT 1")
        );
        if (user == null) {
            throw new BusinessException("用户不存在或已停用");
        }
        return user;
    }

    /**
     * 设置候选人可邀请状态。
     *
     * @param response 候选人响应
     * @param currentUserId 当前用户 ID
     * @param existing 既有成员关系
     */
    private void applyCandidateInviteState(
            MineTeamMemberCandidateResponse response,
            Long currentUserId,
            TeamMemberEntity existing
    ) {
        if (currentUserId.equals(response.getUserId())) {
            response.setCanInvite(false);
            response.setReason("不能邀请自己");
            return;
        }
        if (existing == null) {
            response.setCanInvite(true);
            response.setReason("可添加");
            return;
        }
        if (JoinStatusDict.JOINED.getCode().equals(existing.getJoinStatus())) {
            response.setCanInvite(false);
            response.setReason("成员已加入团队");
            return;
        }
        if (JoinStatusDict.PENDING_CONFIRMATION.getCode().equals(existing.getJoinStatus())) {
            response.setCanInvite(false);
            response.setReason("已有待确认邀请");
            return;
        }
        response.setCanInvite(true);
        response.setReason("可重新邀请");
    }

    /**
     * 新增待确认成员关系。
     *
     * @param teamId 团队 ID
     * @param inviterUserId 邀请人用户 ID
     * @param inviteeUserId 被邀请人用户 ID
     * @param role 团队角色
     * @param profession 团队身份
     * @param request 邀请请求
     * @return 成员关系
     */
    private TeamMemberEntity insertPendingMembership(
            Long teamId,
            Long inviterUserId,
            Long inviteeUserId,
            String role,
            String profession,
            MineTeamMemberInviteRequest request
    ) {
        LocalDateTime now = LocalDateTime.now();
        TeamMemberEntity membership = new TeamMemberEntity();
        membership.setTeamId(teamId);
        membership.setUserId(inviteeUserId);
        membership.setRole(role);
        membership.setProfession(defaultString(profession));
        membership.setJoinStatus(JoinStatusDict.PENDING_CONFIRMATION.getCode());
        membership.setAllowPortfolio(permissionFlag(request.getAllowPortfolio(), true));
        membership.setAllowProfile(permissionFlag(request.getAllowProfile(), true));
        membership.setAllowWorks(permissionFlag(request.getAllowWorks(), false));
        membership.setInvitedBy(inviterUserId);
        membership.setInvitedAt(now);
        try {
            teamMemberEntityMapper.insert(membership);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(PENDING_INVITATION_EXISTS_MESSAGE, e);
        }
        return membership;
    }

    /**
     * 将已拒绝或已移除成员关系恢复为待确认邀请。
     *
     * @param membership 既有成员关系
     * @param inviterUserId 邀请人用户 ID
     * @param role 团队角色
     * @param profession 团队身份
     * @param request 邀请请求
     */
    private void restorePendingMembership(
            TeamMemberEntity membership,
            Long inviterUserId,
            String role,
            String profession,
            MineTeamMemberInviteRequest request
    ) {
        if (JoinStatusDict.JOINED.getCode().equals(membership.getJoinStatus())) {
            throw new BusinessException("成员已加入团队");
        }
        if (JoinStatusDict.PENDING_CONFIRMATION.getCode().equals(membership.getJoinStatus())) {
            throw new BusinessException("已有待确认邀请");
        }
        LocalDateTime now = LocalDateTime.now();
        int allowPortfolio = permissionFlag(request.getAllowPortfolio(), true);
        int allowProfile = permissionFlag(request.getAllowProfile(), true);
        int allowWorks = permissionFlag(request.getAllowWorks(), false);
        UpdateWrapper<TeamMemberEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(COLUMN_ID, membership.getId())
                .eq(COLUMN_JOIN_STATUS, membership.getJoinStatus())
                .set(COLUMN_JOIN_STATUS, JoinStatusDict.PENDING_CONFIRMATION.getCode())
                .set(COLUMN_ROLE, role)
                .set(COLUMN_PROFESSION, defaultString(profession))
                .set(COLUMN_ALLOW_PORTFOLIO, allowPortfolio)
                .set(COLUMN_ALLOW_PROFILE, allowProfile)
                .set(COLUMN_ALLOW_WORKS, allowWorks)
                .set(COLUMN_INVITED_BY, inviterUserId)
                .set(COLUMN_INVITED_AT, now)
                .set(COLUMN_RESPONDED_AT, null)
                .set(COLUMN_JOINED_AT, null)
                .set(COLUMN_REMOVED_AT, null)
                .set(COLUMN_REMOVAL_REASON, "");
        int updated = teamMemberEntityMapper.update(new TeamMemberEntity(), updateWrapper);
        if (updated <= 0) {
            throw new BusinessException("成员邀请保存失败，请重试");
        }
        membership.setJoinStatus(JoinStatusDict.PENDING_CONFIRMATION.getCode());
        membership.setRole(role);
        membership.setProfession(defaultString(profession));
        membership.setAllowPortfolio(allowPortfolio);
        membership.setAllowProfile(allowProfile);
        membership.setAllowWorks(allowWorks);
        membership.setInvitedBy(inviterUserId);
        membership.setInvitedAt(now);
        membership.setRespondedAt(null);
        membership.setJoinedAt(null);
        membership.setRemovedAt(null);
        membership.setRemovalReason("");
    }

    /**
     * 创建团队邀请站内消息。
     *
     * @param team 团队实体
     * @param membership 成员关系
     * @param invitee 被邀请用户
     * @param inviter 邀请人
     */
    private void createInvitationMessage(
            TeamEntity team,
            TeamMemberEntity membership,
            UserEntity invitee,
            UserEntity inviter
    ) {
        if (membership.getId() == null) {
            log.warn("团队邀请成员关系未回填 ID: teamId={}, inviteeUserId={}", team.getId(), invitee.getId());
            throw new BusinessException(TEAM_INVITATION_SAVE_FAILED_MESSAGE);
        }
        String memberIdentity = String.valueOf(membership.getId());
        String actionUrl = INVITATION_ACTION_URL_PREFIX + memberIdentity;
        String idempotencyKey = INVITATION_IDEMPOTENCY_PREFIX + memberIdentity;
        LocalDateTime now = LocalDateTime.now();
        SystemMessageEntity message = new SystemMessageEntity();
        message.setUserId(invitee.getId());
        message.setMessageType(MessageTypeDict.TEAM_INVITATION.getCode());
        message.setCategory(MessageCategoryDict.TEAM.getCode());
        message.setReadStatus(MessageReadStatusDict.UNREAD.getCode());
        message.setTitle(INVITATION_MESSAGE_TITLE);
        message.setContent(buildInvitationMessageContent(team, inviter));
        message.setActionType(MessageActionTypeDict.TEAM_INVITATION.getCode());
        message.setActionUrl(actionUrl);
        message.setBizType(INVITATION_MESSAGE_BIZ_TYPE);
        message.setBizId(membership.getId());
        message.setIdempotencyKey(idempotencyKey);
        try {
            systemMessageEntityMapper.insert(message);
        } catch (DuplicateKeyException e) {
            UpdateWrapper<SystemMessageEntity> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq(COLUMN_IDEMPOTENCY_KEY, idempotencyKey)
                    .set(COLUMN_READ_STATUS, MessageReadStatusDict.UNREAD.getCode())
                    .set(COLUMN_READ_AT, null)
                    .set(COLUMN_TITLE, INVITATION_MESSAGE_TITLE)
                    .set(COLUMN_CONTENT, message.getContent())
                    .set(COLUMN_ACTION_TYPE, MessageActionTypeDict.TEAM_INVITATION.getCode())
                    .set(COLUMN_ACTION_URL, actionUrl)
                    .set(COLUMN_BIZ_ID, membership.getId());
            systemMessageEntityMapper.update(new SystemMessageEntity(), updateWrapper);
            log.info("团队邀请站内消息已存在，已刷新: teamId={}, memberId={}, userId={}",
                    team.getId(), membership.getId(), invitee.getId());
        }
    }

    /**
     * 构建团队邀请消息正文。
     *
     * @param team 团队实体
     * @param inviter 邀请人
     * @return 消息正文
     */
    private String buildInvitationMessageContent(TeamEntity team, UserEntity inviter) {
        String inviterName = inviter == null
                ? INVITER_FALLBACK_NAME
                : buildDisplayName(defaultString(inviter.getNickname(), INVITER_FALLBACK_NAME), inviter.getProfession());
        return inviterName + INVITATION_CONTENT_MIDDLE + defaultString(team.getName(), "未命名团队") + INVITATION_CONTENT_SUFFIX;
    }

    /**
     * 将团队邀请详情对应的站内消息标记为已读。
     *
     * @param memberId 成员关系 ID
     * @param userId 当前用户 ID
     */
    private void markInvitationMessageRead(Long memberId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<SystemMessageEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(COLUMN_USER_ID, userId)
                .eq(COLUMN_IDEMPOTENCY_KEY, INVITATION_IDEMPOTENCY_PREFIX + memberId)
                .eq(COLUMN_READ_STATUS, MessageReadStatusDict.UNREAD.getCode())
                .set(COLUMN_READ_STATUS, MessageReadStatusDict.READ.getCode())
                .set(COLUMN_READ_AT, now);
        systemMessageEntityMapper.update(new SystemMessageEntity(), updateWrapper);
    }

    /**
     * 邀请已响应后清理消息动作，避免消息列表继续展示“去处理”。
     *
     * @param memberId 成员关系 ID
     * @param userId 当前用户 ID
     */
    private void clearInvitationMessageAction(Long memberId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<SystemMessageEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(COLUMN_USER_ID, userId)
                .eq(COLUMN_IDEMPOTENCY_KEY, INVITATION_IDEMPOTENCY_PREFIX + memberId)
                .set(COLUMN_ACTION_TYPE, MessageActionTypeDict.NONE.getCode())
                .set(COLUMN_ACTION_URL, EMPTY_ACTION_URL);
        systemMessageEntityMapper.update(new SystemMessageEntity(), updateWrapper);
    }

    /**
     * 创建团队成员信息变更站内消息。
     *
     * @param team 团队实体
     * @param changeRequest 变更请求
     */
    private void createMemberChangeMessage(TeamEntity team, TeamMemberChangeRequestEntity changeRequest) {
        if (changeRequest.getId() == null) {
            throw new BusinessException(MEMBER_CHANGE_SAVE_FAILED_MESSAGE);
        }
        String changeIdentity = String.valueOf(changeRequest.getId());
        String actionUrl = MEMBER_CHANGE_ACTION_URL_PREFIX + changeIdentity;
        String idempotencyKey = MEMBER_CHANGE_IDEMPOTENCY_PREFIX + changeIdentity;
        LocalDateTime now = LocalDateTime.now();
        SystemMessageEntity message = new SystemMessageEntity();
        message.setUserId(changeRequest.getTargetUserId());
        message.setMessageType(MessageTypeDict.TEAM_ROLE_CHANGED.getCode());
        message.setCategory(MessageCategoryDict.TEAM.getCode());
        message.setReadStatus(MessageReadStatusDict.UNREAD.getCode());
        message.setTitle(MEMBER_CHANGE_MESSAGE_TITLE);
        message.setContent("团队「" + defaultString(team.getName(), "未命名团队") + "」的成员信息有变更，请确认后生效。");
        message.setActionType(MessageActionTypeDict.TEAM_MEMBER_CHANGE.getCode());
        message.setActionUrl(actionUrl);
        message.setBizType(MEMBER_CHANGE_MESSAGE_BIZ_TYPE);
        message.setBizId(changeRequest.getId());
        message.setIdempotencyKey(idempotencyKey);
        try {
            systemMessageEntityMapper.insert(message);
        } catch (DuplicateKeyException e) {
            UpdateWrapper<SystemMessageEntity> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq(COLUMN_IDEMPOTENCY_KEY, idempotencyKey)
                    .set(COLUMN_READ_STATUS, MessageReadStatusDict.UNREAD.getCode())
                    .set(COLUMN_READ_AT, null)
                    .set(COLUMN_TITLE, MEMBER_CHANGE_MESSAGE_TITLE)
                    .set(COLUMN_CONTENT, message.getContent())
                    .set(COLUMN_ACTION_TYPE, MessageActionTypeDict.TEAM_MEMBER_CHANGE.getCode())
                    .set(COLUMN_ACTION_URL, actionUrl)
                    .set(COLUMN_BIZ_ID, changeRequest.getId());
            systemMessageEntityMapper.update(new SystemMessageEntity(), updateWrapper);
        }
    }

    /**
     * 将团队成员信息变更消息标记为已读。
     *
     * @param changeRequestId 变更请求 ID
     * @param userId 当前用户 ID
     */
    private void markMemberChangeMessageRead(Long changeRequestId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<SystemMessageEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(COLUMN_USER_ID, userId)
                .eq(COLUMN_IDEMPOTENCY_KEY, MEMBER_CHANGE_IDEMPOTENCY_PREFIX + changeRequestId)
                .eq(COLUMN_READ_STATUS, MessageReadStatusDict.UNREAD.getCode())
                .set(COLUMN_READ_STATUS, MessageReadStatusDict.READ.getCode())
                .set(COLUMN_READ_AT, now);
        systemMessageEntityMapper.update(new SystemMessageEntity(), updateWrapper);
    }

    /**
     * 清理团队成员信息变更消息动作。
     *
     * @param changeRequestId 变更请求 ID
     * @param userId 当前用户 ID
     */
    private void clearMemberChangeMessageAction(Long changeRequestId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<SystemMessageEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(COLUMN_USER_ID, userId)
                .eq(COLUMN_IDEMPOTENCY_KEY, MEMBER_CHANGE_IDEMPOTENCY_PREFIX + changeRequestId)
                .set(COLUMN_ACTION_TYPE, MessageActionTypeDict.NONE.getCode())
                .set(COLUMN_ACTION_URL, EMPTY_ACTION_URL);
        systemMessageEntityMapper.update(new SystemMessageEntity(), updateWrapper);
    }

    /**
     * 更新团队成员信息变更状态。
     *
     * @param changeRequestId 变更请求 ID
     * @param status 目标状态
     * @param respondedAt 响应时间
     */
    private void updateMemberChangeStatus(Long changeRequestId, String status, LocalDateTime respondedAt) {
        UpdateWrapper<TeamMemberChangeRequestEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(COLUMN_ID, changeRequestId)
                .eq(COLUMN_STATUS, TeamMemberChangeStatusDict.PENDING_CONFIRMATION.getCode())
                .set(COLUMN_STATUS, status)
                .set(COLUMN_RESPONDED_AT, respondedAt);
        int updated = teamMemberChangeRequestEntityMapper.update(new TeamMemberChangeRequestEntity(), updateWrapper);
        if (updated <= 0) {
            throw new BusinessException("成员信息变更状态已变化，请刷新后重试");
        }
    }

    /**
     * 将团队成员信息变更置为失效。
     *
     * @param changeRequestId 变更请求 ID
     */
    private void invalidateMemberChange(Long changeRequestId) {
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<TeamMemberChangeRequestEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(COLUMN_ID, changeRequestId)
                .eq(COLUMN_STATUS, TeamMemberChangeStatusDict.PENDING_CONFIRMATION.getCode())
                .set(COLUMN_STATUS, TeamMemberChangeStatusDict.INVALIDATED.getCode())
                .set(COLUMN_RESPONDED_AT, now);
        teamMemberChangeRequestEntityMapper.update(new TeamMemberChangeRequestEntity(), updateWrapper);
    }

    /**
     * 将成员所有待确认信息变更置为失效。
     *
     * @param memberId 成员关系 ID
     */
    private void invalidatePendingChangesForMember(Long memberId) {
        List<TeamMemberChangeRequestEntity> pendingChanges = safeList(teamMemberChangeRequestEntityMapper.selectList(
                Wrappers.lambdaQuery(TeamMemberChangeRequestEntity.class)
                        .eq(TeamMemberChangeRequestEntity::getMemberId, memberId)
                        .eq(TeamMemberChangeRequestEntity::getStatus,
                                TeamMemberChangeStatusDict.PENDING_CONFIRMATION.getCode())
        ));
        for (TeamMemberChangeRequestEntity changeRequest : pendingChanges) {
            invalidateMemberChange(changeRequest.getId());
            clearMemberChangeMessageAction(changeRequest.getId(), changeRequest.getTargetUserId());
        }
    }

    /**
     * 查询当前用户可查看的邀请。
     *
     * @param memberId 成员关系 ID
     * @param userId 当前用户 ID
     * @return 成员关系
     */
    private TeamMemberEntity requireInvitationForCurrentUser(Long memberId, Long userId) {
        if (memberId == null) {
            throw new BusinessException("团队邀请不存在");
        }
        TeamMemberEntity invitation = teamMemberEntityMapper.selectById(memberId);
        if (invitation == null || !userId.equals(invitation.getUserId())) {
            throw new BusinessException("团队邀请不存在");
        }
        return invitation;
    }

    /**
     * 查询当前用户待确认邀请。
     *
     * @param memberId 成员关系 ID
     * @param userId 当前用户 ID
     * @return 待确认成员关系
     */
    private TeamMemberEntity requirePendingInvitationForCurrentUser(Long memberId, Long userId) {
        TeamMemberEntity invitation = requireInvitationForCurrentUser(memberId, userId);
        if (!JoinStatusDict.PENDING_CONFIRMATION.getCode().equals(invitation.getJoinStatus())) {
            throw new BusinessException("团队邀请已处理");
        }
        return invitation;
    }

    /**
     * 查询当前用户可查看的成员信息变更。
     *
     * @param request 详情请求
     * @param userId 当前用户 ID
     * @return 变更请求
     */
    private TeamMemberChangeRequestEntity requireMemberChangeForCurrentUser(
            MineTeamMemberChangeDetailRequest request,
            Long userId
    ) {
        Long changeRequestId = request == null ? null : request.getChangeRequestId();
        if (changeRequestId == null) {
            throw new BusinessException("成员信息变更不存在");
        }
        TeamMemberChangeRequestEntity changeRequest = teamMemberChangeRequestEntityMapper.selectById(changeRequestId);
        if (changeRequest == null || !userId.equals(changeRequest.getTargetUserId())) {
            throw new BusinessException("成员信息变更不存在");
        }
        return changeRequest;
    }

    /**
     * 查询当前用户待确认成员信息变更。
     *
     * @param request 详情请求
     * @param userId 当前用户 ID
     * @return 待确认变更请求
     */
    private TeamMemberChangeRequestEntity requirePendingMemberChangeForCurrentUser(
            MineTeamMemberChangeDetailRequest request,
            Long userId
    ) {
        TeamMemberChangeRequestEntity changeRequest = requireMemberChangeForCurrentUser(request, userId);
        if (!TeamMemberChangeStatusDict.PENDING_CONFIRMATION.getCode().equals(changeRequest.getStatus())) {
            throw new BusinessException("成员信息变更已处理");
        }
        return changeRequest;
    }

    /**
     * 构建团队邀请响应。
     *
     * @param invitation 成员关系
     * @param team 团队实体
     * @return 团队邀请响应
     */
    private MineTeamInvitationResponse buildInvitationResponse(TeamMemberEntity invitation, TeamEntity team) {
        List<Long> userIds = new ArrayList<>();
        userIds.add(invitation.getUserId());
        if (invitation.getInvitedBy() != null) {
            userIds.add(invitation.getInvitedBy());
        }
        Map<Long, UserEntity> users = loadUsers(userIds);
        UserEntity inviter = users.get(invitation.getInvitedBy());
        MineTeamInvitationResponse response = new MineTeamInvitationResponse();
        response.setMemberId(invitation.getId());
        response.setTeamId(team.getId());
        response.setTeamUniqueCode(defaultString(team.getUniqueCode()));
        response.setTeamName(defaultString(team.getName()));
        response.setTeamAvatarUrl(defaultString(team.getAvatarUrl()));
        response.setInviterUserId(invitation.getInvitedBy());
        response.setInviterName(inviter == null
                ? INVITER_FALLBACK_NAME
                : buildDisplayName(defaultString(inviter.getNickname(), INVITER_FALLBACK_NAME), inviter.getProfession()));
        response.setRole(defaultString(invitation.getRole()));
        response.setRoleText(roleText(invitation.getRole()));
        response.setProfession(defaultString(invitation.getProfession()));
        response.setAllowPortfolio(isEnabled(invitation.getAllowPortfolio()));
        response.setAllowProfile(isEnabled(invitation.getAllowProfile()));
        response.setAllowWorks(isEnabled(invitation.getAllowWorks()));
        response.setJoinStatus(defaultString(invitation.getJoinStatus()));
        response.setJoinStatusText(joinStatusText(invitation.getJoinStatus()));
        response.setStatusTone(statusTone(invitation.getJoinStatus()));
        response.setCanRespond(JoinStatusDict.PENDING_CONFIRMATION.getCode().equals(invitation.getJoinStatus()));
        return response;
    }

    /**
     * 构建团队成员信息变更详情响应。
     *
     * @param changeRequest 变更请求
     * @param team 团队实体
     * @param currentUserId 当前用户 ID
     * @return 详情响应
     */
    private MineTeamMemberChangeDetailResponse buildMemberChangeDetailResponse(
            TeamMemberChangeRequestEntity changeRequest,
            TeamEntity team,
            Long currentUserId
    ) {
        Map<Long, UserEntity> users = loadUsers(List.of(
                changeRequest.getTargetUserId(),
                changeRequest.getRequestedByUserId()
        ));
        UserEntity target = users.get(changeRequest.getTargetUserId());
        UserEntity requester = users.get(changeRequest.getRequestedByUserId());
        MineTeamMemberChangeDetailResponse response = new MineTeamMemberChangeDetailResponse();
        response.setChangeRequestId(changeRequest.getId());
        response.setTeamId(team.getId());
        response.setTeamName(defaultString(team.getName()));
        response.setTeamAvatarUrl(defaultString(team.getAvatarUrl()));
        response.setMemberId(changeRequest.getMemberId());
        response.setTargetUserId(changeRequest.getTargetUserId());
        response.setTargetName(userDisplayName(target, "成员"));
        response.setRequesterUserId(changeRequest.getRequestedByUserId());
        response.setRequesterName(userDisplayName(requester, INVITER_FALLBACK_NAME));
        response.setStatus(defaultString(changeRequest.getStatus()));
        response.setStatusText(memberChangeStatusText(changeRequest.getStatus()));
        response.setCanRespond(currentUserId.equals(changeRequest.getTargetUserId())
                && TeamMemberChangeStatusDict.PENDING_CONFIRMATION.getCode().equals(changeRequest.getStatus()));
        response.setRoleBefore(defaultString(changeRequest.getRoleBefore()));
        response.setRoleBeforeText(roleText(changeRequest.getRoleBefore()));
        response.setRoleAfter(defaultString(changeRequest.getRoleAfter()));
        response.setRoleAfterText(roleText(changeRequest.getRoleAfter()));
        response.setProfessionBefore(defaultString(changeRequest.getProfessionBefore()));
        response.setProfessionAfter(defaultString(changeRequest.getProfessionAfter()));
        response.setAllowPortfolioBefore(isEnabled(changeRequest.getAllowPortfolioBefore()));
        response.setAllowPortfolioAfter(isEnabled(changeRequest.getAllowPortfolioAfter()));
        response.setAllowProfileBefore(isEnabled(changeRequest.getAllowProfileBefore()));
        response.setAllowProfileAfter(isEnabled(changeRequest.getAllowProfileAfter()));
        response.setAllowWorksBefore(isEnabled(changeRequest.getAllowWorksBefore()));
        response.setAllowWorksAfter(isEnabled(changeRequest.getAllowWorksAfter()));
        response.setPermissionBeforeText(permissionText(
                response.isAllowPortfolioBefore(), response.isAllowProfileBefore(), response.isAllowWorksBefore()));
        response.setPermissionAfterText(permissionText(
                response.isAllowPortfolioAfter(), response.isAllowProfileAfter(), response.isAllowWorksAfter()));
        response.setRequestedAtText(formatDetailTime(changeRequest.getRequestedAt()));
        response.setRespondedAtText(formatDetailTime(changeRequest.getRespondedAt()));
        return response;
    }

    /**
     * 读取成员待确认信息变更映射。
     *
     * @param teamId 团队 ID
     * @param memberIds 成员关系 ID 集合
     * @return 成员关系 ID 到变更请求的映射
     */
    private Map<Long, TeamMemberChangeRequestEntity> loadPendingChangeMap(Long teamId, Collection<Long> memberIds) {
        Set<Long> ids = memberIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return safeList(teamMemberChangeRequestEntityMapper.selectList(
                Wrappers.lambdaQuery(TeamMemberChangeRequestEntity.class)
                        .eq(TeamMemberChangeRequestEntity::getTeamId, teamId)
                        .in(TeamMemberChangeRequestEntity::getMemberId, ids)
                        .eq(TeamMemberChangeRequestEntity::getStatus,
                                TeamMemberChangeStatusDict.PENDING_CONFIRMATION.getCode())
        )).stream().collect(Collectors.toMap(
                TeamMemberChangeRequestEntity::getMemberId,
                changeRequest -> changeRequest,
                (left, right) -> left,
                LinkedHashMap::new
        ));
    }

    /**
     * 更新团队成员角色。
     *
     * @param memberId 成员关系 ID
     * @param versionBefore 修改前版本号
     * @param roleBefore 原角色
     * @param roleAfter 新角色
     * @param message 失败文案
     */
    private void updateMemberRole(
            Long memberId,
            Integer versionBefore,
            String roleBefore,
            String roleAfter,
            String message
    ) {
        UpdateWrapper<TeamMemberEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(COLUMN_ID, memberId)
                .eq(COLUMN_ROLE, roleBefore)
                .eq(COLUMN_JOIN_STATUS, JoinStatusDict.JOINED.getCode())
                .eq(COLUMN_VERSION, versionBefore)
                .set(COLUMN_ROLE, roleAfter)
                .setSql(VERSION_INCREMENT_SQL);
        int updated = teamMemberEntityMapper.update(new TeamMemberEntity(), updateWrapper);
        if (updated <= 0) {
            throw new BusinessException(message);
        }
    }

    /**
     * 构建团队拥有者转让通知幂等键。
     *
     * @param team 团队实体
     * @param target 目标成员关系
     * @return 幂等键
     */
    private String buildOwnerTransferIdempotencyKey(TeamEntity team, TeamMemberEntity target) {
        return OWNER_TRANSFER_IDEMPOTENCY_PREFIX
                + team.getId()
                + ":"
                + versionOrZero(team.getVersion())
                + ":"
                + target.getId();
    }

    /**
     * 构建成员移除通知幂等键。
     *
     * @param target 被移除成员关系
     * @return 幂等键
     */
    private String buildMemberRemovedIdempotencyKey(TeamMemberEntity target) {
        return MEMBER_REMOVED_IDEMPOTENCY_PREFIX
                + target.getId()
                + ":"
                + versionOrZero(target.getVersion());
    }

    /**
     * 将空版本号按 0 处理，便于构建确定性的幂等键。
     *
     * @param version 版本号
     * @return 非空版本号
     */
    private int versionOrZero(Integer version) {
        return version == null ? 0 : version;
    }

    /**
     * 计算内存实体中的下一个版本号。
     *
     * @param version 当前版本号
     * @return 下一个版本号
     */
    private Integer incrementVersion(Integer version) {
        return version == null ? 1 : version + 1;
    }

    /**
     * 创建无需用户处理的团队通知消息。
     *
     * @param targetUserId 接收用户 ID
     * @param title 标题
     * @param content 正文
     * @param teamId 团队 ID
     * @param idempotencyKey 幂等键
     */
    private void createPlainTeamNotice(Long targetUserId, String title, String content, Long teamId, String idempotencyKey) {
        LocalDateTime now = LocalDateTime.now();
        SystemMessageEntity message = new SystemMessageEntity();
        message.setUserId(targetUserId);
        message.setMessageType(MessageTypeDict.TEAM_ROLE_CHANGED.getCode());
        message.setCategory(MessageCategoryDict.TEAM.getCode());
        message.setReadStatus(MessageReadStatusDict.UNREAD.getCode());
        message.setTitle(title);
        message.setContent(content);
        message.setActionType(MessageActionTypeDict.NONE.getCode());
        message.setActionUrl(EMPTY_ACTION_URL);
        message.setBizType(TEAM_NOTICE_BIZ_TYPE);
        message.setBizId(teamId);
        message.setIdempotencyKey(idempotencyKey);
        try {
            systemMessageEntityMapper.insert(message);
        } catch (DuplicateKeyException e) {
            UpdateWrapper<SystemMessageEntity> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq(COLUMN_IDEMPOTENCY_KEY, idempotencyKey)
                    .set(COLUMN_READ_STATUS, MessageReadStatusDict.UNREAD.getCode())
                    .set(COLUMN_READ_AT, null)
                    .set(COLUMN_TITLE, title)
                    .set(COLUMN_CONTENT, content)
                    .set(COLUMN_ACTION_TYPE, MessageActionTypeDict.NONE.getCode())
                    .set(COLUMN_ACTION_URL, EMPTY_ACTION_URL)
                    .set(COLUMN_BIZ_ID, teamId);
            systemMessageEntityMapper.update(new SystemMessageEntity(), updateWrapper);
        }
    }

    /**
     * 校验成员内容是否仍被团队作品集引用。
     *
     * @param teamId 团队 ID
     * @param memberUserId 成员用户 ID
     */
    private void assertMemberContentNotReferenced(Long teamId, Long memberUserId) {
        List<WorkEntity> works = safeList(workEntityMapper.selectList(
                Wrappers.lambdaQuery(WorkEntity.class)
                        .eq(WorkEntity::getUserId, memberUserId)
                        .eq(WorkEntity::getStatus, WorkStatusDict.ACTIVE.getCode())
        ));
        List<PortfolioEntity> memberPortfolios = safeList(portfolioEntityMapper.selectList(
                Wrappers.lambdaQuery(PortfolioEntity.class)
                        .eq(PortfolioEntity::getOwnerType, PortfolioOwnerTypeDict.USER.getCode())
                        .eq(PortfolioEntity::getOwnerId, memberUserId)
                        .eq(PortfolioEntity::getStatus, PortfolioStatusDict.ACTIVE.getCode())
        ));
        List<PortfolioEntity> teamPortfolios = safeList(portfolioEntityMapper.selectList(
                Wrappers.lambdaQuery(PortfolioEntity.class)
                        .eq(PortfolioEntity::getOwnerType, PortfolioOwnerTypeDict.TEAM.getCode())
                        .eq(PortfolioEntity::getOwnerId, teamId)
                        .eq(PortfolioEntity::getStatus, PortfolioStatusDict.ACTIVE.getCode())
        ));
        if (teamPortfolios.isEmpty()) {
            return;
        }
        Set<Long> workIds = works.stream().map(WorkEntity::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> memberPortfolioIds = memberPortfolios.stream()
                .map(PortfolioEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> teamPortfolioIds = teamPortfolios.stream()
                .map(PortfolioEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (teamPortfolioIds.isEmpty()) {
            return;
        }
        List<PortfolioReferenceEntity> references = safeList(portfolioReferenceEntityMapper.selectList(
                Wrappers.lambdaQuery(PortfolioReferenceEntity.class)
                        .in(PortfolioReferenceEntity::getPortfolioId, teamPortfolioIds)
                        .eq(PortfolioReferenceEntity::getIsValid, 1)
        ));
        Set<Long> referencedPortfolioIds = collectReferencedTeamPortfolioIds(
                references, workIds, memberPortfolioIds, memberUserId);
        if (referencedPortfolioIds.isEmpty()) {
            return;
        }
        Map<Long, String> titleMap = teamPortfolios.stream().collect(Collectors.toMap(
                PortfolioEntity::getId,
                portfolio -> defaultString(portfolio.getTitle(), "未命名作品集"),
                (left, right) -> left,
                LinkedHashMap::new
        ));
        List<String> names = referencedPortfolioIds.stream()
                .map(id -> titleMap.getOrDefault(id, "未命名作品集"))
                .toList();
        throw new BusinessException(formatRemoveBlockedMessage(names));
    }

    /**
     * 收集团队作品集中引用了成员内容的作品集 ID。
     *
     * @param references 引用记录
     * @param workIds 成员作品 ID
     * @param memberPortfolioIds 成员个人作品集 ID
     * @param memberUserId 成员用户 ID
     * @return 被阻塞的团队作品集 ID
     */
    private Set<Long> collectReferencedTeamPortfolioIds(
            List<PortfolioReferenceEntity> references,
            Set<Long> workIds,
            Set<Long> memberPortfolioIds,
            Long memberUserId
    ) {
        Set<Long> ids = new LinkedHashSet<>();
        for (PortfolioReferenceEntity reference : references) {
            if (reference == null || reference.getPortfolioId() == null) {
                continue;
            }
            String type = reference.getReferenceType();
            Long referenceId = reference.getReferenceId();
            boolean referenced = (ReferenceTypeDict.WORK.getCode().equals(type) && workIds.contains(referenceId))
                    || (ReferenceTypeDict.MEMBER_PORTFOLIO.getCode().equals(type) && memberPortfolioIds.contains(referenceId))
                    || (ReferenceTypeDict.USER_PROFILE.getCode().equals(type) && Objects.equals(memberUserId, referenceId));
            if (referenced) {
                ids.add(reference.getPortfolioId());
            }
        }
        return ids;
    }

    /**
     * 构建移除受阻提示。
     *
     * @param portfolioNames 作品集名称
     * @return 提示文案
     */
    private String formatRemoveBlockedMessage(List<String> portfolioNames) {
        List<String> names = portfolioNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
        if (names.size() <= 2) {
            return "无法移除，成员内容仍被" + quotePortfolioNames(names) + "使用，请先移除引用。";
        }
        return "无法移除，成员内容仍被" + quotePortfolioNames(names.subList(0, 2))
                + "等 " + names.size() + " 个作品集使用，请先移除引用。";
    }

    /**
     * 拼接作品集名称。
     *
     * @param names 作品集名称
     * @return 带书名号的名称串
     */
    private String quotePortfolioNames(List<String> names) {
        return names.stream().map(name -> "《" + name + "》").collect(Collectors.joining());
    }

    /**
     * 批量读取用户资料。
     *
     * @param userIds 用户 ID 集合
     * @return 用户资料映射
     */
    private Map<Long, UserEntity> loadUsers(Collection<Long> userIds) {
        Set<Long> ids = userIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, UserEntity> users = new HashMap<>();
        for (UserEntity user : safeList(userEntityMapper.selectBatchIds(ids))) {
            // 维护页需要知道停用成员的真实状态，不能在后端静默过滤成“微信用户”。
            // 非 ACTIVE 用户也放入映射，后续通过 userStatus/userStatusText/userStatusTone 告诉前端展示停用标签。
            if (user != null) {
                users.put(user.getId(), user);
            }
        }
        return users;
    }

    /**
     * 读取单个用户资料。
     *
     * @param userId 用户 ID
     * @return 用户资料
     */
    private UserEntity loadUser(Long userId) {
        if (userId == null) {
            return null;
        }
        return userEntityMapper.selectById(userId);
    }

    /**
     * 生成团队唯一码 — 委托统一生成器，前缀 TM。
     *
     * @return 团队唯一码
     */
    private String generateUniqueTeamCode() {
        return uniqueCodeGenerator.generate(UniqueCodeGenerator.TEAM_PREFIX, candidates ->
                teamEntityMapper.selectList(
                                Wrappers.<TeamEntity>query()
                                        .select("unique_code")
                                        .in("unique_code", candidates))
                        .stream()
                        .map(TeamEntity::getUniqueCode)
                        .collect(Collectors.toSet()));
    }

    /**
     * 请求字段存在时才覆盖实体字段。
     *
     * @param value 原始值
     * @param maxLength 最大长度
     * @param fieldName 字段名称
     * @param setter 实体字段赋值方法
     * @param updateSetter 更新字段赋值方法
     */
    private void applyStringField(
            String value,
            int maxLength,
            String fieldName,
            Consumer<String> setter,
            Consumer<String> updateSetter
    ) {
        if (value == null) {
            return;
        }
        String normalized = normalizeRequiredString(value, maxLength, fieldName);
        setter.accept(normalized);
        updateSetter.accept(normalized);
    }

    /**
     * 请求字段存在时才覆盖可选实体字段，允许清空。
     *
     * @param value 原始值
     * @param maxLength 最大长度
     * @param fieldName 字段名称
     * @param setter 实体字段赋值方法
     * @param updateSetter 更新字段赋值方法
     */
    private void applyOptionalStringField(
            String value,
            int maxLength,
            String fieldName,
            Consumer<String> setter,
            Consumer<String> updateSetter
    ) {
        if (value == null) {
            return;
        }
        String normalized = normalizeOptionalString(value, maxLength, fieldName);
        setter.accept(normalized);
        updateSetter.accept(normalized);
    }

    /**
     * 规范化必填字符串。
     *
     * @param value 原始值
     * @param maxLength 最大长度
     * @param fieldName 字段名称
     * @return 规范化字符串
     */
    private String normalizeRequiredString(String value, int maxLength, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new BusinessException(fieldName + "不能为空");
        }
        if (normalized.length() > maxLength) {
            throw new BusinessException(fieldName + "不能超过 " + maxLength + " 个字");
        }
        return normalized;
    }

    /**
     * 规范化可选字符串。
     *
     * @param value 原始值
     * @param maxLength 最大长度
     * @param fieldName 字段名称
     * @return 规范化字符串
     */
    private String normalizeOptionalString(String value, int maxLength, String fieldName) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new BusinessException(fieldName + "不能超过 " + maxLength + " 个字");
        }
        return normalized;
    }

    /**
     * 判断角色是否可维护团队。
     *
     * @param role 角色 code
     * @return 是否可维护
     */
    private boolean canMaintain(String role) {
        return TeamRoleDict.OWNER.getCode().equals(role) || TeamRoleDict.MANAGER.getCode().equals(role);
    }

    /**
     * 判断角色是否可维护团队成员。
     *
     * @param role 角色 code
     * @return 是否可维护团队成员
     */
    private boolean canManageMembers(String role) {
        return TeamRoleDict.OWNER.getCode().equals(role);
    }

    /**
     * 规范邀请角色。
     *
     * @param role 原始角色
     * @return 角色 code
     */
    private String normalizeInviteRole(String role) {
        String normalized = role == null ? "" : role.strip();
        if (TeamRoleDict.MANAGER.getCode().equals(normalized) || TeamRoleDict.MEMBER.getCode().equals(normalized)) {
            return normalized;
        }
        throw new BusinessException("团队角色无效");
    }

    /**
     * 将布尔权限转换成数据库标记。
     *
     * @param value 请求值
     * @param defaultValue 默认值
     * @return 0 或 1
     */
    private int permissionFlag(Boolean value, boolean defaultValue) {
        boolean enabled = value == null ? defaultValue : value;
        return enabled ? 1 : 0;
    }

    /**
     * 判断数据库权限标记是否开启。
     *
     * @param value 权限标记
     * @return 是否开启
     */
    private boolean isEnabled(Integer value) {
        return value != null && value == 1;
    }

    /**
     * 将数据库权限标记规整为 0 或 1。
     *
     * @param value 权限标记
     * @return 0 或 1
     */
    private int flagOrZero(Integer value) {
        return isEnabled(value) ? 1 : 0;
    }

    /**
     * 团队角色文案。
     *
     * @param role 角色 code
     * @return 文案
     */
    private String roleText(String role) {
        TeamRoleDict dict = TeamRoleDict.fromCode(role);
        return dict == null ? "普通成员" : dict.getDisplayName();
    }

    /**
     * 加入状态文案。
     *
     * @param joinStatus 加入状态 code
     * @return 文案
     */
    private String joinStatusText(String joinStatus) {
        JoinStatusDict dict = JoinStatusDict.fromCode(joinStatus);
        return dict == null ? "待确认" : dict.getDisplayName();
    }

    /**
     * 团队成员信息变更状态文案。
     *
     * @param status 状态 code
     * @return 文案
     */
    private String memberChangeStatusText(String status) {
        TeamMemberChangeStatusDict dict = TeamMemberChangeStatusDict.fromCode(status);
        return dict == null ? "待同意" : dict.getDisplayName();
    }

    /**
     * 用户账号状态文案。
     *
     * @param userStatus 用户状态 code
     * @return 文案
     */
    private String userStatusText(String userStatus) {
        if (userStatus == null || userStatus.isBlank()) {
            return "资料缺失";
        }
        if (UserStatusDict.DISABLED.getCode().equals(userStatus)) {
            return "已停用";
        }
        UserStatusDict dict = UserStatusDict.fromCode(userStatus);
        return dict == null ? userStatus : dict.getDisplayName();
    }

    /**
     * 用户账号状态色调。
     *
     * <p>这是后端返回给小程序的样式 token：ACTIVE 用 teal 表示正常；
     * DISABLED、资料缺失或未知状态统一用 muted，前端只在非 ACTIVE 时展示该账号状态标签。</p>
     *
     * @param userStatus 用户状态 code
     * @return 色调
     */
    private String userStatusTone(String userStatus) {
        if (UserStatusDict.ACTIVE.getCode().equals(userStatus)) {
            return "teal";
        }
        return "muted";
    }

    /**
     * 团队成员关系状态色调。
     *
     * <p>这是后端返回给小程序的样式 token，而不是业务枚举：</p>
     * <p>JOINED → teal，对应已加入的绿色标签；</p>
     * <p>PENDING_CONFIRMATION → amber，对应待确认的黄色标签；</p>
     * <p>其他状态（已拒绝、已移除、未知）→ muted，对应灰色弱化标签。</p>
     * <p>小程序在 {@code team-maintenance.wxml} 中用
     * {@code class="role-pill {{item.statusTone}}"} 直接消费该 token。</p>
     *
     * @param joinStatus 加入状态 code
     * @return 色调
     */
    private String statusTone(String joinStatus) {
        if (JoinStatusDict.JOINED.getCode().equals(joinStatus)) {
            return "teal";
        }
        if (JoinStatusDict.PENDING_CONFIRMATION.getCode().equals(joinStatus)) {
            return "amber";
        }
        return "muted";
    }

    /**
     * 角色排序值。
     *
     * @param member 成员关系
     * @return 排序值
     */
    private int roleOrder(TeamMemberEntity member) {
        if (TeamRoleDict.OWNER.getCode().equals(member.getRole())) {
            return 0;
        }
        if (TeamRoleDict.MANAGER.getCode().equals(member.getRole())) {
            return 1;
        }
        return 2;
    }

    /**
     * 构建展示名称。
     *
     * @param nickname 昵称
     * @param profession 团队身份
     * @return 展示名称
     */
    private String buildDisplayName(String nickname, String profession) {
        if (profession == null || profession.isBlank()) {
            return nickname;
        }
        return nickname + " · " + profession;
    }

    /**
     * 构建用户展示名称。
     *
     * @param user 用户实体
     * @param fallback 兜底名称
     * @return 展示名称
     */
    private String userDisplayName(UserEntity user, String fallback) {
        if (user == null) {
            return fallback;
        }
        return buildDisplayName(defaultString(user.getNickname(), fallback), user.getProfession());
    }

    /**
     * 构建成员引用权限文案。
     *
     * @param allowPortfolio 是否允许作品集
     * @param allowProfile 是否允许主页资料
     * @param allowWorks 是否允许作品素材
     * @return 权限文案
     */
    private String permissionText(boolean allowPortfolio, boolean allowProfile, boolean allowWorks) {
        List<String> names = new ArrayList<>();
        if (allowPortfolio) {
            names.add("作品集");
        }
        if (allowProfile) {
            names.add("主页资料");
        }
        if (allowWorks) {
            names.add("作品素材");
        }
        if (names.isEmpty()) {
            return "未开放";
        }
        return String.join("、", names);
    }

    /**
     * 格式化更新时间。
     *
     * @param updatedAt 更新时间
     * @return 更新时间文案
     */
    private String formatUpdatedAt(LocalDateTime updatedAt) {
        if (updatedAt == null) {
            return "最近更新 -";
        }
        return "最近更新 " + UPDATED_FORMATTER.format(updatedAt);
    }

    /**
     * 格式化详情时间。
     *
     * @param time 时间
     * @return 时间文案
     */
    private String formatDetailTime(LocalDateTime time) {
        if (time == null) {
            return "";
        }
        return DETAIL_TIME_FORMATTER.format(time);
    }

    /**
     * 返回第一个非空字符串。
     *
     * @param values 待选择字符串
     * @return 非空字符串
     */
    private String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    /**
     * 默认字符串。
     *
     * @param value 原字符串
     * @return 非空字符串
     */
    private String defaultString(String value) {
        return defaultString(value, "");
    }

    /**
     * 默认字符串。
     *
     * @param value 原字符串
     * @param fallback 兜底值
     * @return 非空字符串
     */
    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 将空列表兜底为空集合。
     *
     * @param list 原列表
     * @param <T> 元素类型
     * @return 非空列表
     */
    private <T> List<T> safeList(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }
}
