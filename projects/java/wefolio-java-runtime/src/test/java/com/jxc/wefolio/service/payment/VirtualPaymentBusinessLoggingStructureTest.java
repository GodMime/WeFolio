package com.jxc.wefolio.service.payment;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 微信虚拟支付业务编排日志结构测试。 */
class VirtualPaymentBusinessLoggingStructureTest {

    /** 关键业务编排器应记录开始、微信结果和本地完成三个阶段。 */
    @Test
    void criticalBusinessFlowsContainStructuredLifecycleLogs() throws IOException {
        assertContains("RechargeService.java",
                "微信虚拟支付业务开始 operation=创建充值订单",
                "微信虚拟支付业务完成 operation=创建充值订单",
                "微信虚拟支付业务开始 operation=同步充值订单",
                "微信虚拟支付业务微信结果 operation=同步充值订单",
                "微信虚拟支付业务完成 operation=同步充值订单");
        assertContains("PointGiftOrderProcessor.java",
                "微信虚拟支付业务开始 operation=处理赠送订单",
                "微信虚拟支付业务微信结果 operation=处理赠送订单",
                "微信虚拟支付业务完成 operation=处理赠送订单");
        assertContains("PointDebitTaskProcessor.java",
                "微信虚拟支付业务开始 operation=处理扣币任务",
                "微信虚拟支付业务微信结果 operation=查询权威余额",
                "微信虚拟支付业务微信结果 operation=处理扣币任务",
                "微信虚拟支付业务完成 operation=处理扣币任务");
        assertContains("WechatAuthoritativeBalanceSyncService.java",
                "微信虚拟支付业务开始 operation=同步权威余额",
                "微信虚拟支付业务微信结果 operation=同步权威余额",
                "微信虚拟支付业务完成 operation=同步权威余额");
        assertContains("WechatVirtualPaymentNotificationService.java",
                "微信虚拟支付业务开始 operation=分发虚拟支付通知",
                "微信虚拟支付业务完成 operation=分发虚拟支付通知");
    }

    /** 业务日志语句不得直接引用密钥、会话或签名变量。 */
    @Test
    void businessLogStatementsDoNotReferenceSecrets() throws IOException {
        for (String fileName : List.of(
                "RechargeService.java",
                "PointGiftOrderProcessor.java",
                "PointDebitTaskProcessor.java",
                "WechatAuthoritativeBalanceSyncService.java",
                "WechatVirtualPaymentNotificationService.java")) {
            String source = source(fileName);
            for (String line : source.lines().filter(value -> value.contains("log.")).toList()) {
                assertThat(line).doesNotContain("sessionKey", "accessToken", "signature", "openId", "openid");
            }
        }
    }

    /** 断言指定业务类包含全部日志片段。 */
    private void assertContains(String fileName, String... fragments) throws IOException {
        assertThat(source(fileName)).contains(fragments);
    }

    /** 读取支付业务类源码。 */
    private String source(String fileName) throws IOException {
        return Files.readString(Path.of("src/main/java/com/jxc/wefolio/service/payment", fileName));
    }
}
