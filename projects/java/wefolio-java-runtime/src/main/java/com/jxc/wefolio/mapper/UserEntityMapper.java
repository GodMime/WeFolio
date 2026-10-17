package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;


/**
 * 用户表 Mapper
 */
@Mapper
public interface UserEntityMapper extends BaseMapper<UserEntity> {

    /**
     * 锁定正常且未删除的用户行，串行化作品容量检查，并保护团队转让期间的账号状态。
     *
     * @param userId 用户 ID
     * @return 已锁定的用户 ID；账号停用、已删除或不存在时返回空
     */
    @Select("""
            SELECT id
              FROM wf_user
             WHERE id = #{userId}
               AND status = 'ACTIVE'
               AND deleted = 0
             FOR UPDATE
            """)
    Long lockActiveUserById(@Param("userId") Long userId);

    /**
     * 锁定最小物理用户行，串行化跨请求人的 AI 队列容量计数与插入。
     * 该行仅提供公共互斥点，不按状态或逻辑删除过滤，也不代表任务请求人的身份。
     * 锁仅持有至接单短事务结束；该用户行上的其他更新也会与接单竞争，不在锁内执行远端调用。
     *
     * @return 已锁定的公共行 ID；用户表为空时返回空，由调用方按接单不可用处理
     */
    @Select("""
            SELECT id FROM wf_user ORDER BY id ASC LIMIT 1 FOR UPDATE
            """)
    Long lockAiAdmissionGuard();
}
