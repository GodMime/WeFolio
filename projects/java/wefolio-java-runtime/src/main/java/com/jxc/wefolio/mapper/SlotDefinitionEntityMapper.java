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
     * 统计同一用户未删除档位中使用指定颜色的冲突记录。
     *
     * @param userId 用户 ID
     * @param color 标准化后的颜色
     * @param excludedId 编辑时排除的档位 ID，新增时为空
     * @return 冲突记录数
     */
    @Select("<script>"
            + "SELECT COUNT(*) FROM wf_slot_definition "
            + "WHERE user_id=#{userId} AND color=#{color} AND deleted=0 "
            + "<if test='excludedId != null'>AND id&lt;&gt;#{excludedId}</if>"
            + "</script>")
    long countColorConflicts(
            @Param("userId") Long userId,
            @Param("color") String color,
            @Param("excludedId") Long excludedId
    );

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
