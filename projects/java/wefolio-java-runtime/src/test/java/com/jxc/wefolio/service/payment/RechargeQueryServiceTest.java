package com.jxc.wefolio.service.payment;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.constant.PointConstants;
import com.jxc.wefolio.dict.RechargeOrderStatusDict;
import com.jxc.wefolio.dict.RechargePackageStatusDict;
import com.jxc.wefolio.dto.RechargeOrdersResponse;
import com.jxc.wefolio.dto.RechargePageResponse;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.RechargeOrderEntity;
import com.jxc.wefolio.entity.RechargePackageEntity;
import com.jxc.wefolio.mapper.RechargeOrderEntityMapper;
import com.jxc.wefolio.mapper.RechargePackageEntityMapper;
import com.jxc.wefolio.message.RechargeMessage;
import com.jxc.wefolio.service.PointService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 充值查询服务回归测试，保护余额、套餐筛选和充值记录的既有响应语义。 */
@ExtendWith(MockitoExtension.class)
class RechargeQueryServiceTest {

    /** 当前维护者用户 ID。 */
    private static final Long USER_ID = 7L;

    /** 测试套餐的稳定编码。 */
    private static final String PACKAGE_CODE = "RECHARGE_TEST";

    /** 创建订单时保留的套餐名称和金额快照。 */
    private static final String PACKAGE_SNAPSHOT =
            "{\"packageName\":\"历史充值套餐\",\"amountFen\":9999,\"totalPoints\":9999}";

    /** 测试商户订单号。 */
    private static final String MERCHANT_ORDER_NO = "RECHARGE-QUERY-001";

    /** 未知历史状态用于验证原有展示兜底。 */
    private static final String UNKNOWN_STATUS = "LEGACY_UNKNOWN";

    /** 查询参数占位符，用于只比较 SQL 业务条件。 */
    private static final String SQL_PARAMETER_PATTERN = "#\\{[^}]+}";

    /** 套餐持久化边界。 */
    @Mock
    private RechargePackageEntityMapper rechargePackageEntityMapper;

    /** 充值订单持久化边界。 */
    @Mock
    private RechargeOrderEntityMapper rechargeOrderEntityMapper;

    /** 查询充值页时负责确保积分账户存在。 */
    @Mock
    private PointService pointService;

    /** 被测充值查询服务。 */
    @InjectMocks
    private RechargeQueryService service;

    /** 注册实体元数据，使真实 Lambda 查询条件能够解析为数据库列名。 */
    @BeforeAll
    static void initializeTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, RechargePackageEntity.class);
        TableInfoHelper.initTableInfo(assistant, RechargeOrderEntity.class);
    }

    /** 空余额按零展示，达到低余额阈值时不再提示低余额。 */
    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "NULL,0,true",
            "0,0,true",
            "49,49,true",
            "50,50,false",
            "51,51,false"
    })
    void getPageShouldNormalizeBalanceAndRespectThreshold(
            Long storedBalance, long expectedBalance, boolean expectedLowBalance) {
        PointAccountEntity account = new PointAccountEntity();
        account.setBalance(storedBalance);
        when(pointService.ensureAccount(USER_ID)).thenReturn(account);
        when(rechargePackageEntityMapper.selectList(any())).thenReturn(List.of());

        RechargePageResponse response = service.getPage(USER_ID);

        assertThat(response.getBalance()).isEqualTo(expectedBalance);
        assertThat(response.isLowBalance()).isEqualTo(expectedLowBalance);
        assertThat(response.getLowBalanceThreshold()).isEqualTo(PointConstants.LOW_BALANCE_THRESHOLD);
        assertThat(response.getPackages()).isEmpty();
        verify(pointService).ensureAccount(USER_ID);
    }

    /** 套餐查询仅纳入已启用且当前有效的档位，按展示顺序及 ID 正序并保留所有展示字段。 */
    @Test
    void getPageShouldQueryActivePackagesAndPreservePackageDetails() {
        when(pointService.ensureAccount(USER_ID)).thenReturn(new PointAccountEntity());
        when(rechargePackageEntityMapper.selectList(any())).thenReturn(List.of(
                rechargePackage(12L, "基础套餐", 1900, 190, 10, 200),
                rechargePackage(3L, "进阶套餐", 4900, 490, 60, 550)));

        LocalDateTime beforeQuery = LocalDateTime.now();
        RechargePageResponse response = service.getPage(USER_ID);
        LocalDateTime afterQuery = LocalDateTime.now();

        ArgumentCaptor<LambdaQueryWrapper<RechargePackageEntity>> queryCaptor = ArgumentCaptor.captor();
        verify(rechargePackageEntityMapper).selectList(queryCaptor.capture());
        LambdaQueryWrapper<RechargePackageEntity> query = queryCaptor.getValue();
        assertThat(query.getSqlSegment().replaceAll(SQL_PARAMETER_PATTERN, "?"))
                .isEqualTo("(status = ? AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?))"
                        + " ORDER BY sort_order ASC,id ASC");
        assertThat(query.getParamNameValuePairs().values())
                .hasSize(3)
                .contains(RechargePackageStatusDict.ACTIVE.getCode());
        List<LocalDateTime> effectiveTimes = query.getParamNameValuePairs().values().stream()
                .filter(LocalDateTime.class::isInstance)
                .map(LocalDateTime.class::cast)
                .toList();
        assertThat(effectiveTimes).hasSize(2)
                .allSatisfy(time -> assertThat(time).isBetween(beforeQuery, afterQuery));
        assertThat(effectiveTimes.getFirst()).isEqualTo(effectiveTimes.getLast());
        assertThat(response.getPackages()).extracting(
                RechargePageResponse.PackageItem::getPackageId,
                RechargePageResponse.PackageItem::getPackageCode,
                RechargePageResponse.PackageItem::getPackageName,
                RechargePageResponse.PackageItem::getAmountFen,
                RechargePageResponse.PackageItem::getBasePoints,
                RechargePageResponse.PackageItem::getBonusPoints,
                RechargePageResponse.PackageItem::getTotalPoints)
                .containsExactly(
                        tuple(12L, PACKAGE_CODE, "基础套餐", 1900, 190, 10, 200),
                        tuple(3L, PACKAGE_CODE, "进阶套餐", 4900, 490, 60, 550));
    }

    /** 非正分页参数使用默认值，每页最多一百条，下一页标记严格按总记录数判断。 */
    @ParameterizedTest
    @CsvSource({
            "0,0,1,20,0,false",
            "-2,-3,1,20,21,true",
            "1,20,1,20,20,false",
            "2,20,2,20,39,false",
            "2,20,2,20,40,false",
            "2,20,2,20,41,true",
            "3,101,3,100,301,true",
            "1,100,1,100,100,false"
    })
    void listOrdersShouldNormalizePaginationAndCalculateHasMore(
            int page, int pageSize, long expectedPage, long expectedPageSize,
            long total, boolean expectedHasMore) {
        when(rechargeOrderEntityMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<RechargeOrderEntity> requested = invocation.getArgument(0);
            requested.setTotal(total);
            requested.setRecords(List.of());
            return requested;
        });

        RechargeOrdersResponse response = service.listOrders(USER_ID, page, pageSize);

        assertThat(response.getPage()).isEqualTo(expectedPage);
        assertThat(response.getPageSize()).isEqualTo(expectedPageSize);
        assertThat(response.getTotal()).isEqualTo(total);
        assertThat(response.isHasMore()).isEqualTo(expectedHasMore);
        assertThat(response.getRecords()).isEmpty();
        ArgumentCaptor<LambdaQueryWrapper<RechargeOrderEntity>> queryCaptor = ArgumentCaptor.captor();
        verify(rechargeOrderEntityMapper).selectPage(any(), queryCaptor.capture());
        LambdaQueryWrapper<RechargeOrderEntity> query = queryCaptor.getValue();
        assertThat(query.getSqlSegment().replaceAll(SQL_PARAMETER_PATTERN, "?"))
                .isEqualTo("(user_id = ?) ORDER BY created_at DESC,id DESC");
        assertThat(query.getParamNameValuePairs().values()).containsExactly(USER_ID);
    }

    /** 记录使用下单快照名称、订单金额积分和既有状态文案，未知及空状态沿用关闭兜底。 */
    @ParameterizedTest
    @MethodSource("orderStatuses")
    void listOrdersShouldPreserveHistoricalDetailsAndStatusText(String status, String expectedText) {
        RechargeOrderEntity order = rechargeOrder(status);
        stubOrderPage(order);

        RechargeOrdersResponse response = service.listOrders(USER_ID, 1, 20);

        assertThat(response.getRecords()).singleElement().satisfies(item -> {
            assertThat(item.getMerchantOrderNo()).isEqualTo(MERCHANT_ORDER_NO);
            assertThat(item.getPackageName()).isEqualTo("历史充值套餐");
            assertThat(item.getAmountFen()).isEqualTo(1900);
            assertThat(item.getBasePoints()).isEqualTo(190);
            assertThat(item.getBonusPoints()).isEqualTo(10);
            assertThat(item.getTotalPoints()).isEqualTo(200);
            assertThat(item.getStatus()).isEqualTo(status);
            assertThat(item.getStatusText()).isEqualTo(expectedText);
        });
    }

    /** 时间展示保留秒精度，创建时间及支付时间为空时各自仍返回空值。 */
    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "2026-09-16T09:08:07.123,2026-09-16T10:11:12.456,2026-09-16 09:08:07,2026-09-16 10:11:12",
            "2026-09-16T09:08:07,NULL,2026-09-16 09:08:07,NULL",
            "NULL,2026-09-16T10:11:12,NULL,2026-09-16 10:11:12",
            "NULL,NULL,NULL,NULL"
    })
    void listOrdersShouldFormatDatesAndPreserveNulls(
            LocalDateTime createdAt, LocalDateTime paidAt, String expectedCreatedAt, String expectedPaidAt) {
        RechargeOrderEntity order = rechargeOrder(RechargeOrderStatusDict.PAID.getCode());
        order.setCreatedAt(createdAt);
        order.setPaidAt(paidAt);
        stubOrderPage(order);

        RechargeOrdersResponse response = service.listOrders(USER_ID, 1, 20);

        assertThat(response.getRecords()).singleElement().satisfies(item -> {
            assertThat(item.getCreatedAt()).isEqualTo(expectedCreatedAt);
            assertThat(item.getPaidAt()).isEqualTo(expectedPaidAt);
        });
    }

    /** 提供所有现有订单状态以及兼容历史异常值的展示预期。 */
    private static Stream<Arguments> orderStatuses() {
        return Stream.of(
                Arguments.of(RechargeOrderStatusDict.PENDING_PAYMENT.getCode(), RechargeMessage.STATUS_PENDING_TEXT),
                Arguments.of(RechargeOrderStatusDict.PAID.getCode(), RechargeMessage.STATUS_PAID_TEXT),
                Arguments.of(RechargeOrderStatusDict.PAYMENT_FAILED.getCode(), RechargeMessage.STATUS_PAYMENT_FAILED_TEXT),
                Arguments.of(RechargeOrderStatusDict.CLOSED.getCode(), RechargeMessage.STATUS_CLOSED_TEXT),
                Arguments.of(RechargeOrderStatusDict.REFUNDED.getCode(), RechargeMessage.STATUS_REFUNDED_TEXT),
                Arguments.of(UNKNOWN_STATUS, RechargeMessage.STATUS_CLOSED_TEXT),
                Arguments.of(null, RechargeMessage.STATUS_CLOSED_TEXT));
    }

    /** 构造字段值彼此可区分的套餐，防止金额与积分在映射时混淆。 */
    private RechargePackageEntity rechargePackage(
            Long id, String name, int amountFen, int basePoints, int bonusPoints, int totalPoints) {
        RechargePackageEntity rechargePackage = new RechargePackageEntity();
        rechargePackage.setId(id);
        rechargePackage.setPackageCode(PACKAGE_CODE);
        rechargePackage.setPackageName(name);
        rechargePackage.setAmountFen(amountFen);
        rechargePackage.setBasePoints(basePoints);
        rechargePackage.setBonusPoints(bonusPoints);
        rechargePackage.setTotalPoints(totalPoints);
        return rechargePackage;
    }

    /** 构造订单值与快照金额不同的历史记录，验证金额积分来源保持为订单字段。 */
    private RechargeOrderEntity rechargeOrder(String status) {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setUserId(USER_ID);
        order.setMerchantOrderNo(MERCHANT_ORDER_NO);
        order.setPackageSnapshot(PACKAGE_SNAPSHOT);
        order.setAmountFen(1900);
        order.setBasePoints(190);
        order.setBonusPoints(10);
        order.setTotalPoints(200);
        order.setStatus(status);
        return order;
    }

    /** 在持久化边界返回单条历史记录，由真实查询服务执行响应映射。 */
    private void stubOrderPage(RechargeOrderEntity order) {
        Page<RechargeOrderEntity> selected = new Page<>(1, 20, 1);
        selected.setRecords(List.of(order));
        when(rechargeOrderEntityMapper.selectPage(any(), any())).thenReturn(selected);
    }
}
