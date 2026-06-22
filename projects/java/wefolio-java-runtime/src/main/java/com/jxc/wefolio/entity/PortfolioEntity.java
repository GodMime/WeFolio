package com.jxc.wefolio.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_portfolio — 作品集表 — 当前生效配置，每次保存直接覆盖，访客直接读取
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

    /** 作品集标题 */
    private String title;

    /** 作品集简介 */
    private String intro;

    /** 分享封面 */
    private String shareCoverUrl;

    /** 分享头像 */
    private String shareAvatarUrl;

    /** 状态：ACTIVE 生效 / DISABLED 停用 */
    private String status;

    /** 当前组件 Schema 版本号 */
    private String schemaVersion;

    /** 当前生效的页面配置 JSON，访客直接读取渲染 */
    private String schemaJson;

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
