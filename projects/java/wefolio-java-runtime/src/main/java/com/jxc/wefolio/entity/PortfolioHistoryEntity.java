package com.jxc.wefolio.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_portfolio_history — 作品集历史保存表 — 每次保存形成只读快照，仅用于审计和恢复
 */
@Data
@TableName("wf_portfolio_history")
public class PortfolioHistoryEntity extends BaseEntity {

    /** 所属作品集 ID */
    private Long portfolioId;

    /** 保存修订号，与保存后的 current_revision 一致 */
    private Integer revisionNo;

    /** 组件 Schema 版本号 */
    private String schemaVersion;

    /** 完整保存快照 JSON（标题、分享信息、Schema、引用） */
    private String snapshotJson;

    /** 本次保存使用的自然语言描述 */
    private String aiPrompt;

    /** 保存来源：MANUAL / AI_GENERATED / RESTORED_FROM_HISTORY */
    private String sourceType;

    /** 快照规范化后的 SHA256 */
    private String contentHash;

    /** 保存人用户 ID */
    private Long savedBy;

    /** 保存时间 */
    private LocalDateTime savedAt;

}
