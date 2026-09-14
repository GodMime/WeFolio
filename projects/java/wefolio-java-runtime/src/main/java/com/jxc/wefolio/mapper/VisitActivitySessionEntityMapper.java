package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.VisitActivitySessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;

/** 活动会话持久化；锁顺序为访客（仅 open）、会话、访问汇总。 */
@Mapper
public interface VisitActivitySessionEntityMapper extends BaseMapper<VisitActivitySessionEntity> {
    /** 锁定已认证访客，串行化同访客并发首次打开，避免不存在会话的间隙锁竞争。 */
    @Select("SELECT id FROM wf_visitor WHERE id = #{visitorId} AND deleted = 0 FOR UPDATE")
    Long lockVisitor(@Param("visitorId") Long visitorId);

    /** 在访客行锁之后建立读取快照，避免不存在会话的间隙锁阻塞其他访客插入。 */
    @Select("""
        SELECT * FROM wf_visit_activity_session
        WHERE visitor_id = #{visitorId} AND portfolio_type = #{portfolioType}
          AND portfolio_id = #{portfolioId} AND client_session_key = #{clientSessionKey} AND deleted = 0
        LIMIT 1
        """)
    VisitActivitySessionEntity findOwnedForOpen(@Param("visitorId") Long visitorId,
            @Param("portfolioType") String portfolioType, @Param("portfolioId") Long portfolioId,
            @Param("clientSessionKey") String clientSessionKey);

    /** 检查首次打开键在本业务归属内是否已绑定另一活动键。 */
    @Select("""
        SELECT * FROM wf_visit_activity_session
        WHERE visitor_id = #{visitorId} AND portfolio_type = #{portfolioType}
          AND portfolio_id = #{portfolioId} AND open_idempotency_key = #{openKey} AND deleted = 0
        LIMIT 1
        """)
    VisitActivitySessionEntity findOpenKeyForOpen(@Param("visitorId") Long visitorId,
            @Param("portfolioType") String portfolioType, @Param("portfolioId") Long portfolioId,
            @Param("openKey") String openKey);

    /** 只锁定属于当前认证访客、作品集和类型的有效会话。 */
    @Select("""
        SELECT * FROM wf_visit_activity_session WHERE id = #{sessionId} AND visitor_id = #{visitorId}
          AND portfolio_type = #{portfolioType} AND portfolio_id = #{portfolioId} AND deleted = 0 FOR UPDATE
        """)
    VisitActivitySessionEntity lockOwned(@Param("sessionId") Long sessionId,
            @Param("visitorId") Long visitorId, @Param("portfolioType") String portfolioType,
            @Param("portfolioId") Long portfolioId);

    /** 在行锁下推进高水位并记录有效接收时间，重复或乱序样本仍更新接收时间。 */
    @Update("""
        UPDATE wf_visit_activity_session
        SET active_duration_ms = #{accepted}, last_reported_at = #{now}, updated_at = #{now}, version = version + 1
        WHERE id = #{id} AND deleted = 0 AND active_duration_ms = #{previous}
        """)
    int accept(@Param("id") Long id, @Param("previous") long previous,
            @Param("accepted") long accepted, @Param("now") LocalDateTime now);

    /** 最近有效设备按会话创建时间及 ID 排序，延迟上报不改变设备选择。 */
    @Select("""
        SELECT * FROM wf_visit_activity_session WHERE visit_record_id = #{recordId} AND deleted = 0
          AND (brand IS NOT NULL OR model IS NOT NULL OR `system` IS NOT NULL OR platform IS NOT NULL)
        ORDER BY created_at DESC, id DESC LIMIT 1
        """)
    VisitActivitySessionEntity selectLatestDevice(@Param("recordId") Long recordId);
}
