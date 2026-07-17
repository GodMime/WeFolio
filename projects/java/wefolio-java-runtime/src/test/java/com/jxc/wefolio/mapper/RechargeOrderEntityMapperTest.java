package com.jxc.wefolio.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 充值订单 Mapper 契约测试。
 */
class RechargeOrderEntityMapperTest {

    @Test
    void settlementLookupShouldLockOneOrderByMerchantOrderNo() throws NoSuchMethodException {
        Method method = RechargeOrderEntityMapper.class.getMethod(
                "selectForUpdateByMerchantOrderNo", String.class);
        Select select = method.getAnnotation(Select.class);

        assertThat(select).isNotNull();
        assertThat(String.join(" ", select.value()).replaceAll("\\s+", " "))
                .contains("FROM wf_recharge_order")
                .contains("merchant_order_no = #{merchantOrderNo}")
                .contains("deleted = 0")
                .contains("LIMIT 1 FOR UPDATE");
    }
}
