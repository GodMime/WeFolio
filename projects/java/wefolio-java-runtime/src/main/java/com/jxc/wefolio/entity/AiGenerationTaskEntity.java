package com.jxc.wefolio.entity;

import java.time.LocalDateTime;
import com.jxc.wefolio.dict.AiTaskStatusDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;


/**
 * wf_ai_generation_task — AI 生成任务表 — 高级作品集 AI 生成请求与结果
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

    /** 任务状态，超时为 FAILED 加 TIMEOUT。@see AiTaskStatusDict */
    private String status;

    /** 校验通过的生成结果 JSON */
    @ToString.Exclude
    private String resultJson;

    /** 已领取次数，初次和自动重试共享最多三次预算。 */
    private Integer attemptCount;

    /** 等待任务最早执行时间。 */
    private LocalDateTime nextRunAt;

    /** 可恢复错误码，使用英文大写下划线 */
    private String errorCode;

    /** 脱敏错误摘要 */
    private String errorMessage;

    /** 最近一次领取时间，用于执行中断恢复。 */
    private LocalDateTime startedAt;

    /** 完成时间 */
    private LocalDateTime completedAt;

}
