package com.jxc.wefolio.service.payment;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
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
import java.util.LinkedHashMap;
import java.util.Map;
import com.alibaba.fastjson2.JSON;
import java.time.Instant;

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

    /** 小程序虚拟支付模式。 */
    private static final String VIRTUAL_PAYMENT_MODE = "short_series_coin";

    /** 小程序端签名 URI。 */
    private static final String CLIENT_PAYMENT_URI = "requestVirtualPayment";

    /** 正式环境。 */
    private static final int FORMAL_ENVIRONMENT = 0;

    /** 默认分区。 */
    private static final String DEFAULT_ZONE_ID = "1";

    /** 币种。 */
    private static final String CURRENCY_TYPE = "CNY";

    /** 本地待支付订单有效分钟数。 */
    private static final long ORDER_EXPIRE_MINUTES = 30L;

    /** 充值业务统一使用的上海时区。 */
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    /** 时间展示格式。 */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 微信虚拟支付配置。 */
    private final WechatVirtualPaymentProperties virtualPaymentProperties;

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

    /** 微信虚拟支付客户端。 */
    private final WechatVirtualPaymentClient wechatVirtualPaymentClient;

    /** 微信虚拟支付签名器。 */
    private final WechatVirtualPaymentSigner virtualPaymentSigner;

    /** 维护者微信会话服务。 */
    private final MaintainerWechatSessionService maintainerWechatSessionService;

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
        log.info("微信虚拟支付业务开始 operation=创建充值订单 referenceNo=null userId={} packageId={}",
                userId, request == null ? null : request.getPackageId());
        requirePaymentEnabled();
        if (request == null || request.getPackageId() == null) {
            throw new BusinessException(RechargeMessage.PACKAGE_ID_REQUIRED_MESSAGE);
        }
        requireActiveUser(userId);
        String openId = requireWechatOpenId(userId);
        RechargePackageEntity rechargePackage = requireActivePackage(
                request.getPackageId(), LocalDateTime.now());
        PointAccountEntity account = pointService.ensureAccount(userId);
        MaintainerWechatSession session = requireWechatSession(userId);
        LocalDateTime expireAt = LocalDateTime.now(SHANGHAI_ZONE)
                .plusMinutes(ORDER_EXPIRE_MINUTES);
        RechargeOrderEntity order = transactionService.createPendingOrder(
                userId, account, rechargePackage, expireAt);
        String signData = buildSignData(order);
        CreateRechargeOrderResponse response = buildCreateResponse(order, signData, session.sessionKey());
        log.info("微信虚拟支付业务完成 operation=创建充值订单 referenceNo={} userId={} "
                        + "localStatus={} amountFen={} points={}",
                order.getMerchantOrderNo(), userId, order.getStatus(), order.getAmountFen(),
                order.getTotalPoints());
        return response;
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
        log.info("微信虚拟支付业务开始 operation=同步充值订单 referenceNo={} userId={}",
                merchantOrderNo, userId);
        requirePaymentEnabled();
        if (merchantOrderNo == null || merchantOrderNo.isBlank()) {
            throw new BusinessException(RechargeMessage.MERCHANT_ORDER_NO_REQUIRED_MESSAGE);
        }
        String normalizedOrderNo = merchantOrderNo.strip();
        RechargeOrderEntity order = findUserOrder(userId, normalizedOrderNo);
        if (RechargeOrderStatusDict.PAID.getCode().equals(order.getStatus())) {
            PointAccountEntity account = pointService.ensureAccount(userId);
            return completeSync(order, userId, account.getBalance(), true);
        }
        MaintainerWechatSession session = requireWechatSession(userId);
        String openId = requireWechatOpenId(userId);
        WechatVirtualPaymentResult transaction = wechatVirtualPaymentClient.queryOrder(
                new WechatQueryOrderRequest(
                        userId, order.getMerchantOrderNo(), openId,
                        session.sessionKey(), session.clientIp(),
                        order.getMerchantOrderNo(), Instant.now().getEpochSecond()));
        log.info("微信虚拟支付业务微信结果 operation=同步充值订单 referenceNo={} userId={} "
                        + "errcode={} orderStatus={} payAmount={} remoteOrderId={}",
                order.getMerchantOrderNo(), userId, transaction.errorCode(), transaction.orderStatus(),
                transaction.payAmount(), transaction.remoteOrderId());
        if (!transaction.isSuccessful()) {
            throw new BusinessException(RechargeMessage.ORDER_QUERY_FAILED_MESSAGE);
        }
        validateAuthorityIdentity(order, openId, transaction);
        if ("SUCCESS".equals(transaction.orderStatus()) || "PAID".equals(transaction.orderStatus())) {
            RechargeSettlementResult result = transactionService.settleVirtual(
                    order.getMerchantOrderNo(), transaction);
            return completeSync(result.order(), userId, result.balance(), true);
        }
        if ("CLOSED".equals(transaction.orderStatus())) {
            RechargeOrderEntity closed = transactionService.markTerminalState(
                    userId, normalizedOrderNo, RechargeOrderStatusDict.CLOSED);
            return completeSync(closed, userId, null, true);
        }
        if ("PAYERROR".equals(transaction.orderStatus()) || "REFUND".equals(transaction.orderStatus())) {
            RechargeOrderEntity failed = transactionService.markTerminalState(
                    userId, normalizedOrderNo, RechargeOrderStatusDict.PAYMENT_FAILED);
            return completeSync(failed, userId, null, true);
        }
        return completeSync(order, userId, null, false);
    }

    /** 记录充值同步完成状态并构造响应。 */
    private RechargeOrderSyncResponse completeSync(
            RechargeOrderEntity order,
            Long userId,
            Long balance,
            boolean confirmed
    ) {
        log.info("微信虚拟支付业务完成 operation=同步充值订单 referenceNo={} userId={} "
                        + "localStatus={} balance={} confirmed={}",
                order.getMerchantOrderNo(), userId, order.getStatus(), balance, confirmed);
        return buildSyncResponse(order, balance, confirmed);
    }

    /** 校验权威查单结果属于当前用户、正式环境和本地订单。 */
    private void validateAuthorityIdentity(
            RechargeOrderEntity order,
            String openId,
            WechatVirtualPaymentResult result
    ) {
        if (result.openid() != null && !result.openid().isBlank()
                && !openId.equals(result.openid())) {
            throw new BusinessException(RechargeMessage.WECHAT_IDENTITY_MISSING_MESSAGE);
        }
        if (result.environment() != null && result.environment() != FORMAL_ENVIRONMENT) {
            throw new BusinessException(RechargeMessage.ORDER_QUERY_FAILED_MESSAGE);
        }
        if (result.remoteOrderId() != null && !result.remoteOrderId().isBlank()
                && !order.getMerchantOrderNo().equals(result.remoteOrderId())) {
            throw new BusinessException(RechargeMessage.ORDER_QUERY_FAILED_MESSAGE);
        }
    }

    /**
     * 由已验签的微信通知触发权威查单，不信任通知中的支付结论。
     *
     * @param merchantOrderNo 商户订单号
     * @return 同步结果
     */
    public RechargeOrderSyncResponse syncOrderFromNotification(String merchantOrderNo) {
        RechargeOrderEntity order = rechargeOrderEntityMapper.selectOne(
                Wrappers.lambdaQuery(RechargeOrderEntity.class)
                        .eq(RechargeOrderEntity::getMerchantOrderNo, merchantOrderNo)
                        .last("LIMIT 1")
        );
        if (order == null) {
            throw new BusinessException(RechargeMessage.ORDER_NOT_FOUND_MESSAGE);
        }
        return syncOrder(order.getUserId(), merchantOrderNo);
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
        if (!virtualPaymentProperties.isEnabled()) {
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
            String signData,
            String sessionKey
    ) {
        CreateRechargeOrderResponse response = new CreateRechargeOrderResponse();
        response.setMerchantOrderNo(order.getMerchantOrderNo());
        response.setStatus(order.getStatus());
        response.setMode(VIRTUAL_PAYMENT_MODE);
        response.setSignData(signData);
        response.setPaySig(virtualPaymentSigner.paySignature(
                virtualPaymentProperties.getAppKey(), CLIENT_PAYMENT_URI, signData));
        response.setSignature(virtualPaymentSigner.userSignature(sessionKey, signData));
        return response;
    }

    /** 构造一次且只构造一次的小程序虚拟支付签名正文。 */
    private String buildSignData(RechargeOrderEntity order) {
        Map<String, Object> signData = new LinkedHashMap<>();
        signData.put("offerId", virtualPaymentProperties.getOfferId());
        signData.put("buyQuantity", order.getBuyQuantity());
        signData.put("env", FORMAL_ENVIRONMENT);
        signData.put("currencyType", CURRENCY_TYPE);
        signData.put("outTradeNo", order.getMerchantOrderNo());
        signData.put("zoneId", DEFAULT_ZONE_ID);
        return JSON.toJSONString(signData);
    }

    /** 获取当前有效维护者微信会话。 */
    private MaintainerWechatSession requireWechatSession(Long userId) {
        MaintainerWechatSession session = maintainerWechatSessionService.findAvailableSession(userId);
        if (session == null) {
            throw new BusinessException("微信会话已失效，请刷新后重试");
        }
        return session;
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
