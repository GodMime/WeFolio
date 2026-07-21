package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.SlotDefinitionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 档位定义表 Mapper
 */
@Mapper
public interface SlotDefinitionEntityMapper extends BaseMapper<SlotDefinitionEntity> {

    /**
     * 批量查询指定用户的生效档位定义。
     *
     * @param userIds 用户 ID 集合
     * @return 按用户、开始时间和主键稳定排序的生效档位定义
     */
    @Select("<script>"
            + "SELECT id,user_id,name,start_time,end_time,color,status FROM wf_slot_definition "
            + "WHERE user_id IN <foreach collection='userIds' item='userId' open='(' separator=',' close=')'>#{userId}</foreach> "
            + "AND status='ACTIVE' AND deleted=0 "
            + "ORDER BY user_id, start_time, id"
            + "</script>")
    List<SlotDefinitionEntity> selectActiveByUserIds(@Param("userIds") Collection<Long> userIds);
}
