package com.jxc.wefolio.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户 Mapper 并发锁定合约测试。
 */
class UserEntityMapperTest {

    /** 活动用户锁必须使用主键、状态、逻辑删除条件和行锁。 */
    @Test
    void lockActiveUserUsesForUpdateAndActiveConditions() throws NoSuchMethodException {
        Method method = UserEntityMapper.class.getMethod("lockActiveUserById", Long.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(method.getReturnType()).isEqualTo(Long.class);
        assertThat(sql)
                .contains("SELECT id")
                .contains("FROM wf_user")
                .contains("id = #{userId}")
                .contains("status = 'ACTIVE'")
                .contains("deleted = 0")
                .contains("FOR UPDATE");
    }
}
