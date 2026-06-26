package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.dict.TeamStatusDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.FileUploadResponse;
import com.jxc.wefolio.dto.MineTeamCreateRequest;
import com.jxc.wefolio.dto.MineTeamDetailResponse;
import com.jxc.wefolio.dto.MineTeamListResponse;
import com.jxc.wefolio.dto.MineTeamUpdateRequest;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    /** 团队唯一码前缀 */
    private static final String TEAM_CODE_PREFIX = "TM";

    /** 团队唯一码随机字符 */
    private static final String TEAM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** 团队唯一码随机部分长度 */
    private static final int TEAM_CODE_RANDOM_LENGTH = 8;

    /** 团队名称最大长度 */
    private static final int NAME_MAX_LENGTH = 100;

    /** 团队简介最大长度 */
    private static final int INTRO_MAX_LENGTH = 1000;

    /** 团队图标地址最大长度 */
    private static final int AVATAR_URL_MAX_LENGTH = 512;

    /** 更新时间展示格式 */
    private static final DateTimeFormatter UPDATED_FORMATTER = DateTimeFormatter.ofPattern("MM-dd");

    /** 随机数生成器 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 团队 Mapper */
    private final TeamEntityMapper teamEntityMapper;

    /** 团队成员 Mapper */
    private final TeamMemberEntityMapper teamMemberEntityMapper;

    /** 用户 Mapper */
    private final UserEntityMapper userEntityMapper;

    /** COS 文件服务 */
    private final CosService cosService;

    /** 团队注册事务服务 */
    private final TeamRegistrationService teamRegistrationService;

    /** 积分服务 */
    private final PointService pointService;

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

        // 更新请求按“字段存在才覆盖”处理，便于团队图标上传后只补写 avatarUrl。
        UpdateWrapper<TeamEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", teamId);
        applyStringField(request.getName(), NAME_MAX_LENGTH, "团队名称",
                team::setName, value -> updateWrapper.set("name", value));
        applyOptionalStringField(request.getIntro(), INTRO_MAX_LENGTH, "团队简介",
                team::setIntro, value -> updateWrapper.set("intro", value));
        applyOptionalStringField(request.getAvatarUrl(), AVATAR_URL_MAX_LENGTH, "团队图标",
                team::setAvatarUrl, value -> updateWrapper.set("avatar_url", value));
        LocalDateTime updatedAt = LocalDateTime.now();
        team.setUpdatedAt(updatedAt);
        updateWrapper.set("updated_at", updatedAt);
        int updated = teamEntityMapper.update(null, updateWrapper);
        if (updated <= 0) {
            throw new BusinessException("团队保存失败，请重试");
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
        ));
        Map<Long, UserEntity> userMap = loadUsers(members.stream().map(TeamMemberEntity::getUserId).toList());
        return buildDetailResponse(team, currentMembership, members, userMap);
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
        info.setMemberCount(safeMembers.isEmpty() ? 1 : safeMembers.size());
        info.setMemberCountText(info.getMemberCount() + " 位成员");
        response.setTeam(info);
        response.setMembers(safeMembers.stream()
                .map(member -> buildMemberItem(member, userMap.get(member.getUserId())))
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
    private MineTeamDetailResponse.MemberItem buildMemberItem(TeamMemberEntity member, UserEntity user) {
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
     * 生成未使用的团队唯一码。
     *
     * @return 团队唯一码
     */
    private String generateUniqueTeamCode() {
        // TM + 8 位随机码碰撞概率很低；仍然查库重试，保证落库前唯一。
        for (int attempt = 0; attempt < 10; attempt += 1) {
            String code = TEAM_CODE_PREFIX + randomCode();
            Long count = teamEntityMapper.selectCount(
                    Wrappers.lambdaQuery(TeamEntity.class)
                            .eq(TeamEntity::getUniqueCode, code)
            );
            if (count == null || count == 0L) {
                return code;
            }
        }
        throw new BusinessException("团队唯一码生成失败，请重试");
    }

    /**
     * 生成随机码。
     *
     * @return 随机码
     */
    private String randomCode() {
        StringBuilder builder = new StringBuilder(TEAM_CODE_RANDOM_LENGTH);
        for (int index = 0; index < TEAM_CODE_RANDOM_LENGTH; index += 1) {
            builder.append(TEAM_CODE_ALPHABET.charAt(RANDOM.nextInt(TEAM_CODE_ALPHABET.length())));
        }
        return builder.toString();
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
     * @param profession 职业身份
     * @return 展示名称
     */
    private String buildDisplayName(String nickname, String profession) {
        if (profession == null || profession.isBlank()) {
            return nickname;
        }
        return nickname + " · " + profession;
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
