package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dto.MineDashboardResponse;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.PointTransactionEntity;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.mapper.PointAccountEntityMapper;
import com.jxc.wefolio.mapper.PointTransactionEntityMapper;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 我的首页服务 — 负责聚合工作台展示数据
 */
@Service
@RequiredArgsConstructor
public class MineDashboardService {

    /** 低余额提醒阈值 */
    private static final long LOW_BALANCE_THRESHOLD = 50L;

    /** 用户资料 Mapper */
    private final UserEntityMapper userEntityMapper;

    /** 积分账户 Mapper */
    private final PointAccountEntityMapper pointAccountEntityMapper;

    /** 积分流水 Mapper */
    private final PointTransactionEntityMapper pointTransactionEntityMapper;

    /** 作品 Mapper */
    private final WorkEntityMapper workEntityMapper;

    /** 作品集 Mapper */
    private final PortfolioEntityMapper portfolioEntityMapper;

    /** 访问记录 Mapper */
    private final VisitRecordEntityMapper visitRecordEntityMapper;

    /**
     * 获取维护者“我的”首页数据
     *
     * @return 我的首页聚合响应
     */
    public MineDashboardResponse getDashboard() {
        Long userId = AuthContextHolder.requireUserId();
        UserEntity user = userEntityMapper.selectById(userId);
        if (user == null || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
            throw new BusinessException("用户不存在或已停用");
        }

        MineDashboardResponse response = new MineDashboardResponse();
        response.setProfile(buildProfile(user));
        response.setPoint(buildPointSummary(userId));
        response.setMetrics(buildMetrics(userId));
        return response;
    }

    /**
     * 构建身份资料摘要
     *
     * @param user 用户实体
     * @return 身份资料摘要
     */
    private MineDashboardResponse.Profile buildProfile(UserEntity user) {
        MineDashboardResponse.Profile profile = new MineDashboardResponse.Profile();
        profile.setUserId(user.getId());
        profile.setUniqueCode(defaultString(user.getUniqueCode()));
        profile.setNickname(defaultString(user.getNickname()));
        profile.setAvatarUrl(defaultString(user.getAvatarUrl()));
        profile.setProfession(defaultString(user.getProfession()));
        profile.setCity(defaultString(user.getCity()));
        profile.setTags(parseTags(user.getProfileTags()));
        profile.setDisplayName(buildDisplayName(user));
        return profile;
    }

    /**
     * 构建积分摘要
     *
     * @param userId 当前登录用户 ID
     * @return 积分摘要
     */
    private MineDashboardResponse.PointSummary buildPointSummary(Long userId) {
        PointAccountEntity account = pointAccountEntityMapper.selectOne(
                Wrappers.lambdaQuery(PointAccountEntity.class)
                        .eq(PointAccountEntity::getUserId, userId)
                        .last("LIMIT 1")
        );

        long balance = account == null || account.getBalance() == null ? 0L : account.getBalance();
        long totalRecharged = account == null || account.getTotalRecharged() == null ? 0L : account.getTotalRecharged();
        long totalConsumed = account == null || account.getTotalConsumed() == null ? 0L : account.getTotalConsumed();

        MineDashboardResponse.PointSummary point = new MineDashboardResponse.PointSummary();
        point.setBalance(balance);
        point.setTodayConsumed(sumTodayConsumed(userId));
        point.setTotalRecharged(totalRecharged);
        point.setTotalConsumed(totalConsumed);
        point.setLowBalance(balance < LOW_BALANCE_THRESHOLD);
        return point;
    }

    /**
     * 汇总今日消耗积分
     *
     * @param userId 当前登录用户 ID
     * @return 今日消耗积分
     */
    private long sumTodayConsumed(Long userId) {
        LocalDate today = LocalDate.now();
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();

        return pointTransactionEntityMapper.selectList(
                        Wrappers.lambdaQuery(PointTransactionEntity.class)
                                .eq(PointTransactionEntity::getUserId, userId)
                                .lt(PointTransactionEntity::getPointsChange, 0)
                                .ge(PointTransactionEntity::getOccurredAt, start)
                                .lt(PointTransactionEntity::getOccurredAt, end)
                ).stream()
                .map(PointTransactionEntity::getPointsChange)
                .filter(Objects::nonNull)
                .mapToLong(Math::abs)
                .sum();
    }

    /**
     * 构建业务指标
     *
     * @param userId 当前登录用户 ID
     * @return 首页业务指标
     */
    private MineDashboardResponse.Metrics buildMetrics(Long userId) {
        MineDashboardResponse.Metrics metrics = new MineDashboardResponse.Metrics();
        metrics.setWorkCount(workEntityMapper.selectCount(
                Wrappers.lambdaQuery(WorkEntity.class)
                        .eq(WorkEntity::getUserId, userId)
                        .eq(WorkEntity::getStatus, WorkStatusDict.ACTIVE.getCode())
        ));
        metrics.setPublishedPortfolioCount(portfolioEntityMapper.selectCount(
                Wrappers.lambdaQuery(PortfolioEntity.class)
                        .eq(PortfolioEntity::getOwnerType, PortfolioOwnerTypeDict.USER.getCode())
                        .eq(PortfolioEntity::getOwnerId, userId)
                        .eq(PortfolioEntity::getStatus, PortfolioStatusDict.ACTIVE.getCode())
        ));
        metrics.setRecentVisitCount(sumRecentVisitCount(userId));
        return metrics;
    }

    /**
     * 汇总近 7 日访问次数
     *
     * @param userId 当前登录用户 ID
     * @return 近 7 日访问次数
     */
    private long sumRecentVisitCount(Long userId) {
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        List<VisitRecordEntity> records = visitRecordEntityMapper.selectList(
                Wrappers.lambdaQuery(VisitRecordEntity.class)
                        .eq(VisitRecordEntity::getOwnerType, PortfolioOwnerTypeDict.USER.getCode())
                        .eq(VisitRecordEntity::getOwnerId, userId)
                        .ge(VisitRecordEntity::getLastVisitedAt, sevenDaysAgo)
        );
        return records.stream()
                .map(VisitRecordEntity::getVisitCount)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .sum();
    }

    /**
     * 解析资料标签
     *
     * @param profileTags 标签 JSON
     * @return 标签列表
     */
    private List<String> parseTags(String profileTags) {
        if (profileTags == null || profileTags.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return JSON.parseArray(profileTags, String.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 构建展示名称
     *
     * @param user 用户实体
     * @return 展示名称
     */
    private String buildDisplayName(UserEntity user) {
        String nickname = defaultString(user.getNickname());
        String profession = defaultString(user.getProfession());
        if (nickname.isBlank() && profession.isBlank()) {
            return "微信用户";
        }
        if (profession.isBlank()) {
            return nickname;
        }
        if (nickname.isBlank()) {
            return profession;
        }
        return nickname + " · " + profession;
    }

    /**
     * 空值安全字符串
     *
     * @param value 原字符串
     * @return 非空字符串
     */
    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
