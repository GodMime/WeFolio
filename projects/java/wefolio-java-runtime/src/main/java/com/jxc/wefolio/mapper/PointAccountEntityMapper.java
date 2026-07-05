package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.PointAccountEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;


/**
 * 积分账户表 Mapper
 */
@Mapper
public interface PointAccountEntityMapper extends BaseMapper<PointAccountEntity> {

    /**
     * 原子扣减消费积分，并同步累计消耗和版本号。
     *
     * @param accountId 积分账户 ID
     * @param userId 用户 ID
     * @param points 扣减积分
     * @return 更新行数
     */
    @Update("""
            UPDATE wf_point_account
               SET balance = balance - #{points},
                   total_consumed = total_consumed + #{points},
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{accountId}
               AND user_id = #{userId}
               AND deleted = 0
               AND balance >= #{points}
            """)
    int deductConsumedPoints(
            @Param("accountId") Long accountId,
            @Param("userId") Long userId,
            @Param("points") Long points
    );
}
