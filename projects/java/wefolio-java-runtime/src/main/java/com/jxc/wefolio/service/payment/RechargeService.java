package com.jxc.wefolio.service.payment;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.config.WechatPayProperties;
import com.jxc.wefolio.constant.PointConstants;
import com.jxc.wefolio.dict.AuthTypeDict;
import com.jxc.wefolio.dict.RechargeOrderStatusDict;
import com.jxc.wefolio.dict.RechargePackageStatusDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.CreateRechargeOrderRequest;
import com.jxc.wefolio.dto.CreateRechargeOrderResponse;
import com.jxc.wefolio.dto.RechargeOrderSyncResponse;
import com.jxc.wefolio.dto.RechargeOrdersResponse;
import com.jxc.wefolio.dto.RechargePageResponse;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.RechargeOrderEntity;
import com.jxc.wefolio.entity.RechargePackageEntity;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.RechargeOrderEntityMapper;
import com.jxc.wefolio.mapper.RechargePackageEntityMapper;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.RechargeMessage;
import com.jxc.wefolio.service.PointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 充值应用服务 — 编排套餐查询、本地建单、微信预下单、记录与主动查单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RechargeService {

    /** 默认页码。 */
    private static final int DEFAULT_PAGE = 1;

    /** 默认每页条数。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 最大每页条数。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 微信支付商品描述前缀。 */
    private static final String PAYMENT_DESCRIPTION_PREFIX = "映期Folio-";

    /** 充值业务统一使用的上海时区。 */
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    /** 时间展示格式。 */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 微信支付配置。 */
    private final WechatPayProperties payProperties;

    /** 微信小程序配置。 */
    private final WechatMiniappProperties miniappProperties;

    /** 用户 Mapper。 */
    private final UserEntityMapper userEntityMapper;

    /** 用户认证 Mapper。 */
    private final UserAuthEntityMapper userAuthEntityMapper;

    /** 充值套餐 Mapper。 */
    private final RechargePackageEntityMapper rechargePackageEntityMapper;

    /** 充值订单 Mapper。 */
    private final RechargeOrderEntityMapper rechargeOrderEntityMapper;

    /** 积分服务。 */
    private final PointService pointService;

    /** 充值订单事务服务。 */
    private final RechargeOrderTransactionService transactionService;

    /** 微信支付客户端。 */
    private final WechatPayClient wechatPayClient;

    /**
     * 获取充值页数据。
     *
     * @param userId 当前用户 ID
     * @return 充值页数据
     */
    public RechargePageResponse getPage(Long userId) {
        PointAccountEntity account = pointService.ensureAccount(userId);
        List<RechargePackageEntity> packages = loadActivePackages(LocalDateTime.now());
        RechargePageResponse response = new RechargePageResponse();
        long balance = account.getBalance() == null ? 0L : account.getBalance();
        response.setBalance(balance);
        response.setLowBalance(balance < PointConstants.LOW_BALANCE_THRESHOLD);
        response.setLowBalanceThreshold(PointConstants.LOW_BALANCE_THRESHOLD);
        response.setPackages(packages.stream().map(this::buildPackageItem).toList());
        return response;
    }

    /**
     * 创建充值订单并取得小程序调起支付参数。
     *
     * @param userId 当前用户 ID
     * @param request 创建订单请求
     * @return 支付参数
     */
    public CreateRechargeOrderResponse createOrder(Long userId, CreateRechargeOrderRequest request) {
        requirePaymentEnabled();
        if (request == null || request.getPackageId() == null) {
            throw new BusinessException(RechargeMessage.PACKAGE_ID_REQUIRED_MESSAGE);
        }
        requireActiveUser(userId);
        String openId = requireWechatOpenId(userId);
        RechargePackageEntity rechargePackage = requireActivePackage(
                request.getPackageId(), LocalDateTime.now());
        PointAccountEntity account = pointService.ensureAccount(userId);
        LocalDateTime expireAt = LocalDateTime.now(SHANGHAI_ZONE)
                .plusMinutes(payProperties.getOrderExpireMinutes());
        RechargeOrderEntity order = transactionService.createPendingOrder(
                userId, account, rechargePackage, expireAt);
        try {
            WechatPayClient.PrepayResult prepay = wechatPayClient.prepay(new WechatPayClient.PrepayCommand(
                    miniappProperties.getAppId(),
                    payProperties.getMerchantId(),
                    PAYMENT_DESCRIPTION_PREFIX + rechargePackage.getPackageName(),
                    order.getMerchantOrderNo(),
                    order.getAmountFen(),
                    openId,
                    expireAt,
                    payProperties.getNotifyUrl()
            ));
            transactionService.markPrepayReady(order.getMerchantOrderNo(), prepay.prepayId());
            return buildCreateResponse(order, prepay);
        } catch (RuntimeException exception) {
            try {
                transactionService.markPrepayFailed(order.getMerchantOrderNo());
            } catch (RuntimeException markException) {
                log.error("微信支付预下单失败且本地订单状态回写失败: merchantOrderNo={}, exceptionType={}",
                        order.getMerchantOrderNo(), markException.getClass().getSimpleName());
            }
            log.warn("微信支付预下单失败: merchantOrderNo={}, exceptionType={}",
                    order.getMerchantOrderNo(), exception.getClass().getSimpleName());
            throw new BusinessException(RechargeMessage.PREPAY_FAILED_MESSAGE, exception);
        }
    }

    /**
     * 分页查询当前用户充值记录。
     */
    public RechargeOrdersResponse listOrders(Long userId, int page, int pageSize) {
        int normalizedPage = page <= 0 ? DEFAULT_PAGE : page;
        int normalizedPageSize = pageSize <= 0
                ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);
        Page<RechargeOrderEntity> result = rechargeOrderEntityMapper.selectPage(
                new Page<>(normalizedPage, normalizedPageSize),
                Wrappers.lambdaQuery(RechargeOrderEntity.class)
                        .eq(RechargeOrderEntity::getUserId, userId)
                        .orderByDesc(RechargeOrderEntity::getCreatedAt)
                        .orderByDesc(RechargeOrderEntity::getId)
        );
        RechargeOrdersResponse response = new RechargeOrdersResponse();
        response.setPage(result.getCurrent());
        response.setPageSize(result.getSize());
        response.setTotal(result.getTotal());
        response.setHasMore(result.getCurrent() * result.getSize() < result.getTotal());
        response.setRecords(result.getRecords().stream().map(this::buildOrderItem).toList());
        return response;
    }

    /**
     * 主动查询并同步当前用户的一笔充值订单。
     */
    public RechargeOrderSyncResponse syncOrder(Long userId, String merchantOrderNo) {
        requirePaymentEnabled();
        if (merchantOrderNo == null || merchantOrderNo.isBlank()) {
            throw new BusinessException(RechargeMessage.MERCHANT_ORDER_NO_REQUIRED_MESSAGE);
        }
        String normalizedOrderNo = merchantOrderNo.strip();
        RechargeOrderEntity order = findUserOrder(userId, normalizedOrderNo);
        if (RechargeOrderStatusDict.PAID.getCode().equals(order.getStatus())) {
            PointAccountEntity account = pointService.ensureAccount(userId);
            return buildSyncResponse(order, account.getBalance(), true);
        }
        WechatPayClient.Transaction transaction;
        try {
            transaction = wechatPayClient.queryByMerchantOrderNo(order.getMerchantOrderNo());
        } catch (RuntimeException exception) {
            log.warn("微信支付主动查单失败: merchantOrderNo={}, exceptionType={}",
                    order.getMerchantOrderNo(), exception.getClass().getSimpleName());
            throw new BusinessException(RechargeMessage.ORDER_QUERY_FAILED_MESSAGE, exception);
        }
        if (transaction == null || transaction.tradeState() == null) {
            throw new BusinessException(RechargeMessage.ORDER_QUERY_FAILED_MESSAGE);
        }
        if (!order.getMerchantOrderNo().equals(transaction.merchantOrderNo())) {
            throw new BusinessException(RechargeMessage.MERCHANT_ORDER_NO_MISMATCH_MESSAGE);
        }
        if (transaction.tradeState() == WechatPayClient.TradeState.SUCCESS) {
            RechargeSettlementResult result = transactionService.settle(transaction);
            return buildSyncResponse(result.order(), result.balance(), true);
        }
        if (transaction.tradeState() == WechatPayClient.TradeState.CLOSED
                || transaction.tradeState() == WechatPayClient.TradeState.REVOKED) {
            RechargeOrderEntity closed = transactionService.markTerminalState(
                    userId, normalizedOrderNo, RechargeOrderStatusDict.CLOSED);
            return buildSyncResponse(closed, null, true);
        }
        if (transaction.tradeState() == WechatPayClient.TradeState.PAYERROR
                || transaction.tradeState() == WechatPayClient.TradeState.REFUND) {
            RechargeOrderEntity failed = transactionService.markTerminalState(
                    userId, normalizedOrderNo, RechargeOrderStatusDict.PAYMENT_FAILED);
            return buildSyncResponse(failed, null, true);
        }
        return buildSyncResponse(order, null, false);
    }

    /**
     * 加载当前有效套餐。
     */
    private List<RechargePackageEntity> loadActivePackages(LocalDateTime now) {
        return rechargePackageEntityMapper.selectList(
                Wrappers.lambdaQuery(RechargePackageEntity.class)
                        .eq(RechargePackageEntity::getStatus, RechargePackageStatusDict.ACTIVE.getCode())
                        .le(RechargePackageEntity::getEffectiveFrom, now)
                        .and(wrapper -> wrapper.isNull(RechargePackageEntity::getEffectiveTo)
                                .or()
                                .gt(RechargePackageEntity::getEffectiveTo, now))
                        .orderByAsc(RechargePackageEntity::getSortOrder)
                        .orderByAsc(RechargePackageEntity::getId)
        );
    }

    /**
     * 查询指定有效套餐。
     */
    private RechargePackageEntity requireActivePackage(Long packageId, LocalDateTime now) {
        RechargePackageEntity rechargePackage = rechargePackageEntityMapper.selectOne(
                Wrappers.lambdaQuery(RechargePackageEntity.class)
                        .eq(RechargePackageEntity::getId, packageId)
                        .eq(RechargePackageEntity::getStatus, RechargePackageStatusDict.ACTIVE.getCode())
                        .le(RechargePackageEntity::getEffectiveFrom, now)
                        .and(wrapper -> wrapper.isNull(RechargePackageEntity::getEffectiveTo)
                                .or()
                                .gt(RechargePackageEntity::getEffectiveTo, now))
                        .last("LIMIT 1")
        );
        if (rechargePackage == null) {
            throw new BusinessException(RechargeMessage.PACKAGE_UNAVAILABLE_MESSAGE);
        }
        return rechargePackage;
    }

    /**
     * 校验启用用户。
     */
    private void requireActiveUser(Long userId) {
        UserEntity user = userEntityMapper.selectById(userId);
        if (user == null || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
            throw new BusinessException(RechargeMessage.USER_UNAVAILABLE_MESSAGE);
        }
    }

    /**
     * 查询维护者服务端保存的微信 openid。
     */
    private String requireWechatOpenId(Long userId) {
        UserAuthEntity auth = userAuthEntityMapper.selectOne(
                Wrappers.lambdaQuery(UserAuthEntity.class)
                        .eq(UserAuthEntity::getUserId, userId)
                        .eq(UserAuthEntity::getAuthType, AuthTypeDict.WECHAT_MINI_APP.getCode())
                        .eq(UserAuthEntity::getStatus, UserStatusDict.ACTIVE.getCode())
                        .isNotNull(UserAuthEntity::getOpenId)
                        .last("LIMIT 1")
        );
        if (auth == null || auth.getOpenId() == null || auth.getOpenId().isBlank()) {
            throw new BusinessException(RechargeMessage.WECHAT_IDENTITY_MISSING_MESSAGE);
        }
        return auth.getOpenId().strip();
    }

    /**
     * 查询当前用户订单。
     */
    private RechargeOrderEntity findUserOrder(Long userId, String merchantOrderNo) {
        RechargeOrderEntity order = rechargeOrderEntityMapper.selectOne(
                Wrappers.lambdaQuery(RechargeOrderEntity.class)
                        .eq(RechargeOrderEntity::getUserId, userId)
                        .eq(RechargeOrderEntity::getMerchantOrderNo, merchantOrderNo)
                        .last("LIMIT 1")
        );
        if (order == null) {
            throw new BusinessException(RechargeMessage.ORDER_NOT_FOUND_MESSAGE);
        }
        return order;
    }

    /**
     * 校验微信支付已启用。
     */
    private void requirePaymentEnabled() {
        if (!payProperties.isEnabled()) {
            throw new BusinessException(RechargeMessage.PAYMENT_NOT_CONFIGURED_MESSAGE);
        }
    }

    /**
     * 构造套餐展示项。
     */
    private RechargePageResponse.PackageItem buildPackageItem(RechargePackageEntity rechargePackage) {
        RechargePageResponse.PackageItem item = new RechargePageResponse.PackageItem();
        item.setPackageId(rechargePackage.getId());
        item.setPackageCode(rechargePackage.getPackageCode());
        item.setPackageName(rechargePackage.getPackageName());
        item.setAmountFen(rechargePackage.getAmountFen());
        item.setBasePoints(rechargePackage.getBasePoints());
        item.setBonusPoints(rechargePackage.getBonusPoints());
        item.setTotalPoints(rechargePackage.getTotalPoints());
        return item;
    }

    /**
     * 构造创建订单响应。
     */
    private CreateRechargeOrderResponse buildCreateResponse(
            RechargeOrderEntity order,
            WechatPayClient.PrepayResult prepay
    ) {
        CreateRechargeOrderResponse response = new CreateRechargeOrderResponse();
        response.setMerchantOrderNo(order.getMerchantOrderNo());
        response.setStatus(order.getStatus());
        response.setTimeStamp(prepay.timeStamp());
        response.setNonceStr(prepay.nonceStr());
        response.setPackageValue(prepay.packageValue());
        response.setSignType(prepay.signType());
        response.setPaySign(prepay.paySign());
        return response;
    }

    /**
     * 构造充值记录展示项。
     */
    private RechargeOrdersResponse.RecordItem buildOrderItem(RechargeOrderEntity order) {
        JSONObject snapshot = JSONObject.parseObject(order.getPackageSnapshot());
        RechargeOrdersResponse.RecordItem item = new RechargeOrdersResponse.RecordItem();
        item.setMerchantOrderNo(order.getMerchantOrderNo());
        item.setPackageName(snapshot.getString("packageName"));
        item.setAmountFen(order.getAmountFen());
        item.setBasePoints(order.getBasePoints());
        item.setBonusPoints(order.getBonusPoints());
        item.setTotalPoints(order.getTotalPoints());
        item.setStatus(order.getStatus());
        item.setStatusText(statusText(order.getStatus()));
        item.setCreatedAt(formatTime(order.getCreatedAt()));
        item.setPaidAt(formatTime(order.getPaidAt()));
        return item;
    }

    /**
     * 构造同步响应。
     */
    private RechargeOrderSyncResponse buildSyncResponse(
            RechargeOrderEntity order,
            Long balance,
            boolean confirmed
    ) {
        RechargeOrderSyncResponse response = new RechargeOrderSyncResponse();
        response.setMerchantOrderNo(order.getMerchantOrderNo());
        response.setStatus(order.getStatus());
        response.setStatusText(statusText(order.getStatus()));
        response.setBalance(balance);
        response.setConfirmed(confirmed);
        return response;
    }

    /**
     * 获取充值状态展示文案。
     */
    private String statusText(String status) {
        if (RechargeOrderStatusDict.PAID.getCode().equals(status)) {
            return RechargeMessage.STATUS_PAID_TEXT;
        }
        if (RechargeOrderStatusDict.PENDING_PAYMENT.getCode().equals(status)) {
            return RechargeMessage.STATUS_PENDING_TEXT;
        }
        if (RechargeOrderStatusDict.PAYMENT_FAILED.getCode().equals(status)) {
            return RechargeMessage.STATUS_PAYMENT_FAILED_TEXT;
        }
        return RechargeMessage.STATUS_CLOSED_TEXT;
    }

    /**
     * 格式化时间。
     */
    private String formatTime(LocalDateTime time) {
        return time == null ? null : TIME_FORMATTER.format(time);
    }
}
