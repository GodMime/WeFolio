package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.dto.MineScheduleQueryRecordRow;
import com.jxc.wefolio.entity.ScheduleQueryRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 查询档期记录表 Mapper
 */
@Mapper
public interface ScheduleQueryRecordEntityMapper extends BaseMapper<ScheduleQueryRecordEntity> {

    /**
     * 分页查询当前维护者可见的个人与团队查档记录候选集。
     *
     * @param userId 当前用户 ID
     * @param teamIds 当前用户已加入的团队 ID
     * @param candidateLimit 每个分表的候选记录上限
     * @param offset 全局分页偏移量
     * @param resultLimit 全局返回上限，包含用于判断下一页的额外一条
     * @return 统一查档记录投影
     * @implNote 每个内层分支的记录类型为常量，因此其 queried_at、id 排序与外层完整排序键一致；
     * 修改外层排序时必须同步检查候选集排序，避免分页边界遗漏。
     */
    @Select("""
            <script>
            <choose>
              <when test='teamIds != null and teamIds.size() > 0'>
                SELECT source_record_id,record_type,portfolio_title_snapshot,visitor_id,visitor_key,
                       source_type,queried_date,slot_name_snapshot,start_time_snapshot,end_time_snapshot,
                       result_status,result_status_text,available,result_message,available_member_count,
                       partial_available_member_count,full_member_count,queried_at
                FROM (
                  (SELECT id AS source_record_id,'PERSONAL' AS record_type,portfolio_title_snapshot,
                          visitor_id,visitor_key,source_type,queried_date,slot_name_snapshot,
                          start_time_snapshot,end_time_snapshot,result_status,result_status_text,
                          available,result_message,0 AS available_member_count,
                          0 AS partial_available_member_count,0 AS full_member_count,queried_at
                   FROM wf_schedule_query_record
                   WHERE owner_type='USER' AND owner_id=#{userId}
                     AND portfolio_type='PERSONAL' AND deleted=0
                   ORDER BY queried_at DESC,id DESC
                   LIMIT #{candidateLimit})
                  UNION ALL
                  (SELECT id AS source_record_id,'TEAM' AS record_type,portfolio_title_snapshot,
                          visitor_id,visitor_key,source_type,queried_date,NULL AS slot_name_snapshot,
                          NULL AS start_time_snapshot,NULL AS end_time_snapshot,result_status,
                          result_status_text,available,result_message,available_member_count,
                          partial_available_member_count,full_member_count,queried_at
                   FROM wf_team_schedule_query_record
                   WHERE team_id IN
                   <foreach collection='teamIds' item='teamId' open='(' separator=',' close=')'>
                     #{teamId}
                   </foreach>
                     AND deleted=0
                   ORDER BY queried_at DESC,id DESC
                   LIMIT #{candidateLimit})
                ) visible_records
                ORDER BY queried_at DESC,record_type ASC,source_record_id DESC
                LIMIT #{offset},#{resultLimit}
              </when>
              <otherwise>
                SELECT id AS source_record_id,'PERSONAL' AS record_type,portfolio_title_snapshot,
                       visitor_id,visitor_key,source_type,queried_date,slot_name_snapshot,
                       start_time_snapshot,end_time_snapshot,result_status,result_status_text,
                       available,result_message,0 AS available_member_count,
                       0 AS partial_available_member_count,0 AS full_member_count,queried_at
                FROM wf_schedule_query_record
                WHERE owner_type='USER' AND owner_id=#{userId}
                  AND portfolio_type='PERSONAL' AND deleted=0
                ORDER BY queried_at DESC,id DESC
                LIMIT #{offset},#{resultLimit}
              </otherwise>
            </choose>
            </script>
            """)
    List<MineScheduleQueryRecordRow> selectVisibleRecords(
            @Param("userId") Long userId,
            @Param("teamIds") Collection<Long> teamIds,
            @Param("candidateLimit") long candidateLimit,
            @Param("offset") long offset,
            @Param("resultLimit") int resultLimit);

    /**
     * 统计当前维护者可见的个人与团队查档记录。
     *
     * @param userId 当前用户 ID
     * @param teamIds 当前用户已加入的团队 ID
     * @return 可见记录数
     */
    @Select("""
            <script>
            SELECT
              (SELECT COUNT(*)
               FROM wf_schedule_query_record
               WHERE owner_type='USER' AND owner_id=#{userId}
                 AND portfolio_type='PERSONAL' AND deleted=0)
              <if test='teamIds != null and teamIds.size() > 0'>
                + (SELECT COUNT(*)
                   FROM wf_team_schedule_query_record
                   WHERE team_id IN
                   <foreach collection='teamIds' item='teamId' open='(' separator=',' close=')'>
                     #{teamId}
                   </foreach>
                     AND deleted=0)
              </if>
              AS visible_count
            </script>
            """)
    Long countVisibleRecords(
            @Param("userId") Long userId,
            @Param("teamIds") Collection<Long> teamIds);
}
