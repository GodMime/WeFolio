package com.jxc.wefolio.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 积分赠送入口结构测试。
 */
class PointServiceVirtualGiftStructureTest {

    /** 批量赠送必须走统一命令服务，旧测试构造器必须明确标记待移除。 */
    @Test
    void batchGiftShouldNotFallbackToLocalBalanceMutation() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/service/PointService.java"));

        assertThat(source)
                .contains("@Deprecated(forRemoval = true)")
                .contains("统一积分命令服务未注入，禁止绕过微信赠送订单")
                .doesNotContain("for (GiftCommand command : commands)");
    }
}
