package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.VisitRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;


/**
 * 访问汇总表 Mapper
 */
@Mapper
public interface VisitRecordEntityMapper extends BaseMapper<VisitRecordEntity> {

    /**
     * 原子累加访问汇总，避免并发事件之间通过乐观锁互相回滚。
     *
     * @param record 需要刷新快照的访问汇总
     * @param visitDelta 打开次数增量
     * @param viewWorkDelta 查看作品次数增量
     * @param playVideoDelta 播放视频次数增量
     * @param scheduleQueryDelta 查档次数增量
     * @param qrActionDelta 二维码互动次数增量
     * @param contactSubmitDelta 留资次数增量
     * @param durationDelta 停留秒数增量
     * @return 更新行数
     */
    @Update("""
            UPDATE wf_visit_record
            SET visitor_id = COALESCE(#{record.visitorId}, visitor_id),
                portfolio_title_snapshot = COALESCE(#{record.portfolioTitleSnapshot}, portfolio_title_snapshot),
                portfolio_share_code_snapshot = COALESCE(#{record.portfolioShareCodeSnapshot}, portfolio_share_code_snapshot),
                last_portfolio_revision = COALESCE(#{record.lastPortfolioRevision}, last_portfolio_revision),
                source_type = COALESCE(#{record.sourceType}, source_type),
                visit_count = visit_count + #{visitDelta},
                view_work_count = view_work_count + #{viewWorkDelta},
                play_video_count = play_video_count + #{playVideoDelta},
                schedule_query_count = schedule_query_count + #{scheduleQueryDelta},
                qr_action_count = qr_action_count + #{qrActionDelta},
                contact_submit_count = contact_submit_count + #{contactSubmitDelta},
                total_duration_seconds = total_duration_seconds + #{durationDelta},
                last_visited_at = #{record.lastVisitedAt},
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{record.id}
              AND deleted = 0
            """)
    int incrementCounters(
            @Param("record") VisitRecordEntity record,
            @Param("visitDelta") int visitDelta,
            @Param("viewWorkDelta") int viewWorkDelta,
            @Param("playVideoDelta") int playVideoDelta,
            @Param("scheduleQueryDelta") int scheduleQueryDelta,
            @Param("qrActionDelta") int qrActionDelta,
            @Param("contactSubmitDelta") int contactSubmitDelta,
            @Param("durationDelta") int durationDelta
    );
}
