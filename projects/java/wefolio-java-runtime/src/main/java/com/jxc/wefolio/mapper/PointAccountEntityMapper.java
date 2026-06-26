package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.PointAccountEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;


/**
 * 积分账户表 Mapper
 */
@Mapper
public interface PointAccountEntityMapper extends BaseMapper<PointAccountEntity> {

    /**
     * 按用户 ID 锁定积分账户行。
     *
     * @param userId 用户 ID
     * @return 积分账户
     */
    @Select("""
            SELECT id, user_id, balance, total_recharged, total_gifted, total_consumed,
                   version, created_at, updated_at, deleted
              FROM wf_point_account
             WHERE user_id = #{userId}
               AND deleted = 0
             LIMIT 1
             FOR UPDATE
            """)
    PointAccountEntity selectByUserIdForUpdate(@Param("userId") Long userId);
}
