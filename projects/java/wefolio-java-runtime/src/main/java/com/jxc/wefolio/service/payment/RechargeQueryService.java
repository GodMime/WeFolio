package com.jxc.wefolio.service.payment;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.constant.PointConstants;
import com.jxc.wefolio.dict.RechargePackageStatusDict;
import com.jxc.wefolio.dto.RechargeOrdersResponse;
import com.jxc.wefolio.dto.RechargePageResponse;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.RechargeOrderEntity;
import com.jxc.wefolio.entity.RechargePackageEntity;
import com.jxc.wefolio.mapper.RechargeOrderEntityMapper;
import com.jxc.wefolio.mapper.RechargePackageEntityMapper;
import com.jxc.wefolio.service.PointService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 充值查询服务 — 负责充值页套餐、余额和充值记录展示。
 * 充值页沿用积分账户自动初始化行为，不承接支付状态同步或结算。
 */
@Service
@RequiredArgsConstructor
public class RechargeQueryService {

    /** 默认页码。 */
    private static final int DEFAULT_PAGE = 1;

    /** 默认每页条数。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 最大每页条数。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 时间展示格式。 */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 套餐快照中的历史名称字段。 */
    private static final String PACKAGE_NAME_FIELD = "packageName";

    /** 充值套餐 Mapper。 */
    private final RechargePackageEntityMapper rechargePackageEntityMapper;

    /** 充值订单 Mapper。 */
    private final RechargeOrderEntityMapper rechargeOrderEntityMapper;

    /** 积分服务。 */
    private final PointService pointService;

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
     * 构造充值记录展示项。
     */
    private RechargeOrdersResponse.RecordItem buildOrderItem(RechargeOrderEntity order) {
        JSONObject snapshot = JSONObject.parseObject(order.getPackageSnapshot());
        RechargeOrdersResponse.RecordItem item = new RechargeOrdersResponse.RecordItem();
        item.setMerchantOrderNo(order.getMerchantOrderNo());
        item.setPackageName(snapshot.getString(PACKAGE_NAME_FIELD));
        item.setAmountFen(order.getAmountFen());
        item.setBasePoints(order.getBasePoints());
        item.setBonusPoints(order.getBonusPoints());
        item.setTotalPoints(order.getTotalPoints());
        item.setStatus(order.getStatus());
        item.setStatusText(RechargeOrderStatusText.format(order.getStatus()));
        item.setCreatedAt(formatTime(order.getCreatedAt()));
        item.setPaidAt(formatTime(order.getPaidAt()));
        return item;
    }

    /**
     * 格式化时间。
     */
    private String formatTime(LocalDateTime time) {
        return time == null ? null : TIME_FORMATTER.format(time);
    }
}
