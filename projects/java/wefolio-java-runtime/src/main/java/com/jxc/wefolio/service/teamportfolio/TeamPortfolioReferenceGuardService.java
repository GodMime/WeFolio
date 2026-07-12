package com.jxc.wefolio.service.teamportfolio;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 团队作品集引用保护服务。
 *
 * <p>只依赖共享归属实体和引用表，不解析个人作品集配置或团队组件快照。</p>
 */
@Service
@RequiredArgsConstructor
public class TeamPortfolioReferenceGuardService {

    /** 标准个人作品集 Schema 版本 */
    private static final String STANDARD_PERSONAL_SCHEMA_VERSION = "standard-personal-v1";

    /** 未逻辑删除值 */
    private static final long NOT_DELETED = 0L;

    /** 有效引用值 */
    private static final int VALID_REFERENCE = 1;

    /** 个人作品集删除受阻提示 */
    private static final String PERSONAL_PORTFOLIO_REFERENCED_MESSAGE =
            "作品集正在被团队作品集使用，请先移除引用。";

    /** 成员移除受阻提示 */
    private static final String MEMBER_LEAVE_REFERENCED_MESSAGE =
            "无法移除，成员内容仍被团队作品集使用，请先移除引用。";

    /** 成员授权关闭受阻提示 */
    private static final String MEMBER_PERMISSION_REFERENCED_MESSAGE =
            "无法关闭授权，成员内容仍被团队作品集使用，请先移除引用。";

    /** 作品集 Mapper */
    private final PortfolioEntityMapper portfolioEntityMapper;

    /** 作品集引用 Mapper */
    private final PortfolioReferenceEntityMapper portfolioReferenceEntityMapper;

    /** 作品 Mapper */
    private final WorkEntityMapper workEntityMapper;

    /**
     * 校验个人作品集没有被有效团队作品集引用。
     *
     * @param portfolioId 个人作品集 ID
     */
    public void assertPersonalPortfolioNotReferenced(long portfolioId) {
        Set<Long> teamPortfolioIds = loadActiveTeamPortfolioIds(null);
        if (teamPortfolioIds.isEmpty()) {
            return;
        }
        List<PortfolioReferenceEntity> references = safeList(portfolioReferenceEntityMapper.selectList(
                baseActiveReferenceQuery(teamPortfolioIds)
                        .eq(PortfolioReferenceEntity::getReferenceType,
                                ReferenceTypeDict.MEMBER_PORTFOLIO.getCode())
                        .eq(PortfolioReferenceEntity::getReferenceId, portfolioId)
        ));
        if (!references.isEmpty()) {
            throw new BusinessException(PERSONAL_PORTFOLIO_REFERENCED_MESSAGE);
        }
    }

    /**
     * 校验指定成员当前内容没有被指定团队作品集引用。
     *
     * @param teamId 团队 ID
     * @param memberUserId 成员用户 ID
     */
    public void assertMemberCanLeave(long teamId, long memberUserId) {
        assertMemberReferences(teamId, memberUserId, true, true, MEMBER_LEAVE_REFERENCED_MESSAGE);
    }

    /**
     * 校验成员下一授权状态不会留下对应类型的有效团队引用。
     *
     * @param teamId 团队 ID
     * @param memberUserId 成员用户 ID
     * @param nextAllowWorks 下一作品素材授权状态
     * @param nextAllowPortfolio 下一个人作品集授权状态
     */
    public void assertMemberPermissionsCanChange(
            long teamId,
            long memberUserId,
            boolean nextAllowWorks,
            boolean nextAllowPortfolio
    ) {
        boolean checkWorks = !nextAllowWorks;
        boolean checkPortfolio = !nextAllowPortfolio;
        if (!checkWorks && !checkPortfolio) {
            return;
        }
        assertMemberReferences(
                teamId,
                memberUserId,
                checkWorks,
                checkPortfolio,
                MEMBER_PERMISSION_REFERENCED_MESSAGE
        );
    }

    /**
     * 按需要的引用类型批量校验成员内容。
     *
     * @param teamId 团队 ID
     * @param memberUserId 成员用户 ID
     * @param checkWorks 是否检查作品引用
     * @param checkPortfolio 是否检查个人作品集引用
     * @param blockedMessage 阻断提示
     */
    private void assertMemberReferences(
            long teamId,
            long memberUserId,
            boolean checkWorks,
            boolean checkPortfolio,
            String blockedMessage
    ) {
        Set<Long> workIds = checkWorks ? loadActiveWorkIds(memberUserId) : Collections.emptySet();
        Set<Long> memberPortfolioIds = checkPortfolio
                ? loadActivePersonalPortfolioIds(memberUserId)
                : Collections.emptySet();
        if (workIds.isEmpty() && memberPortfolioIds.isEmpty()) {
            return;
        }

        Set<Long> teamPortfolioIds = loadActiveTeamPortfolioIds(teamId);
        if (teamPortfolioIds.isEmpty()) {
            return;
        }

        LambdaQueryWrapper<PortfolioReferenceEntity> query = baseActiveReferenceQuery(teamPortfolioIds);
        appendMemberReferenceConditions(query, workIds, memberPortfolioIds);
        if (!safeList(portfolioReferenceEntityMapper.selectList(query)).isEmpty()) {
            throw new BusinessException(blockedMessage);
        }
    }

    /** 批量读取成员当前生效作品 ID。 */
    private Set<Long> loadActiveWorkIds(long memberUserId) {
        return safeList(workEntityMapper.selectList(
                Wrappers.lambdaQuery(WorkEntity.class)
                        .eq(WorkEntity::getUserId, memberUserId)
                        .eq(WorkEntity::getStatus, WorkStatusDict.ACTIVE.getCode())
                        .eq(WorkEntity::getDeleted, NOT_DELETED)
        )).stream().map(WorkEntity::getId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /** 批量读取成员当前生效标准个人作品集 ID。 */
    private Set<Long> loadActivePersonalPortfolioIds(long memberUserId) {
        return safeList(portfolioEntityMapper.selectList(
                Wrappers.lambdaQuery(PortfolioEntity.class)
                        .eq(PortfolioEntity::getOwnerType, PortfolioOwnerTypeDict.USER.getCode())
                        .eq(PortfolioEntity::getOwnerId, memberUserId)
                        .eq(PortfolioEntity::getTemplateType, PortfolioTemplateTypeDict.STANDARD.getCode())
                        .eq(PortfolioEntity::getStatus, PortfolioStatusDict.ACTIVE.getCode())
                        .eq(PortfolioEntity::getSchemaVersion, STANDARD_PERSONAL_SCHEMA_VERSION)
                        .eq(PortfolioEntity::getDeleted, NOT_DELETED)
        )).stream().map(PortfolioEntity::getId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /** 批量读取生效标准团队父作品集 ID。 */
    private Set<Long> loadActiveTeamPortfolioIds(Long teamId) {
        return safeList(portfolioEntityMapper.selectList(
                Wrappers.lambdaQuery(PortfolioEntity.class)
                        .eq(PortfolioEntity::getOwnerType, PortfolioOwnerTypeDict.TEAM.getCode())
                        .eq(teamId != null, PortfolioEntity::getOwnerId, teamId)
                        .eq(PortfolioEntity::getTemplateType, PortfolioTemplateTypeDict.STANDARD.getCode())
                        .eq(PortfolioEntity::getStatus, PortfolioStatusDict.ACTIVE.getCode())
                        .eq(PortfolioEntity::getSchemaVersion,
                                TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1)
                        .eq(PortfolioEntity::getDeleted, NOT_DELETED)
        )).stream().map(PortfolioEntity::getId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /** 构造有效草稿和正式引用的公共查询条件。 */
    private LambdaQueryWrapper<PortfolioReferenceEntity> baseActiveReferenceQuery(Set<Long> teamPortfolioIds) {
        return Wrappers.lambdaQuery(PortfolioReferenceEntity.class)
                .in(PortfolioReferenceEntity::getPortfolioId, teamPortfolioIds)
                .in(PortfolioReferenceEntity::getConfigScope,
                        PortfolioConfigScopeDict.DRAFT.getCode(),
                        PortfolioConfigScopeDict.PUBLISHED.getCode())
                .eq(PortfolioReferenceEntity::getIsValid, VALID_REFERENCE)
                .eq(PortfolioReferenceEntity::getDeleted, NOT_DELETED);
    }

    /** 为非空内容 ID 集合追加精确引用类型条件。 */
    private void appendMemberReferenceConditions(
            LambdaQueryWrapper<PortfolioReferenceEntity> query,
            Set<Long> workIds,
            Set<Long> memberPortfolioIds
    ) {
        if (!workIds.isEmpty() && !memberPortfolioIds.isEmpty()) {
            query.and(wrapper -> wrapper
                    .eq(PortfolioReferenceEntity::getReferenceType, ReferenceTypeDict.WORK.getCode())
                    .in(PortfolioReferenceEntity::getReferenceId, workIds)
                    .or()
                    .eq(PortfolioReferenceEntity::getReferenceType,
                            ReferenceTypeDict.MEMBER_PORTFOLIO.getCode())
                    .in(PortfolioReferenceEntity::getReferenceId, memberPortfolioIds));
            return;
        }
        if (!workIds.isEmpty()) {
            query.eq(PortfolioReferenceEntity::getReferenceType, ReferenceTypeDict.WORK.getCode())
                    .in(PortfolioReferenceEntity::getReferenceId, workIds);
            return;
        }
        query.eq(PortfolioReferenceEntity::getReferenceType, ReferenceTypeDict.MEMBER_PORTFOLIO.getCode())
                .in(PortfolioReferenceEntity::getReferenceId, memberPortfolioIds);
    }

    /** 将 Mapper 的空返回值归一为空列表。 */
    private <T> List<T> safeList(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }
}
