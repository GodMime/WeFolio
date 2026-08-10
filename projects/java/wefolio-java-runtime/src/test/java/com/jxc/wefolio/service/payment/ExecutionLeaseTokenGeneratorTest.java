package com.jxc.wefolio.service.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行租约令牌生成器测试。
 */
class ExecutionLeaseTokenGeneratorTest {

    /** 每次生成的令牌必须唯一且适配数据库 ASCII 定长字段。 */
    @Test
    void generateShouldReturnUniqueUppercaseUuidWithoutHyphens() {
        ExecutionLeaseTokenGenerator generator = new ExecutionLeaseTokenGenerator();

        String first = generator.generate();
        String second = generator.generate();

        assertThat(first).matches("[0-9A-F]{32}");
        assertThat(second).matches("[0-9A-F]{32}").isNotEqualTo(first);
    }
}
