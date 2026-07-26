package com.jxc.wefolio.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dto.MinePointOverviewResponse;
import com.jxc.wefolio.dto.MinePointTransactionsResponse;
import com.jxc.wefolio.dto.PointCalculationRequest;
import com.jxc.wefolio.dto.PointCalculationResponse;
import com.jxc.wefolio.service.PointService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 我的积分控制器测试 — 确认积分查询、流水和计算接口路径与服务委托。
 */
@ExtendWith(MockitoExtension.class)
class MinePointControllerTest {

    /** 积分服务模拟 */
    @Mock
    private PointService pointService;

    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void pointEndpointsUseMaintainerAccessAndDelegateToService() throws NoSuchMethodException {
        MinePointOverviewResponse overviewResponse = new MinePointOverviewResponse();
        MinePointTransactionsResponse transactionsResponse = new MinePointTransactionsResponse();
        PointCalculationRequest calculationRequest = new PointCalculationRequest();
        PointCalculationResponse calculationResponse = new PointCalculationResponse();
        when(pointService.getOverview(7L)).thenReturn(overviewResponse);
        when(pointService.listTransactions(7L, "CONSUMPTION", "CREATE_TEAM", 1, 20))
                .thenReturn(transactionsResponse);
        when(pointService.calculate(7L, calculationRequest)).thenReturn(calculationResponse);

        MinePointController controller = new MinePointController(pointService);
        Response<MinePointOverviewResponse> overview = controller.overview();
        Response<MinePointTransactionsResponse> transactions = controller.transactions(
                "CONSUMPTION", "CREATE_TEAM", 1, 20);
        Response<PointCalculationResponse> calculation = controller.calculate(calculationRequest);

        assertThat(MinePointController.class.isAnnotationPresent(MaintainerAccess.class)).isTrue();
        assertThat(MinePointController.class.getMethod("overview").getAnnotation(GetMapping.class).value())
                .containsExactly("/api/mine/points");
        assertThat(MinePointController.class
                .getMethod("transactions", String.class, String.class, int.class, int.class)
                .getAnnotation(GetMapping.class)
                .value()).containsExactly("/api/mine/points/transactions");
        assertThat(MinePointController.class
                .getMethod("calculate", PointCalculationRequest.class)
                .getAnnotation(PostMapping.class)
                .value()).containsExactly("/api/mine/points/calculate");
        assertThat(overview.getData()).isSameAs(overviewResponse);
        assertThat(transactions.getData()).isSameAs(transactionsResponse);
        assertThat(calculation.getData()).isSameAs(calculationResponse);
        verify(pointService).getOverview(7L);
        verify(pointService).listTransactions(7L, "CONSUMPTION", "CREATE_TEAM", 1, 20);
        verify(pointService).calculate(7L, calculationRequest);
    }

    @Test
    void calculateEndpointDeclaresNonRemovalDeprecation() throws NoSuchMethodException {
        Deprecated deprecated = MinePointController.class
                .getMethod("calculate", PointCalculationRequest.class)
                .getAnnotation(Deprecated.class);

        assertThat(deprecated).isNotNull();
        assertThat(deprecated.since()).isEqualTo("2026-07");
        assertThat(deprecated.forRemoval()).isFalse();
    }

    @Test
    void calculateEndpointWritesDeprecationWarning() {
        Logger logger = (Logger) LoggerFactory.getLogger(MinePointController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new MinePointController(pointService).calculate(new PointCalculationRequest());

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getLevel)
                    .containsExactly(Level.WARN);
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("调用已弃用积分试算接口: userId=7");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
