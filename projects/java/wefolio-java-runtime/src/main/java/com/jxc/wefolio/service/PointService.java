package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.constant.PointConstants;
import com.jxc.wefolio.dict.MessageActionTypeDict;
import com.jxc.wefolio.dict.MessageCategoryDict;
import com.jxc.wefolio.dict.MessageReadStatusDict;
import com.jxc.wefolio.dict.MessageTypeDict;
import com.jxc.wefolio.dict.PointCalcModeDict;
import com.jxc.wefolio.dict.PointRuleGroupDict;
import com.jxc.wefolio.dict.PointRuleStatusDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.PointTransactionTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.MinePointOverviewResponse;
import com.jxc.wefolio.dto.MinePointTransactionsResponse;
import com.jxc.wefolio.dto.PointCalculationRequest;
import com.jxc.wefolio.dto.PointCalculationResponse;
import com.jxc.wefolio.dto.PointMutationResponse;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.PointMeterEntity;
import com.jxc.wefolio.entity.PointRuleEntity;
import com.jxc.wefolio.entity.PointTransactionEntity;
import com.jxc.wefolio.entity.SystemMessageEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PointAccountEntityMapper;
import com.jxc.wefolio.mapper.PointMeterEntityMapper;
import com.jxc.wefolio.mapper.PointRuleEntityMapper;
import com.jxc.wefolio.mapper.PointTransactionEntityMapper;
import com.jxc.wefolio.mapper.SystemMessageEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.PointMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 积分服务 — 统一处理积分账户、规则计算、扣减、人工加分和流水查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointService {

    /** 低余额消息幂等键前缀 */
    private static final String LOW_BALANCE_MESSAGE_IDEMPOTENCY_PREFIX = "POINT_LOW_BALANCE:";

    /** 低余额消息标题 */
    private static final String LOW_BALANCE_MESSAGE_TITLE = "积分余额不足";

    /** 低余额消息内容前缀 */
    private static final String LOW_BALANCE_MESSAGE_CONTENT_PREFIX = "当前积分余额已低于 ";

    /** 低余额消息内容后缀 */
    private static final String LOW_BALANCE_MESSAGE_CONTENT_SUFFIX = "，请及时充值，避免影响作品维护和客户访问。";

    /** 低余额消息业务来源类型 */
    private static final String LOW_BALANCE_MESSAGE_BIZ_TYPE = "POINT_TRANSACTION";

    /** 低余额消息跳转地址 */
    private static final String LOW_BALANCE_MESSAGE_ACTION_URL = "/pages/points/points";

    /** 默认页码 */
    private static final int DEFAULT_PAGE = 1;

    /** 默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 最大每页条数 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 后台人工加分业务类型 */
    private static final String BUSINESS_TYPE_ADMIN_GRANT = "ADMIN_GRANT";

    /** 流水时间展示格式 */
    private static final DateTimeFormatter TRANSACTION_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 维护类消耗场景 */
    private static final Set<String> MAINTENANCE_SCENES = Set.of(
            PointSceneCodeDict.UPLOAD_IMAGE.getCode(),
            PointSceneCodeDict.UPLOAD_VIDEO.getCode(),
            PointSceneCodeDict.CREATE_TEAM.getCode(),
            PointSceneCodeDict.MAINTAIN_STANDARD_PORTFOLIO.getCode(),
            PointSceneCodeDict.MAINTAIN_ADVANCED_PORTFOLIO.getCode(),
            PointSceneCodeDict.MONTHLY_WORK_STORAGE.getCode()
    );

    /** 访客类消耗场景 */
    private static final Set<String> VISITOR_SCENES = Set.of(
            PointSceneCodeDict.VISIT_PERSONAL_PORTFOLIO.getCode(),
            PointSceneCodeDict.VIEW_PORTFOLIO_IMAGES.getCode(),
            PointSceneCodeDict.VIEW_PORTFOLIO_VIDEO.getCode()
    );

    /** 用户 Mapper */
    private final UserEntityMapper userEntityMapper;

    /** 积分账户 Mapper */
    private final PointAccountEntityMapper pointAccountEntityMapper;

    /** 积分规则 Mapper */
    private final PointRuleEntityMapper pointRuleEntityMapper;

    /** 积分计量器 Mapper */
    private final PointMeterEntityMapper pointMeterEntityMapper;

    /** 积分流水 Mapper */
    private final PointTransactionEntityMapper pointTransactionEntityMapper;

    /** 系统消息 Mapper */
    private final SystemMessageEntityMapper systemMessageEntityMapper;

    /**
     * 确保用户积分账户存在。
     *
     * @param userId 用户 ID
     * @return 积分账户
     */
    @Transactional(rollbackFor = Exception.class)
    public PointAccountEntity ensureAccount(Long userId) {
        requireActiveUser(userId);
        PointAccountEntity existing = findAccount(userId);
        if (existing != null) {
            return fillAccountDefaults(existing);
        }
        return createZeroAccount(userId);
    }

    /**
     * 获取我的积分概览。
     *
     * @param userId 当前用户 ID
     * @return 积分概览响应
     */
    @Transactional(rollbackFor = Exception.class)
    public MinePointOverviewResponse getOverview(Long userId) {
        PointAccountEntity account = ensureAccount(userId);
        MinePointOverviewResponse response = new MinePointOverviewResponse();
        response.setAccountId(account.getId());
        response.setUserId(userId);
        response.setBalance(safeLong(account.getBalance()));
        response.setTotalRecharged(safeLong(account.getTotalRecharged()));
        response.setTotalGifted(safeLong(account.getTotalGifted()));
        response.setTotalConsumed(safeLong(account.getTotalConsumed()));
        response.setTodayConsumed(sumConsumed(userId, null, LocalDate.now().atStartOfDay(), LocalDate.now().plusDays(1).atStartOfDay()));
        response.setVisitorConsumed(sumConsumed(userId, VISITOR_SCENES, null, null));
        response.setMaintenanceConsumed(sumConsumed(userId, MAINTENANCE_SCENES, null, null));
        response.setLowBalance(safeLong(account.getBalance()) < PointConstants.LOW_BALANCE_THRESHOLD);
        response.setLowBalanceThreshold(PointConstants.LOW_BALANCE_THRESHOLD);
        response.setRules(loadActiveRules(LocalDateTime.now()).stream()
                .map(this::buildRuleItem)
                .toList());
        return response;
    }

    /**
     * 分页查询我的积分流水。
     *
     * @param userId 当前用户 ID
     * @param transactionType 流水类型，可为空
     * @param sceneCode 场景编码，可为空
     * @param page 页码
     * @param pageSize 每页条数
     * @return 积分流水响应
     */
    public MinePointTransactionsResponse listTransactions(
            Long userId,
            String transactionType,
            String sceneCode,
            int page,
            int pageSize
    ) {
        requireActiveUser(userId);
        int normalizedPage = page <= 0 ? DEFAULT_PAGE : page;
        int normalizedPageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        String normalizedTransactionType = hasText(transactionType) ? transactionType.strip() : null;
        String normalizedSceneCode = hasText(sceneCode) ? sceneCode.strip() : null;
        Page<PointTransactionEntity> requestPage = new Page<>(normalizedPage, normalizedPageSize);
        Page<PointTransactionEntity> resultPage = pointTransactionEntityMapper.selectPage(
                requestPage,
                Wrappers.lambdaQuery(PointTransactionEntity.class)
                        .eq(PointTransactionEntity::getUserId, userId)
                        .eq(normalizedTransactionType != null,
                                PointTransactionEntity::getTransactionType, normalizedTransactionType)
                        .eq(normalizedSceneCode != null,
                                PointTransactionEntity::getSceneCode, normalizedSceneCode)
                        .orderByDesc(PointTransactionEntity::getOccurredAt)
                        .orderByDesc(PointTransactionEntity::getId)
        );

        MinePointTransactionsResponse response = new MinePointTransactionsResponse();
        response.setPage(normalizedPage);
        response.setPageSize(normalizedPageSize);
        response.setTotal(resultPage.getTotal());
        response.setHasMore(resultPage.getCurrent() < resultPage.getPages());
        response.setRecords(resultPage.getRecords().stream()
                .map(this::buildTransactionItem)
                .toList());
        return response;
    }

    /**
     * 试算积分变动。
     *
     * @param userId 当前用户 ID
     * @param request 试算请求
     * @return 试算响应
     */
    public PointCalculationResponse calculate(Long userId, PointCalculationRequest request) {
        requireActiveUser(userId);
        if (request == null) {
            throw new BusinessException("积分试算内容不能为空");
        }
        int actionCount = normalizeActionCount(request.getActionCount());
        String sceneCode = normalizeRequiredString(request.getSceneCode(), "积分场景不能为空");
        PointRuleEntity rule = requireActiveRule(sceneCode, LocalDateTime.now());
        PointMeterEntity meter = null;
        PointAccountEntity account = findAccount(userId);
        if (isAccumulated(rule) && account != null
                && hasText(request.getBusinessType()) && hasText(request.getBusinessId())) {
            meter = pointMeterEntityMapper.selectOne(
                    Wrappers.lambdaQuery(PointMeterEntity.class)
                            .eq(PointMeterEntity::getAccountId, account.getId())
                            .eq(PointMeterEntity::getRuleCode, rule.getRuleCode())
                            .eq(PointMeterEntity::getBusinessType, request.getBusinessType().strip())
                            .eq(PointMeterEntity::getBusinessId, request.getBusinessId().strip())
                            .last("LIMIT 1")
            );
        }

        PointCalculation calculation = calculateByRule(rule, actionCount, meter);
        return buildCalculationResponse(rule, calculation, actionCount);
    }

    /**
     * 只读校验指定场景是否有足够积分完成扣除。
     *
     * <p>该方法用于远端副作用前的预检，不创建积分账户、不加行锁、不写流水；
     * 真正扣除仍必须调用 {@link #consume(Long, String, String, String, int, String, String)} 做原子更新兜底。</p>
     *
     * @param userId 当前用户 ID
     * @param sceneCode 场景编码
     * @param actionCount 动作次数
     */
    public void assertCanConsume(Long userId, String sceneCode, int actionCount) {
        requireActiveUser(userId);
        String normalizedSceneCode = normalizeRequiredString(sceneCode, "积分场景不能为空");
        PointRuleEntity rule = requireActiveRule(normalizedSceneCode, LocalDateTime.now());
        PointCalculation calculation = calculateByRule(rule, normalizeActionCount(actionCount), null);
        if (calculation.points() <= 0L) {
            return;
        }
        PointAccountEntity account = findAccount(userId);
        long balanceBefore = account == null ? 0L : safeLong(account.getBalance());
        long signedPoints = signedPoints(rule.getTransactionType(), calculation.points());
        long balanceAfter = calculateBalanceAfter(balanceBefore, signedPoints);
        if (PointTransactionTypeDict.CONSUMPTION.getCode().equals(rule.getTransactionType())
                && balanceAfter < 0L) {
            throw new BusinessException("积分余额不足，请充值后再试");
        }
    }

    /**
     * 只读校验指定积分业务是否可扣除，并兼容已经成功写入的幂等流水。
     *
     * <p>幂等流水命中时必须校验用户、场景、业务类型和业务 ID 全部一致；
     * 身份一致表示该请求已经完成扣费，因此不再检查当前余额。</p>
     *
     * @param userId 当前用户 ID
     * @param sceneCode 场景编码
     * @param businessType 业务类型
     * @param businessId 业务 ID
     * @param actionCount 动作次数
     * @param idempotencyKey 幂等键
     */
    public void assertCanConsume(
            Long userId,
            String sceneCode,
            String businessType,
            String businessId,
            int actionCount,
            String idempotencyKey
    ) {
        requireActiveUser(userId);
        String normalizedSceneCode = normalizeRequiredString(sceneCode, "积分场景不能为空");
        String normalizedBusinessType = normalizeRequiredString(businessType, "业务类型不能为空");
        String normalizedBusinessId = normalizeRequiredString(businessId, "业务 ID 不能为空");
        String normalizedIdempotencyKey = normalizeRequiredString(idempotencyKey, "幂等键不能为空");
        PointTransactionEntity existing = findTransaction(normalizedIdempotencyKey);
        if (existing != null) {
            assertSamePointBusiness(
                    existing,
                    userId,
                    normalizedSceneCode,
                    normalizedBusinessType,
                    normalizedBusinessId
            );
            return;
        }
        assertCanConsume(userId, normalizedSceneCode, actionCount);
    }

    /**
     * 后台人工加分。
     *
     * @param userId 用户 ID
     * @param points 增加积分
     * @param idempotencyKey 幂等键
     * @param remark 备注
     * @return 积分变动响应
     */
    @Transactional(rollbackFor = Exception.class)
    public PointMutationResponse grantPoints(Long userId, Long points, String idempotencyKey, String remark) {
        return grantGift(
                userId,
                points,
                PointSceneCodeDict.MANUAL_ADMIN_GRANT.getCode(),
                BUSINESS_TYPE_ADMIN_GRANT,
                String.valueOf(userId),
                idempotencyKey,
                remark
        );
    }

    /**
     * 赠送积分并写入指定赠送场景流水。
     *
     * @param userId 用户 ID
     * @param points 赠送积分
     * @param sceneCode 赠送场景编码
     * @param businessType 业务类型
     * @param businessId 业务 ID
     * @param idempotencyKey 幂等键
     * @param remark 备注
     * @return 积分变动响应
     */
    @Transactional(rollbackFor = Exception.class)
    public PointMutationResponse grantGift(
            Long userId,
            Long points,
            String sceneCode,
            String businessType,
            String businessId,
            String idempotencyKey,
            String remark
    ) {
        requireActiveUser(userId);
        long normalizedPoints = normalizePositiveLong(points, "增加积分必须大于 0");
        String normalizedSceneCode = normalizeRequiredString(sceneCode, "积分场景不能为空");
        String normalizedBusinessType = normalizeRequiredString(businessType, "业务类型不能为空");
        String normalizedBusinessId = normalizeRequiredString(businessId, "业务 ID 不能为空");
        String normalizedIdempotencyKey = normalizeRequiredString(idempotencyKey, "幂等键不能为空");
        PointTransactionEntity existing = findTransaction(normalizedIdempotencyKey);
        if (existing != null) {
            assertSameUser(existing, userId);
            return buildMutationFromTransaction(existing, true, true);
        }

        PointAccountEntity account = requireAccount(userId);
        long balanceBefore = safeLong(account.getBalance());
        long balanceAfter = Math.addExact(balanceBefore, normalizedPoints);
        account.setBalance(balanceAfter);
        account.setTotalGifted(Math.addExact(safeLong(account.getTotalGifted()), normalizedPoints));
        updateAccount(account);

        PointTransactionEntity transaction = new PointTransactionEntity();
        transaction.setAccountId(account.getId());
        transaction.setUserId(userId);
        transaction.setTransactionType(PointTransactionTypeDict.GIFT.getCode());
        transaction.setSceneCode(normalizedSceneCode);
        transaction.setPointsChange(normalizedPoints);
        transaction.setBalanceBefore(balanceBefore);
        transaction.setBalanceAfter(balanceAfter);
        transaction.setBusinessType(normalizedBusinessType);
        transaction.setBusinessId(normalizedBusinessId);
        transaction.setCalculationSnapshot(toJson(snapshot(
                PointCalcModeDict.MANUAL_ADJUSTMENT.getCode(), normalizedPoints, 1L, null)));
        transaction.setIdempotencyKey(normalizedIdempotencyKey);
        transaction.setRemark(normalizeOptionalString(remark));
        transaction.setOccurredAt(LocalDateTime.now());
        pointTransactionEntityMapper.insert(transaction);
        createLowBalanceMessageIfNeeded(transaction);
        log.info("赠送积分成功: userId={}, sceneCode={}, points={}, balanceAfter={}, idempotencyKey={}",
                userId, normalizedSceneCode, normalizedPoints, balanceAfter, normalizedIdempotencyKey);
        return buildMutationFromTransaction(transaction, false, true);
    }

    /**
     * 按积分规则消费或变动积分。
     *
     * @param userId 用户 ID
     * @param sceneCode 场景编码
     * @param businessType 业务类型
     * @param businessId 业务 ID
     * @param actionCount 动作次数
     * @param idempotencyKey 幂等键
     * @param remark 备注
     * @return 积分变动响应
     */
    @Transactional(rollbackFor = Exception.class)
    public PointMutationResponse consume(
            Long userId,
            String sceneCode,
            String businessType,
            String businessId,
            int actionCount,
            String idempotencyKey,
            String remark
    ) {
        return consumeInternal(userId, sceneCode, businessType, businessId, businessId, actionCount, idempotencyKey, remark);
    }

    /**
     * 按指定计量业务 ID 消费积分。
     *
     * <p>累计阈值场景会使用计量业务 ID 读取和更新计量器，积分流水仍写入业务 ID，
     * 便于把扣费流水反查到更细的业务对象。</p>
     *
     * @param userId 用户 ID
     * @param sceneCode 场景编码
     * @param businessType 业务类型
     * @param businessId 流水业务 ID
     * @param meterBusinessId 计量业务 ID
     * @param actionCount 动作次数
     * @param idempotencyKey 幂等键
     * @param remark 备注
     * @return 积分变动响应
     */
    @Transactional(rollbackFor = Exception.class)
    public PointMutationResponse consumeWithMeterBusinessId(
            Long userId,
            String sceneCode,
            String businessType,
            String businessId,
            String meterBusinessId,
            int actionCount,
            String idempotencyKey,
            String remark
    ) {
        return consumeInternal(userId, sceneCode, businessType, businessId, meterBusinessId, actionCount, idempotencyKey, remark);
    }

    /**
     * 执行积分消费。
     *
     * @param userId 用户 ID
     * @param sceneCode 场景编码
     * @param businessType 业务类型
     * @param businessId 流水业务 ID
     * @param meterBusinessId 计量业务 ID
     * @param actionCount 动作次数
     * @param idempotencyKey 幂等键
     * @param remark 备注
     * @return 积分变动响应
     */
    private PointMutationResponse consumeInternal(
            Long userId,
            String sceneCode,
            String businessType,
            String businessId,
            String meterBusinessId,
            int actionCount,
            String idempotencyKey,
            String remark
    ) {
        requireActiveUser(userId);
        String normalizedSceneCode = normalizeRequiredString(sceneCode, "积分场景不能为空");
        String normalizedBusinessType = normalizeRequiredString(businessType, "业务类型不能为空");
        String normalizedBusinessId = normalizeRequiredString(businessId, "业务 ID 不能为空");
        String normalizedMeterBusinessId = normalizeRequiredString(meterBusinessId, "计量业务 ID 不能为空");
        String normalizedIdempotencyKey = normalizeRequiredString(idempotencyKey, "幂等键不能为空");
        PointTransactionEntity existing = findTransaction(normalizedIdempotencyKey);
        if (existing != null) {
            assertSamePointBusiness(
                    existing,
                    userId,
                    normalizedSceneCode,
                    normalizedBusinessType,
                    normalizedBusinessId
            );
            return buildMutationFromTransaction(existing, true, true);
        }

        PointRuleEntity rule = requireActiveRule(normalizedSceneCode, LocalDateTime.now());
        PointAccountEntity account = requireAccount(userId);
        PointMeterEntity meter = isAccumulated(rule)
                ? requireMeter(account, rule, normalizedBusinessType, normalizedMeterBusinessId)
                : null;
        PointCalculation calculation = calculateByRule(rule, normalizeActionCount(actionCount), meter);
        applyMeter(calculation, meter, account, rule, normalizedBusinessType, normalizedMeterBusinessId);
        if (calculation.points() <= 0L) {
            PointMutationResponse response = new PointMutationResponse();
            response.setAccountId(account.getId());
            response.setUserId(userId);
            response.setSceneCode(rule.getSceneCode());
            response.setSceneText(sceneText(rule.getSceneCode()));
            response.setPointsChange(0L);
            response.setBalanceBefore(safeLong(account.getBalance()));
            response.setBalanceAfter(safeLong(account.getBalance()));
            response.setBilledUnits(0L);
            response.setCharged(false);
            response.setMessage("累计次数未达到扣费阈值");
            return response;
        }

        long signedPoints = signedPoints(rule.getTransactionType(), calculation.points());
        long balanceBefore;
        long balanceAfter;
        if (PointTransactionTypeDict.CONSUMPTION.getCode().equals(rule.getTransactionType())) {
            account = deductConsumedPoints(account, userId, calculation.points());
            balanceAfter = safeLong(account.getBalance());
            balanceBefore = Math.addExact(balanceAfter, calculation.points());
        } else {
            balanceBefore = safeLong(account.getBalance());
            balanceAfter = calculateBalanceAfter(balanceBefore, signedPoints);
            applyAccountChange(account, rule.getTransactionType(), calculation.points(), balanceAfter);
        }

        PointTransactionEntity transaction = new PointTransactionEntity();
        transaction.setAccountId(account.getId());
        transaction.setUserId(userId);
        transaction.setRuleId(rule.getId());
        transaction.setTransactionType(rule.getTransactionType());
        transaction.setSceneCode(rule.getSceneCode());
        transaction.setPointsChange(signedPoints);
        transaction.setBalanceBefore(balanceBefore);
        transaction.setBalanceAfter(balanceAfter);
        transaction.setBusinessType(normalizedBusinessType);
        transaction.setBusinessId(normalizedBusinessId);
        transaction.setCalculationSnapshot(toJson(snapshot(rule.getCalcMode(), calculation.points(), calculation.billedUnits(), rule)));
        transaction.setIdempotencyKey(normalizedIdempotencyKey);
        transaction.setRemark(normalizeOptionalString(remark));
        transaction.setOccurredAt(LocalDateTime.now());
        pointTransactionEntityMapper.insert(transaction);
        createLowBalanceMessageIfNeeded(transaction);
        return buildMutationFromTransaction(transaction, false, true);
    }

    /**
     * 校验启用用户。
     *
     * @param userId 用户 ID
     */
    private void requireActiveUser(Long userId) {
        if (userId == null) {
            throw new BusinessException("用户不能为空");
        }
        UserEntity user = userEntityMapper.selectById(userId);
        if (user == null || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
            throw new BusinessException("用户不存在或已停用");
        }
    }

    /**
     * 创建零余额积分账户。
     *
     * @param userId 用户 ID
     * @return 积分账户
     */
    private PointAccountEntity createZeroAccount(Long userId) {
        PointAccountEntity account = new PointAccountEntity();
        account.setUserId(userId);
        account.setBalance(0L);
        account.setTotalRecharged(0L);
        account.setTotalGifted(0L);
        account.setTotalConsumed(0L);
        try {
            pointAccountEntityMapper.insert(account);
        } catch (DuplicateKeyException e) {
            PointAccountEntity existing = findAccount(userId);
            if (existing != null) {
                return fillAccountDefaults(existing);
            }
            throw e;
        }
        return account;
    }

    /**
     * 查询积分账户。
     *
     * @param userId 用户 ID
     * @return 积分账户
     */
    private PointAccountEntity findAccount(Long userId) {
        PointAccountEntity account = pointAccountEntityMapper.selectOne(
                Wrappers.lambdaQuery(PointAccountEntity.class)
                        .eq(PointAccountEntity::getUserId, userId)
                        .last("LIMIT 1")
        );
        return account == null ? null : fillAccountDefaults(account);
    }

    /**
     * 查询或创建积分账户。
     *
     * @param userId 用户 ID
     * @return 积分账户
     */
    private PointAccountEntity requireAccount(Long userId) {
        PointAccountEntity account = findAccount(userId);
        if (account != null) {
            return fillAccountDefaults(account);
        }
        createZeroAccount(userId);
        account = findAccount(userId);
        if (account == null) {
            throw new BusinessException("积分账户创建失败，请重试");
        }
        return fillAccountDefaults(account);
    }

    /**
     * 原子扣减消费积分。
     *
     * @param account 积分账户
     * @param userId 用户 ID
     * @param points 扣减积分
     * @return 扣减后的积分账户
     */
    private PointAccountEntity deductConsumedPoints(PointAccountEntity account, Long userId, long points) {
        int updated = pointAccountEntityMapper.deductConsumedPoints(account.getId(), userId, points);
        if (updated <= 0) {
            throw new BusinessException("积分余额不足，请充值后再试");
        }
        PointAccountEntity updatedAccount = findAccount(userId);
        if (updatedAccount == null) {
            throw new BusinessException("积分账户更新失败，请重试");
        }
        return fillAccountDefaults(updatedAccount);
    }

    /**
     * 更新积分账户。
     *
     * @param account 积分账户
     */
    private void updateAccount(PointAccountEntity account) {
        int updated = pointAccountEntityMapper.updateById(account);
        if (updated <= 0) {
            throw new BusinessException("积分账户更新失败，请重试");
        }
    }

    /**
     * 账户空值兜底。
     *
     * @param account 积分账户
     * @return 积分账户
     */
    private PointAccountEntity fillAccountDefaults(PointAccountEntity account) {
        account.setBalance(safeLong(account.getBalance()));
        account.setTotalRecharged(safeLong(account.getTotalRecharged()));
        account.setTotalGifted(safeLong(account.getTotalGifted()));
        account.setTotalConsumed(safeLong(account.getTotalConsumed()));
        return account;
    }

    /**
     * 查询幂等流水。
     *
     * @param idempotencyKey 幂等键
     * @return 积分流水
     */
    private PointTransactionEntity findTransaction(String idempotencyKey) {
        return pointTransactionEntityMapper.selectOne(
                Wrappers.lambdaQuery(PointTransactionEntity.class)
                        .eq(PointTransactionEntity::getIdempotencyKey, idempotencyKey)
                        .last("LIMIT 1")
        );
    }

    /**
     * 校验幂等流水归属。
     *
     * @param transaction 已有流水
     * @param userId 当前用户 ID
     */
    private void assertSameUser(PointTransactionEntity transaction, Long userId) {
        if (!Objects.equals(transaction.getUserId(), userId)) {
            throw new BusinessException(PointMessage.IDEMPOTENCY_USER_CONFLICT_MESSAGE);
        }
    }

    /**
     * 校验幂等流水是否属于同一积分业务。
     *
     * @param transaction 已有流水
     * @param userId 当前用户 ID
     * @param sceneCode 场景编码
     * @param businessType 业务类型
     * @param businessId 业务 ID
     */
    private void assertSamePointBusiness(
            PointTransactionEntity transaction,
            Long userId,
            String sceneCode,
            String businessType,
            String businessId
    ) {
        assertSameUser(transaction, userId);
        if (!Objects.equals(transaction.getSceneCode(), sceneCode)
                || !Objects.equals(transaction.getBusinessType(), businessType)
                || !Objects.equals(transaction.getBusinessId(), businessId)) {
            throw new BusinessException(PointMessage.IDEMPOTENCY_BUSINESS_CONFLICT_MESSAGE);
        }
    }

    /**
     * 查询当前生效规则。
     *
     * @param sceneCode 场景编码
     * @param now 当前时间
     * @return 积分规则
     */
    private PointRuleEntity requireActiveRule(String sceneCode, LocalDateTime now) {
        List<PointRuleEntity> rules = pointRuleEntityMapper.selectList(
                Wrappers.lambdaQuery(PointRuleEntity.class)
                        .eq(PointRuleEntity::getSceneCode, sceneCode)
                        .eq(PointRuleEntity::getStatus, PointRuleStatusDict.ACTIVE.getCode())
                        .le(PointRuleEntity::getEffectiveFrom, now)
                        .and(wrapper -> wrapper
                                .isNull(PointRuleEntity::getEffectiveTo)
                                .or()
                                .gt(PointRuleEntity::getEffectiveTo, now))
                        .orderByDesc(PointRuleEntity::getRuleVersion)
                        .last("LIMIT 1")
        );
        if (rules == null || rules.isEmpty()) {
            throw new BusinessException("积分规则不存在或未启用");
        }
        return rules.get(0);
    }

    /**
     * 查询全部生效规则。
     *
     * @param now 当前时间
     * @return 生效规则列表
     */
    private List<PointRuleEntity> loadActiveRules(LocalDateTime now) {
        List<PointRuleEntity> rules = pointRuleEntityMapper.selectList(
                Wrappers.lambdaQuery(PointRuleEntity.class)
                        .eq(PointRuleEntity::getStatus, PointRuleStatusDict.ACTIVE.getCode())
                        .le(PointRuleEntity::getEffectiveFrom, now)
                        .and(wrapper -> wrapper
                                .isNull(PointRuleEntity::getEffectiveTo)
                                .or()
                                .gt(PointRuleEntity::getEffectiveTo, now))
                        .orderByAsc(PointRuleEntity::getSceneCode)
                        .orderByDesc(PointRuleEntity::getRuleVersion)
        );
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        Map<String, PointRuleEntity> latestRules = new LinkedHashMap<>();
        for (PointRuleEntity rule : rules) {
            String sceneCode = defaultString(rule.getSceneCode());
            PointRuleEntity existing = latestRules.get(sceneCode);
            if (existing == null || ruleVersion(rule) > ruleVersion(existing)) {
                latestRules.put(sceneCode, rule);
            }
        }
        return List.copyOf(latestRules.values());
    }

    /**
     * 查询或创建累计计量器。
     *
     * @param account 积分账户
     * @param rule 积分规则
     * @param businessType 业务类型
     * @param businessId 业务 ID
     * @return 积分计量器
     */
    private PointMeterEntity requireMeter(
            PointAccountEntity account,
            PointRuleEntity rule,
            String businessType,
            String businessId
    ) {
        PointMeterEntity meter = pointMeterEntityMapper.selectMeter(
                account.getId(), rule.getRuleCode(), businessType, businessId);
        if (meter != null) {
            return fillMeterDefaults(meter);
        }
        PointMeterEntity created = new PointMeterEntity();
        created.setAccountId(account.getId());
        created.setUserId(account.getUserId());
        created.setRuleCode(rule.getRuleCode());
        created.setAppliedRuleId(rule.getId());
        created.setBusinessType(businessType);
        created.setBusinessId(businessId);
        created.setPendingCount(0);
        created.setTotalCount(0L);
        created.setTotalBilledUnits(0L);
        try {
            pointMeterEntityMapper.insert(created);
        } catch (DuplicateKeyException e) {
            PointMeterEntity existing = pointMeterEntityMapper.selectMeter(
                    account.getId(), rule.getRuleCode(), businessType, businessId);
            if (existing != null) {
                return fillMeterDefaults(existing);
            }
            throw e;
        }
        return created;
    }

    /**
     * 保存计量器变更。
     *
     * @param calculation 计算结果
     * @param meter 计量器
     * @param account 积分账户
     * @param rule 积分规则
     * @param businessType 业务类型
     * @param businessId 业务 ID
     */
    private void applyMeter(
            PointCalculation calculation,
            PointMeterEntity meter,
            PointAccountEntity account,
            PointRuleEntity rule,
            String businessType,
            String businessId
    ) {
        if (!isAccumulated(rule)) {
            return;
        }
        PointMeterEntity target = meter == null ? new PointMeterEntity() : meter;
        target.setAccountId(account.getId());
        target.setUserId(account.getUserId());
        target.setRuleCode(rule.getRuleCode());
        target.setAppliedRuleId(rule.getId());
        target.setBusinessType(businessType);
        target.setBusinessId(businessId);
        target.setPendingCount(calculation.pendingAfter());
        target.setTotalCount(Math.addExact(safeLong(target.getTotalCount()), (long) calculation.actionCount()));
        target.setTotalBilledUnits(Math.addExact(safeLong(target.getTotalBilledUnits()), calculation.billedUnits()));
        if (target.getId() == null) {
            pointMeterEntityMapper.insert(target);
        } else {
            int updated = pointMeterEntityMapper.updateById(target);
            if (updated <= 0) {
                throw new BusinessException("积分计量器更新失败，请重试");
            }
        }
    }

    /**
     * 积分规则计算。
     *
     * @param rule 积分规则
     * @param actionCount 动作次数
     * @param meter 计量器
     * @return 计算结果
     */
    private PointCalculation calculateByRule(PointRuleEntity rule, int actionCount, PointMeterEntity meter) {
        long pointsValue = safeLong(rule.getPointsValue());
        int unitCount = rule.getUnitCount() == null || rule.getUnitCount() <= 0 ? 1 : rule.getUnitCount();
        if (PointCalcModeDict.FIXED_PER_ACTION.getCode().equals(rule.getCalcMode())
                || PointCalcModeDict.MANUAL_ADJUSTMENT.getCode().equals(rule.getCalcMode())) {
            long billedUnits = actionCount;
            return new PointCalculation(
                    actionCount,
                    0,
                    0,
                    billedUnits,
                    Math.multiplyExact(pointsValue, billedUnits)
            );
        }
        if (PointCalcModeDict.ACCUMULATED_THRESHOLD.getCode().equals(rule.getCalcMode())) {
            int pendingBefore = meter == null || meter.getPendingCount() == null ? 0 : meter.getPendingCount();
            int totalPending = Math.addExact(pendingBefore, actionCount);
            long billedUnits = totalPending / unitCount;
            int pendingAfter = totalPending % unitCount;
            return new PointCalculation(
                    actionCount,
                    pendingBefore,
                    pendingAfter,
                    billedUnits,
                    Math.multiplyExact(pointsValue, billedUnits)
            );
        }
        throw new BusinessException("积分计算模式暂不支持");
    }

    /**
     * 应用账户变动。
     *
     * @param account 积分账户
     * @param transactionType 流水类型
     * @param absolutePoints 变动积分绝对值
     * @param balanceAfter 变动后余额
     */
    private void applyAccountChange(
            PointAccountEntity account,
            String transactionType,
            long absolutePoints,
            long balanceAfter
    ) {
        account.setBalance(balanceAfter);
        if (PointTransactionTypeDict.CONSUMPTION.getCode().equals(transactionType)) {
            account.setTotalConsumed(Math.addExact(safeLong(account.getTotalConsumed()), absolutePoints));
        } else if (PointTransactionTypeDict.RECHARGE.getCode().equals(transactionType)) {
            account.setTotalRecharged(Math.addExact(safeLong(account.getTotalRecharged()), absolutePoints));
        } else if (PointTransactionTypeDict.GIFT.getCode().equals(transactionType)) {
            account.setTotalGifted(Math.addExact(safeLong(account.getTotalGifted()), absolutePoints));
        }
        updateAccount(account);
    }

    /**
     * 余额低于阈值时写入站内消息。
     *
     * @param transaction 积分流水
     */
    private void createLowBalanceMessageIfNeeded(PointTransactionEntity transaction) {
        if (transaction == null || safeLong(transaction.getBalanceAfter()) >= PointConstants.LOW_BALANCE_THRESHOLD) {
            return;
        }
        SystemMessageEntity message = new SystemMessageEntity();
        message.setUserId(transaction.getUserId());
        message.setMessageType(MessageTypeDict.POINT_LOW_BALANCE.getCode());
        message.setCategory(MessageCategoryDict.POINT.getCode());
        message.setReadStatus(MessageReadStatusDict.UNREAD.getCode());
        message.setTitle(LOW_BALANCE_MESSAGE_TITLE);
        message.setContent(LOW_BALANCE_MESSAGE_CONTENT_PREFIX
                + PointConstants.LOW_BALANCE_THRESHOLD
                + LOW_BALANCE_MESSAGE_CONTENT_SUFFIX);
        message.setActionType(MessageActionTypeDict.POINT_RECHARGE.getCode());
        message.setActionUrl(LOW_BALANCE_MESSAGE_ACTION_URL);
        message.setBizType(LOW_BALANCE_MESSAGE_BIZ_TYPE);
        message.setBizId(transaction.getId());
        message.setIdempotencyKey(buildLowBalanceMessageIdempotencyKey(transaction));
        try {
            systemMessageEntityMapper.insert(message);
        } catch (DuplicateKeyException e) {
            log.info("低余额站内消息已存在: userId={}, transactionId={}, idempotencyKey={}",
                    transaction.getUserId(), transaction.getId(), message.getIdempotencyKey());
        }
    }

    /**
     * 构建低余额消息幂等键。
     *
     * @param transaction 积分流水
     * @return 消息幂等键
     */
    private String buildLowBalanceMessageIdempotencyKey(PointTransactionEntity transaction) {
        if (transaction.getId() != null) {
            return LOW_BALANCE_MESSAGE_IDEMPOTENCY_PREFIX + transaction.getId();
        }
        return LOW_BALANCE_MESSAGE_IDEMPOTENCY_PREFIX + normalizeOptionalString(transaction.getIdempotencyKey());
    }

    /**
     * 计算变动后余额。
     *
     * @param balanceBefore 变动前余额
     * @param signedPoints 带符号积分
     * @return 变动后余额
     */
    private long calculateBalanceAfter(long balanceBefore, long signedPoints) {
        try {
            return Math.addExact(balanceBefore, signedPoints);
        } catch (ArithmeticException e) {
            throw new BusinessException("积分计算结果超出范围", e);
        }
    }

    /**
     * 按流水类型生成带符号积分。
     *
     * @param transactionType 流水类型
     * @param points 积分绝对值
     * @return 带符号积分
     */
    private long signedPoints(String transactionType, long points) {
        if (PointTransactionTypeDict.CONSUMPTION.getCode().equals(transactionType)) {
            return -points;
        }
        return points;
    }

    /**
     * 构建积分变动响应。
     *
     * @param transaction 积分流水
     * @param idempotent 是否幂等命中
     * @param charged 是否实际变动
     * @return 积分变动响应
     */
    private PointMutationResponse buildMutationFromTransaction(
            PointTransactionEntity transaction,
            boolean idempotent,
            boolean charged
    ) {
        PointMutationResponse response = new PointMutationResponse();
        response.setTransactionId(transaction.getId());
        response.setAccountId(transaction.getAccountId());
        response.setUserId(transaction.getUserId());
        response.setSceneCode(defaultString(transaction.getSceneCode()));
        response.setSceneText(sceneText(transaction.getSceneCode()));
        response.setPointsChange(safeLong(transaction.getPointsChange()));
        response.setBalanceBefore(safeLong(transaction.getBalanceBefore()));
        response.setBalanceAfter(safeLong(transaction.getBalanceAfter()));
        response.setIdempotent(idempotent);
        response.setCharged(charged);
        response.setBilledUnits(billedUnitsFromSnapshot(transaction));
        response.setMessage(idempotent ? "已处理过相同积分请求" : "积分变动成功");
        return response;
    }

    /**
     * 从计算快照读取计费单位数。
     *
     * @param transaction 积分流水
     * @return 计费单位数
     */
    private long billedUnitsFromSnapshot(PointTransactionEntity transaction) {
        String calculationSnapshot = transaction.getCalculationSnapshot();
        if (!hasText(calculationSnapshot)) {
            return defaultBilledUnits(transaction);
        }
        try {
            Object billedUnits = JSON.parseObject(calculationSnapshot).get("billedUnits");
            if (billedUnits instanceof Number number && number.longValue() >= 0L) {
                return number.longValue();
            }
            if (billedUnits instanceof String text && hasText(text)) {
                long parsedValue = Long.parseLong(text.strip());
                if (parsedValue >= 0L) {
                    return parsedValue;
                }
            }
        } catch (RuntimeException e) {
            log.warn("积分快照解析失败: transactionId={}, snapshotLength={}",
                    transaction.getId(), calculationSnapshot.length(), e);
        }
        return defaultBilledUnits(transaction);
    }

    /**
     * 计费单位数兜底值。
     *
     * @param transaction 积分流水
     * @return 计费单位数
     */
    private long defaultBilledUnits(PointTransactionEntity transaction) {
        return safeLong(transaction.getPointsChange()) == 0L ? 0L : 1L;
    }

    /**
     * 构建试算响应。
     *
     * @param rule 积分规则
     * @param calculation 计算结果
     * @param actionCount 动作次数
     * @return 试算响应
     */
    private PointCalculationResponse buildCalculationResponse(
            PointRuleEntity rule,
            PointCalculation calculation,
            int actionCount
    ) {
        PointCalculationResponse response = new PointCalculationResponse();
        response.setRuleId(rule.getId());
        response.setRuleCode(defaultString(rule.getRuleCode()));
        response.setRuleName(defaultString(rule.getRuleName()));
        response.setSceneCode(defaultString(rule.getSceneCode()));
        response.setSceneText(sceneText(rule.getSceneCode()));
        response.setCalcMode(defaultString(rule.getCalcMode()));
        response.setTransactionType(defaultString(rule.getTransactionType()));
        response.setActionCount(actionCount);
        response.setUnitCount(rule.getUnitCount());
        response.setPointsValue(safeLong(rule.getPointsValue()));
        response.setBilledUnits(calculation.billedUnits());
        response.setPointsChange(signedPoints(rule.getTransactionType(), calculation.points()));
        response.setPendingCountBefore(calculation.pendingBefore());
        response.setPendingCountAfter(calculation.pendingAfter());
        return response;
    }

    /**
     * 构建规则展示项。
     *
     * @param rule 积分规则
     * @return 规则展示项
     */
    private MinePointOverviewResponse.RuleItem buildRuleItem(PointRuleEntity rule) {
        MinePointOverviewResponse.RuleItem item = new MinePointOverviewResponse.RuleItem();
        item.setRuleId(rule.getId());
        item.setRuleCode(defaultString(rule.getRuleCode()));
        item.setRuleName(defaultString(rule.getRuleName()));
        item.setSceneCode(defaultString(rule.getSceneCode()));
        item.setSceneText(sceneText(rule.getSceneCode()));
        item.setGroupCode(ruleGroupCode(rule.getGroupCode()));
        item.setGroupText(ruleGroupText(rule.getGroupCode()));
        item.setCalcMode(defaultString(rule.getCalcMode()));
        item.setTransactionType(defaultString(rule.getTransactionType()));
        item.setUnitCount(rule.getUnitCount());
        item.setPointsValue(safeLong(rule.getPointsValue()));
        return item;
    }

    /**
     * 构建流水展示项。
     *
     * @param transaction 积分流水
     * @return 流水展示项
     */
    private MinePointTransactionsResponse.TransactionItem buildTransactionItem(PointTransactionEntity transaction) {
        MinePointTransactionsResponse.TransactionItem item = new MinePointTransactionsResponse.TransactionItem();
        item.setTransactionId(transaction.getId());
        item.setTransactionType(defaultString(transaction.getTransactionType()));
        item.setTransactionTypeText(transactionTypeText(transaction.getTransactionType()));
        item.setSceneCode(defaultString(transaction.getSceneCode()));
        item.setSceneText(sceneText(transaction.getSceneCode()));
        item.setPointsChange(safeLong(transaction.getPointsChange()));
        item.setPointsText(formatPoints(transaction.getPointsChange()));
        item.setBalanceBefore(safeLong(transaction.getBalanceBefore()));
        item.setBalanceAfter(safeLong(transaction.getBalanceAfter()));
        item.setBusinessType(defaultString(transaction.getBusinessType()));
        item.setBusinessId(defaultString(transaction.getBusinessId()));
        item.setRemark(defaultString(transaction.getRemark()));
        item.setOccurredAt(transaction.getOccurredAt() == null
                ? ""
                : TRANSACTION_TIME_FORMATTER.format(transaction.getOccurredAt()));
        return item;
    }

    /**
     * 汇总消耗积分。
     *
     * @param userId 用户 ID
     * @param scenes 场景集合，可为空
     * @param start 开始时间，可为空
     * @param end 结束时间，可为空
     * @return 消耗积分正数值
     */
    private long sumConsumed(Long userId, Collection<String> scenes, LocalDateTime start, LocalDateTime end) {
        List<PointTransactionEntity> transactions = pointTransactionEntityMapper.selectList(
                Wrappers.lambdaQuery(PointTransactionEntity.class)
                        .eq(PointTransactionEntity::getUserId, userId)
                        .lt(PointTransactionEntity::getPointsChange, 0)
                        .in(scenes != null && !scenes.isEmpty(), PointTransactionEntity::getSceneCode, scenes)
                        .ge(start != null, PointTransactionEntity::getOccurredAt, start)
                        .lt(end != null, PointTransactionEntity::getOccurredAt, end)
        );
        return transactions == null
                ? 0L
                : transactions.stream()
                .map(PointTransactionEntity::getPointsChange)
                .filter(Objects::nonNull)
                .mapToLong(Math::abs)
                .sum();
    }

    /**
     * 生成计算快照。
     *
     * @param calcMode 计算模式
     * @param points 积分绝对值
     * @param billedUnits 计费单位数
     * @param rule 积分规则
     * @return 快照映射
     */
    private Map<String, Object> snapshot(String calcMode, long points, long billedUnits, PointRuleEntity rule) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("calcMode", calcMode);
        snapshot.put("points", points);
        snapshot.put("billedUnits", billedUnits);
        if (rule != null) {
            snapshot.put("ruleId", rule.getId());
            snapshot.put("ruleCode", rule.getRuleCode());
            snapshot.put("ruleVersion", rule.getRuleVersion());
            snapshot.put("unitCount", rule.getUnitCount());
            snapshot.put("pointsValue", rule.getPointsValue());
        }
        return snapshot;
    }

    /**
     * JSON 序列化。
     *
     * @param value 原始对象
     * @return JSON 字符串
     */
    private String toJson(Object value) {
        return JSON.toJSONString(value);
    }

    /**
     * 判断是否累计阈值规则。
     *
     * @param rule 积分规则
     * @return 是否累计阈值
     */
    private boolean isAccumulated(PointRuleEntity rule) {
        return PointCalcModeDict.ACCUMULATED_THRESHOLD.getCode().equals(rule.getCalcMode());
    }

    /**
     * 计量器空值兜底。
     *
     * @param meter 计量器
     * @return 计量器
     */
    private PointMeterEntity fillMeterDefaults(PointMeterEntity meter) {
        meter.setPendingCount(meter.getPendingCount() == null ? 0 : meter.getPendingCount());
        meter.setTotalCount(safeLong(meter.getTotalCount()));
        meter.setTotalBilledUnits(safeLong(meter.getTotalBilledUnits()));
        return meter;
    }

    /**
     * 标准化动作次数。
     *
     * @param actionCount 原始动作次数
     * @return 动作次数
     */
    private int normalizeActionCount(Integer actionCount) {
        int normalized = actionCount == null ? 1 : actionCount;
        if (normalized <= 0) {
            throw new BusinessException("动作次数必须大于 0");
        }
        return normalized;
    }

    /**
     * 标准化动作次数。
     *
     * @param actionCount 原始动作次数
     * @return 动作次数
     */
    private int normalizeActionCount(int actionCount) {
        return normalizeActionCount(Integer.valueOf(actionCount));
    }

    /**
     * 标准化正整数。
     *
     * @param value 原始值
     * @param message 异常文案
     * @return 正整数
     */
    private long normalizePositiveLong(Long value, String message) {
        if (value == null || value <= 0L) {
            throw new BusinessException(message);
        }
        return value;
    }

    /**
     * 标准化必填字符串。
     *
     * @param value 原始值
     * @param message 异常文案
     * @return 字符串
     */
    private String normalizeRequiredString(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(message);
        }
        return value.strip();
    }

    /**
     * 标准化可选字符串。
     *
     * @param value 原始值
     * @return 字符串
     */
    private String normalizeOptionalString(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * 是否存在非空字符串。
     *
     * @param value 原始值
     * @return 是否非空
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 安全 long 值。
     *
     * @param value 原始值
     * @return 非空 long
     */
    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 安全规则版本。
     *
     * @param rule 积分规则
     * @return 规则版本
     */
    private int ruleVersion(PointRuleEntity rule) {
        return rule.getRuleVersion() == null ? 0 : rule.getRuleVersion();
    }

    /**
     * 默认字符串。
     *
     * @param value 原始字符串
     * @return 非空字符串
     */
    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    /**
     * 场景文案。
     *
     * @param sceneCode 场景编码
     * @return 场景文案
     */
    private String sceneText(String sceneCode) {
        return PointSceneCodeDict.fromCode(sceneCode)
                .map(PointSceneCodeDict.DictValue::getDisplayName)
                .orElse(defaultString(sceneCode));
    }

    /**
     * 规则分组编码。
     *
     * @param groupCode 分组编码
     * @return 规则分组编码
     */
    private String ruleGroupCode(String groupCode) {
        PointRuleGroupDict dict = PointRuleGroupDict.fromCode(groupCode);
        return dict == null ? PointRuleGroupDict.OTHER.getCode() : dict.getCode();
    }

    /**
     * 规则分组文案。
     *
     * @param groupCode 分组编码
     * @return 规则分组文案
     */
    private String ruleGroupText(String groupCode) {
        PointRuleGroupDict dict = PointRuleGroupDict.fromCode(groupCode);
        return dict == null ? PointRuleGroupDict.OTHER.getDisplayName() : dict.getDisplayName();
    }

    /**
     * 流水类型文案。
     *
     * @param transactionType 流水类型
     * @return 类型文案
     */
    private String transactionTypeText(String transactionType) {
        PointTransactionTypeDict dict = PointTransactionTypeDict.fromCode(transactionType);
        return dict == null ? defaultString(transactionType) : dict.getDisplayName();
    }

    /**
     * 格式化积分变动。
     *
     * @param pointsChange 积分变动
     * @return 积分文案
     */
    private String formatPoints(Long pointsChange) {
        long value = safeLong(pointsChange);
        return value > 0L ? "+" + value : String.valueOf(value);
    }

    /**
     * 积分计算结果。
     *
     * @param actionCount 动作次数
     * @param pendingBefore 计算前累计余数
     * @param pendingAfter 计算后累计余数
     * @param billedUnits 计费单位数
     * @param points 积分绝对值
     */
    private record PointCalculation(
            int actionCount,
            int pendingBefore,
            int pendingAfter,
            long billedUnits,
            long points
    ) {
    }
}
