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
     * 锁定当前活动用户行，使同一用户的作品确认容量检查串行执行。
     *
     * @param userId 用户 ID
     * @return 已锁定的用户 ID，不存在时返回空
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
}
