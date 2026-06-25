package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.dict.TeamStatusDict;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 团队注册服务，集中维护团队主表和拥有者成员关系的事务写入。
 *
 * <p>调用方需要先完成团队唯一码生成和 COS 目录初始化，本服务只处理数据库写入，
 * 保持和用户注册流程一致：外层准备存储资源，内层事务落库。</p>
 */
@Service
@RequiredArgsConstructor
public class TeamRegistrationService {

    /** 团队 Mapper */
    private final TeamEntityMapper teamEntityMapper;

    /** 团队成员 Mapper */
    private final TeamMemberEntityMapper teamMemberEntityMapper;

    /**
     * 创建团队，并创建当前用户的拥有者成员关系。
     *
     * @param uniqueCode 已生成且已初始化 COS 目录的团队唯一码
     * @param ownerUserId 拥有者用户 ID
     * @param name 团队名称
     * @param intro 团队简介
     * @param avatarUrl 团队图标地址
     * @return 团队创建结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TeamCreationResult createTeamWithOwner(
            String uniqueCode,
            Long ownerUserId,
            String name,
            String intro,
            String avatarUrl
    ) {
        LocalDateTime now = LocalDateTime.now();
        TeamEntity team = new TeamEntity();
        team.setUniqueCode(uniqueCode);
        team.setName(name);
        team.setIntro(intro);
        team.setAvatarUrl(avatarUrl);
        team.setCity("");
        team.setContactQrUrl("");
        team.setOwnerUserId(ownerUserId);
        team.setStatus(TeamStatusDict.ACTIVE.getCode());
        team.setCreatedAt(now);
        team.setUpdatedAt(now);
        teamEntityMapper.insert(team);
        if (team.getId() == null) {
            throw new BusinessException("团队创建失败，请重试");
        }

        // 创建者默认成为团队拥有者，并拥有资料、作品集等后续维护权限。
        TeamMemberEntity owner = new TeamMemberEntity();
        owner.setTeamId(team.getId());
        owner.setUserId(ownerUserId);
        owner.setRole(TeamRoleDict.OWNER.getCode());
        owner.setProfession("");
        owner.setJoinStatus(JoinStatusDict.JOINED.getCode());
        owner.setAllowPortfolio(1);
        owner.setAllowProfile(1);
        owner.setAllowWorks(1);
        owner.setInvitedBy(ownerUserId);
        owner.setInvitedAt(now);
        owner.setRespondedAt(now);
        owner.setJoinedAt(now);
        teamMemberEntityMapper.insert(owner);

        TeamCreationResult result = new TeamCreationResult();
        result.setTeam(team);
        result.setOwnerMembership(owner);
        return result;
    }

    /**
     * 团队创建结果。
     */
    @Data
    public static class TeamCreationResult {

        /** 已创建的团队实体 */
        private TeamEntity team;

        /** 创建者对应的拥有者成员关系 */
        private TeamMemberEntity ownerMembership;
    }
}
