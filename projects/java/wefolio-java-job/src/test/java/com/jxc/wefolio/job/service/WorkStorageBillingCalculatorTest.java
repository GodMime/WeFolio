package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingRule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 作品存储月费计算器测试。
 */
class WorkStorageBillingCalculatorTest {

    private final WorkStorageBillingCalculator calculator = new WorkStorageBillingCalculator();

    @Test
    void calculatorShouldDropRemainderAtTenMbBoundary() {
        BillingRule rule = rule(10, 1);

        assertThat(calculator.calculate(10L * 1024 * 1024 - 1, rule).pointsDue()).isZero();
        assertThat(calculator.calculate(10L * 1024 * 1024, rule).pointsDue()).isEqualTo(1L);
        assertThat(calculator.calculate(29L * 1024 * 1024, rule).pointsDue()).isEqualTo(2L);
        assertThat(calculator.calculate(30L * 1024 * 1024, rule).pointsDue()).isEqualTo(3L);
    }

    @Test
    void calculatorShouldUseRuleValuesAndFormatMbDown() {
        BillingRule rule = rule(5, 3);

        var calculation = calculator.calculate(12_345_678L, rule);

        assertThat(calculation.unitBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(calculation.billedUnits()).isEqualTo(2L);
        assertThat(calculation.pointsDue()).isEqualTo(6L);
        assertThat(calculation.displayMb()).isEqualTo("11.77");
        assertThat(calculator.formatDisplayMb(0)).isEqualTo("0");
        assertThat(calculator.formatDisplayMb(10L * 1024 * 1024)).isEqualTo("10.00");
    }

    @Test
    void invalidRuleAndOverflowShouldBeRejected() {
        assertThatThrownBy(() -> calculator.validateRule(rule(0, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("作品存储积分规则非法");
        assertThatThrownBy(() -> calculator.validateRule(rule(10, -1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("作品存储积分规则非法");
        assertThatThrownBy(() -> calculator.calculate(Long.MAX_VALUE, rule(1, Long.MAX_VALUE)))
                .isInstanceOf(ArithmeticException.class);
    }

    private BillingRule rule(int unitCount, long pointsValue) {
        return new BillingRule(32L, "MONTHLY_WORK_STORAGE", 1, unitCount, pointsValue);
    }
}
