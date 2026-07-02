package com.jxc.wefolio.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_portfolio — 作品集表 — 同一作品集保存草稿配置与正式发布配置
 */
@Data
@TableName("wf_portfolio")
public class PortfolioEntity extends BaseEntity {

    /** 独立分享路径编码，不复用主键 */
    private String shareCode;

    /** 归属类型：USER 用户 / TEAM 团队 */
    private String ownerType;

    /** 归属用户 ID 或团队 ID */
    private Long ownerId;

    /** 模板类型：STANDARD 标准 / ADVANCED 高级 */
    private String templateType;

    /** 状态：ACTIVE 生效 / DISABLED 停用 */
    private String status;

    /** 当前组件 Schema 版本号 */
    private String schemaVersion;

    /** 草稿完整配置 JSON，维护端读取和保存 */
    private String draftConfigJson;

    /** 草稿版本号，保存草稿成功后递增 */
    private Integer draftRevision;

    /** 草稿配置规范化后的 SHA-256 */
    private String draftContentHash;

    /** 草稿最近保存人用户 ID */
    private Long draftSavedBy;

    /** 草稿最近保存时间 */
    private LocalDateTime draftSavedAt;

    /** 正式发布完整配置 JSON，访客端读取 */
    private String publishedConfigJson;

    /** 正式发布版本号，发布成功后递增 */
    private Integer publishedRevision;

    /** 正式配置规范化后的 SHA-256 */
    private String publishedContentHash;

    /** 最近发布人用户 ID */
    private Long publishedBy;

    /** 最近发布时间 */
    private LocalDateTime publishedAt;

    /** 发布状态：DRAFT_ONLY 草稿 / PUBLISHED 已发布 / OFFLINE 下线 */
    private String publicationStatus;

    /** 当前高级作品集自然语言描述 */
    private String aiPrompt;

    /** 保存来源：MANUAL 手工 / AI_GENERATED AI 生成 / RESTORED_FROM_HISTORY 历史恢复 */
    private String sourceType;

    /** 当前配置规范化后的 SHA256 */
    private String contentHash;

    /** 当前保存修订号，每次成功保存递增 */
    private Integer currentRevision;

    /** 最近预览时间，不作为生效开关 */
    private LocalDateTime previewedAt;

    /** 最近保存人用户 ID */
    private Long lastSavedBy;

    /** 最近成功保存时间 */
    private LocalDateTime lastSavedAt;

    /** 逻辑删除时间 */
    private LocalDateTime deletedAt;

}
