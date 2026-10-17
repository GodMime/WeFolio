package com.jxc.wefolio.job.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/** 高级作品集 AI 异步任务及独立媒体费用记录。 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"requestJson", "resultJson", "mediaBudgetJson", "referenceAttachmentsJson"})
@TableName("wf_ai_generation_task")
public class AiGenerationTaskEntity extends BaseEntity {
    /** 真实归属类型 USER 或 TEAM。 */
    private String ownerType;
    /** 归属用户或团队主键。 */
    private Long ownerId;
    /** 提交时已有作品集主键，新建未保存时为空。 */
    private Long portfolioId;
    /** 发起人主键，生成不扣发起人积分。 */
    private Long requestedBy;
    /** 请求人作用域内的提交幂等键。 */
    private String idempotencyKey;
    /** 不可变业务请求、可信事实、模型配置及版本，不含密钥。 */
    private String requestJson;
    /** 本次参考附件不可变快照，不含回执或私有读取链接。 */
    private String referenceAttachmentsJson;
    /** 合法中间模型结果，不是作品集配置。 */
    private String resultJson;
    /** 独立逐调用费用协议，由专用短事务更新，禁止普通实体更新回带旧值。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String mediaBudgetJson;
    /** 尚无完整用量的调用数，隐私清理保留其最小费用行。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer mediaBudgetUnknownCount;
    /** 五态任务状态。 */
    private String status;
    /** 已领取执行次数，含中断的尝试。 */
    private Integer attemptCount;
    /** 等待任务最早执行时间。 */
    private LocalDateTime nextRunAt;
    /** 最近一次领取时间，也是恢复窗口基准。 */
    private LocalDateTime startedAt;
    /** 首次进入终态的时间。 */
    private LocalDateTime completedAt;
    /** 最近一次失败的固定错误码。 */
    private String errorCode;
    /** 按执行次数追加的安全失败摘要，不含模型原文，成功后保留至任务到期。 */
    private String errorMessage;
}
