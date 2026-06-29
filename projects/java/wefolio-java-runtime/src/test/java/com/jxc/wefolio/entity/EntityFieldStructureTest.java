package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体字段结构约束测试。
 */
class EntityFieldStructureTest {

    @Test
    void baseEntityDeletedShouldUseIdAsLogicDeleteValue() throws NoSuchFieldException {
        Field deletedField = BaseEntity.class.getDeclaredField("deleted");
        TableLogic tableLogic = deletedField.getAnnotation(TableLogic.class);

        assertThat(deletedField.getType()).isEqualTo(Long.class);
        assertThat(tableLogic).isNotNull();
        assertThat(tableLogic.value()).isEqualTo("0");
        assertThat(tableLogic.delval()).isEqualTo("id");
    }

    @Test
    void portfolioShareRecordShouldUseBaseCreatedAtOnly() {
        boolean declaresCreatedAt = Arrays.stream(PortfolioShareRecordEntity.class.getDeclaredFields())
                .map(Field::getName)
                .anyMatch("createdAt"::equals);

        assertThat(declaresCreatedAt).isFalse();
    }

    @Test
    void systemMessageEqualityShouldIncludeBaseEntityFields() {
        SystemMessageEntity first = new SystemMessageEntity();
        first.setId(1L);
        SystemMessageEntity second = new SystemMessageEntity();
        second.setId(2L);

        assertThat(first).isNotEqualTo(second);
    }
}
