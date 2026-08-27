package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.dto.MineWorkSortRequest;
import com.jxc.wefolio.entity.WorkEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;


/**
 * 作品表 Mapper
 */
@Mapper
public interface WorkEntityMapper extends BaseMapper<WorkEntity> {

    /**
     * 按人工审核编号锁定未删除作品，确保审核结论串行写入。
     *
     * @param manualAuditNo 人工审核编号
     * @return 作品记录，不存在时返回空
     */
    @Select("""
            SELECT *
              FROM wf_work
             WHERE manual_audit_no = #{manualAuditNo}
               AND deleted = 0
             LIMIT 1
             FOR UPDATE
            """)
    WorkEntity lockByManualAuditNo(@Param("manualAuditNo") String manualAuditNo);

    /**
     * 在作品仍处于对应人工审核时写入首次结论。
     *
     * @param workId 作品 ID
     * @param manualAuditNo 人工审核编号
     * @param auditStatus 审核结论
     * @param auditRejectReason 人工拒绝原因
     * @param resultAt 结论写入时间
     * @return 影响行数
     */
    @Update("""
            UPDATE wf_work
               SET audit_status = #{auditStatus},
                   audit_reason_code = NULL,
                   audit_reason_codes = NULL,
                   audit_reject_reason = #{auditRejectReason},
                   manual_audit_result_at = #{resultAt},
                   updated_at = #{resultAt},
                   version = version + 1
             WHERE id = #{workId}
               AND manual_audit_no = #{manualAuditNo}
               AND status = 'ACTIVE'
               AND audit_status = 'AUDITING'
               AND deleted = 0
            """)
    int completeManualAudit(
            @Param("workId") Long workId,
            @Param("manualAuditNo") String manualAuditNo,
            @Param("auditStatus") String auditStatus,
            @Param("auditRejectReason") String auditRejectReason,
            @Param("resultAt") LocalDateTime resultAt);

    /**
     * 批量更新当前用户作品排序，调用方必须保证排序项非空。
     *
     * @param userId 当前用户 ID
     * @param items 排序项
     * @return 影响行数
     */
    @Update({
            "<script>",
            "UPDATE wf_work",
            "SET sort_order = CASE id",
            "<foreach collection='items' item='item'>",
            "WHEN #{item.workId} THEN #{item.sortOrder}",
            "</foreach>",
            "END",
            "WHERE user_id = #{userId}",
            "AND deleted = 0",
            "AND id IN",
            "<foreach collection='items' item='item' open='(' separator=',' close=')'>",
            "#{item.workId}",
            "</foreach>",
            "</script>"
    })
    int updateSortOrders(
            @Param("userId") Long userId,
            @Param("items") List<MineWorkSortRequest.Item> items
    );
}
