package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.ScheduleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 档期表 Mapper
 */
@Mapper
public interface ScheduleEntityMapper extends BaseMapper<ScheduleEntity> {

    /**
     * 批量查询指定用户在目标日期的档期。
     *
     * @param userIds     用户 ID 集合
     * @param queriedDate 目标查询日期
     * @return 按用户、档位定义和主键稳定排序的档期
     */
    @Select("<script>"
            + "SELECT id,user_id,schedule_date,slot_definition_id,status,slot_name_snapshot,start_time_snapshot,end_time_snapshot,color_snapshot "
            + "FROM wf_schedule "
            + "WHERE user_id IN <foreach collection='userIds' item='userId' open='(' separator=',' close=')'>#{userId}</foreach> "
            + "AND schedule_date=#{queriedDate} AND deleted=0 "
            + "ORDER BY user_id, slot_definition_id, id"
            + "</script>")
    List<ScheduleEntity> selectByUserIdsAndDate(
            @Param("userIds") Collection<Long> userIds,
            @Param("queriedDate") LocalDate queriedDate);
}
