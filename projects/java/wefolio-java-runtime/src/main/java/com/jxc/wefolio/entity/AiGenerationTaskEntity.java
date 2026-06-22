package com.jxc.wefolio.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_ai_generation_task — AI 生成任务表 — 高级作品集 AI 生成请求与结果
 */
@Data
@TableName("wf_ai_generation_task")
public class AiGenerationTaskEntity extends BaseEntity {

    /** 目标作品集 ID */
    private Long portfolioId;

    /** 发起人用户 ID，也是团队作品集扣费人 */
    private Long requestedBy;

    /** 生成结果被保存生效后的修订号，仅生成未保存时为空 */
    private Integer appliedRevision;

    /** 请求幂等键 */
    private String idempotencyKey;

    /** 自然语言描述 */
    private String prompt;

    /** 带序号的素材选择快照 JSON */
    private String selectedSourcesJson;

    /** 任务状态：PENDING 待执行 / RUNNING 执行中 / SUCCEEDED 成功 / FAILED 失败 / TIMED_OUT 超时 */
    private String status;

    /** 校验通过的生成结果 JSON */
    private String resultSchemaJson;

    /** 可恢复错误码，使用英文大写下划线 */
    private String errorCode;

    /** 脱敏错误摘要 */
    private String errorMessage;

    /** 成功任务实际扣除积分，失败为 0 */
    private Integer costPoints;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 完成时间 */
    private LocalDateTime completedAt;

}
