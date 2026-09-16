package com.jxc.wefolio.mapper;

import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Update;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 积分账户充值原子更新契约测试。
 */
class PointAccountRechargeMapperTest {

    @Test
    void applyRechargeWechatBalanceShouldUseSingleAtomicSqlUpdate() throws NoSuchMethodException {
        Method method = PointAccountEntityMapper.class.getMethod(
                "applyRechargeWechatBalance", Long.class, Long.class, Long.class, Long.class, Long.class);
        Update update = method.getAnnotation(Update.class);

        assertThat(update).isNotNull();
        String sql = String.join(" ", update.value()).replaceAll("\\s+", " ").toLowerCase();
        assertThat(sql)
                .contains("wechat_balance = #{wechatbalance}")
                .contains("wechat_present_balance = #{wechatpresentbalance}")
                .contains("balance = #{wechatbalance} - pending_debit")
                .contains("total_recharged = total_recharged + #{points}")
                .contains("version = version + 1")
                .contains("where id = #{accountid}")
                .contains("and user_id = #{userid}")
                .contains("and deleted = 0")
                .doesNotContain("balance = balance + #{points}")
                .doesNotContain("for update");
    }
}
