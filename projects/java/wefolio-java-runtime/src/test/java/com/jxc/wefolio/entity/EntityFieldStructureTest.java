package com.jxc.wefolio.entity;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体字段结构约束测试。
 */
class EntityFieldStructureTest {

    @Test
    void portfolioShareRecordShouldUseBaseCreatedAtOnly() {
        boolean declaresCreatedAt = Arrays.stream(PortfolioShareRecordEntity.class.getDeclaredFields())
                .map(Field::getName)
                .anyMatch("createdAt"::equals);

        assertThat(declaresCreatedAt).isFalse();
    }
}
