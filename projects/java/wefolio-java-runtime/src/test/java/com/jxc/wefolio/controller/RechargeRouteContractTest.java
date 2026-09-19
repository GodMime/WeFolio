package com.jxc.wefolio.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证普通微信支付入口移除后，虚拟支付和维护者充值路由仍保持原有契约。 */
class RechargeRouteContractTest {

    /** 应用控制器的扫描范围。 */
    private static final String CONTROLLER_PACKAGE = "com.jxc.wefolio.controller";

    /** 已停用的普通微信支付通知地址。 */
    private static final String LEGACY_NOTIFICATION_PATH = "/api/payment/wechat/recharge/notify";

    /** 当前虚拟支付通知地址。 */
    private static final String VIRTUAL_NOTIFICATION_PATH = "/api/payment/wechat/virtual-payment/notify";

    /** 维护者充值页地址。 */
    private static final String RECHARGE_PATH = "/api/mine/recharges";

    /** 维护者充值订单地址。 */
    private static final String ORDERS_PATH = RECHARGE_PATH + "/orders";

    /** 维护者主动查单地址。 */
    private static final String SYNC_PATH = ORDERS_PATH + "/{merchantOrderNo}/sync";

    /** Spring 实际发现并注册的请求映射。 */
    private Set<RequestMappingInfo> mappings;

    /** 扫描真实控制器并交由 Spring MVC 注册映射，无需启动其数据库或远端依赖。 */
    @BeforeEach
    void registerActualControllerMappings() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        try (StaticWebApplicationContext context = new StaticWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            scanner.findCandidateComponents(CONTROLLER_PACKAGE).forEach(definition -> {
                definition.setLazyInit(true);
                context.registerBeanDefinition(definition.getBeanClassName(), definition);
            });
            context.refresh();
            RequestMappingHandlerMapping handlerMapping = new RequestMappingHandlerMapping();
            handlerMapping.setApplicationContext(context);
            handlerMapping.afterPropertiesSet();
            mappings = Set.copyOf(handlerMapping.getHandlerMethods().keySet());
        }
    }

    /** 旧普通微信支付回调不得继续注册为 HTTP 路由。 */
    @Test
    void legacyWechatPayNotificationShouldNotBeRegistered() {
        assertThat(mappings).noneMatch(mapping -> mapping.getPatternValues().contains(LEGACY_NOTIFICATION_PATH));
    }

    /** 虚拟支付的地址校验和通知入口继续使用原来的 GET 与 POST。 */
    @Test
    void virtualPaymentNotificationRoutesShouldRemainRegistered() {
        assertRouteRegistered(RequestMethod.GET, VIRTUAL_NOTIFICATION_PATH);
        assertRouteRegistered(RequestMethod.POST, VIRTUAL_NOTIFICATION_PATH);
    }

    /** 维护者充值页、建单、记录和主动查单入口均保持可用。 */
    @Test
    void maintainerRechargeRoutesShouldRemainRegistered() {
        assertRouteRegistered(RequestMethod.GET, RECHARGE_PATH);
        assertRouteRegistered(RequestMethod.POST, ORDERS_PATH);
        assertRouteRegistered(RequestMethod.GET, ORDERS_PATH);
        assertRouteRegistered(RequestMethod.POST, SYNC_PATH);
    }

    /** 检查 Spring 注册的路径与 HTTP 方法。 */
    private void assertRouteRegistered(RequestMethod method, String path) {
        assertThat(mappings)
                .as("%s %s 应保持注册", method, path)
                .anyMatch(mapping -> mapping.getPatternValues().contains(path)
                        && mapping.getMethodsCondition().getMethods().contains(method));
    }
}
