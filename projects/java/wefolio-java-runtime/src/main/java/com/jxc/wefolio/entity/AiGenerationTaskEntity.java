package com.jxc.wefolio.entity;

import java.time.LocalDateTime;
import com.jxc.wefolio.dict.AiTaskStatusDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;


/**
 * wf_ai_generation_task — AI 生成任务表 — 高级作品集 AI 生成请求与结果，共 22 列（17 个业务字段及 5 个公共字段）
 */
@Data
@TableName("wf_ai_generation_task")
public class AiGenerationTaskEntity extends BaseEntity {

    /** 归属类型：USER 或 TEAM。@see PortfolioOwnerTypeDict */
    private String ownerType;

    /** 归属用户或团队 ID。 */
    private Long ownerId;

    /** 提交时已有作品集 ID；新建未保存时为空。 */
    private Long portfolioId;

    /** 发起人用户 ID；生成任务不扣用户积分。 */
    private Long requestedBy;

    /** 请求幂等键 */
    private String idempotencyKey;

    /** 冻结的编辑输入、授权事实、模型配置及契约版本，不含密钥。 */
    @ToString.Exclude
    private String requestJson;

    /** 不可变参考附件快照；与原请求分列存储，不含回执或临时地址。 */
    @ToString.Exclude
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String referenceAttachmentsJson;

    /** 任务状态，超时为 FAILED 加 TIMEOUT。@see AiTaskStatusDict */
    private String status;

    /** 校验通过的生成结果 JSON */
    @ToString.Exclude
    private String resultJson;

    /** 版本化逐调用预算，费用单位分；由 Job 显式维护，普通任务更新不得覆盖旧预算。 */
    @ToString.Exclude
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String mediaBudgetJson;

    /** 完整用量尚未返回的预留笔数；仅创建时置零，后续由 Job 显式维护。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer mediaBudgetUnknownCount;

    /** 已领取次数，初次和自动重试共享最多三次预算。 */
    private Integer attemptCount;

    /** 等待任务最早执行时间。 */
    private LocalDateTime nextRunAt;

    /** 可恢复错误码，使用英文大写下划线 */
    private String errorCode;

    /** 按执行次数追加的安全失败摘要，成功后保留至任务到期。 */
    private String errorMessage;

    /** 最近一次领取时间，用于执行中断恢复。 */
    private LocalDateTime startedAt;

    /** 完成时间 */
    private LocalDateTime completedAt;

}
