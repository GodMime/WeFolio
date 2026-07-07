# 作品审核拒绝原因设计方案

版本：v1.0
日期：2026-07-07

本文档描述作品内容审核结果中“未通过原因”的落库方案。该方案是 `docs/work-audit-job-design.md` 的补充，只覆盖作品模型新增审核拒绝原因字段，以及 job 工程在审核结果落库时如何写入该字段。

## 1. 目标与边界

### 1.1 目标

- `wf_work` 新增字符串字段，用于记录作品审核未通过原因。
- `wefolio-java-runtime` 的作品实体新增对应字段。
- `wefolio-java-job` 的内部作品实体和仓储新增对应字段与更新方法。
- job 工程在图片同步审核、视频异步结果查询、提交失败、查询超限等场景中，把可读的审核拒绝原因写回 `wf_work`。
- 审核通过时清空拒绝原因，避免历史原因残留。

### 1.2 非目标

- 暂不建设人工审核管理入口。
- 暂不让审核拒绝原因参与业务拦截、作品展示、作品集渲染或访客访问。
- 暂不新增 runtime 对外接口返回该字段，除非后续业务明确需要。
- 暂不在 `wf_work_audit_task` 新增冗余拒绝原因字段；任务表继续保存腾讯云原始摘要字段和错误信息。

## 2. 数据库变更

所有 SQL 变更继续走 `wefolio-java-runtime` 工程 Flyway，`wefolio-java-job` 不引入 Flyway。

新增 migration：

```text
projects/java/wefolio-java-runtime/src/main/resources/db/migration/V28__add_work_audit_reject_reason.sql
```

建议 SQL：

```sql
ALTER TABLE `wf_work`
  ADD COLUMN `audit_reject_reason` VARCHAR(512) NULL COMMENT '审核拒绝原因：违规、疑似、失败等未通过原因摘要'
  AFTER `audit_status`;
```

字段说明：

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `audit_reject_reason` | `VARCHAR(512)` | `NULL` | 记录审核未通过、疑似、失败等需要人工查看的原因摘要，超长由 job 写库前截断 |

设计选择：

- 使用 `VARCHAR(512)`，控制作品主表字段大小，满足当前运维排查和人工查看摘要诉求。
- job 写入前统一截断到 512 个字符，避免数据库报字段超长异常。
- 允许为空；`PASSED`、`PENDING`、`AUDITING` 通常为空。
- 不加索引；该字段用于查看原因，不作为扫描条件。
- 不加检查约束；内容来自外部审核结果和系统错误摘要，形式不适合枚举约束。

## 3. Java 模型变更

### 3.1 runtime 工程

修改文件：

```text
projects/java/wefolio-java-runtime/src/main/java/com/jxc/wefolio/entity/WorkEntity.java
```

新增字段：

```java
/**
 * 审核拒绝原因。
 */
private String auditRejectReason;
```

runtime 本次只做实体字段补充，不新增业务逻辑，不新增接口返回字段，不调整作品查询流程。

### 3.2 job 工程

修改文件：

```text
projects/java/wefolio-java-job/src/main/java/com/jxc/wefolio/job/entity/WorkAuditWorkEntity.java
```

新增字段：

```java
/**
 * 审核拒绝原因。
 */
private String auditRejectReason;
```

修改文件：

```text
projects/java/wefolio-java-job/src/main/java/com/jxc/wefolio/job/repository/WorkAuditWorkRepository.java
```

新增或替换状态更新方法：

```java
/**
 * 更新作品审核状态和拒绝原因。
 *
 * @param workId 作品 ID
 * @param auditStatus 审核状态
 * @param auditRejectReason 审核拒绝原因，审核通过时传空
 * @return 更新行数
 */
int updateAuditStatusAndRejectReason(Long workId, WorkAuditStatusDict auditStatus, String auditRejectReason);
```

仓储实现要求：

- 更新 `audit_status`。
- 同步更新 `audit_reject_reason`。
- 更新 `updated_at`。
- 条件包含 `id = workId` 和 `deleted = 0`。

## 4. 拒绝原因映射

`wf_work.audit_status` 与 `wf_work.audit_reject_reason` 的写入规则：

| 审核状态 | 拒绝原因 |
|---|---|
| `PENDING` | `NULL` |
| `AUDITING` | `NULL` |
| `PASSED` | `NULL` |
| `REJECTED` | 写入明确违规原因 |
| `REVIEW_REQUIRED` | 写入疑似违规、需人工复核原因 |
| `FAILED` | 写入调用失败、提交失败、查询超限或结果异常原因 |

腾讯云审核结果映射：

| 腾讯云结果 | 作品状态 | 拒绝原因示例 |
|---|---|---|
| 正常 | `PASSED` | `NULL` |
| 违规 | `REJECTED` | `腾讯云判定违规：label=Porn，result=1，score=98` |
| 疑似 | `REVIEW_REQUIRED` | `腾讯云判定疑似违规，需人工复核：label=Sexy，result=2，score=86` |
| 未知结果 | `FAILED` | `腾讯云审核结果未知：state=Success，result=null，label=null` |
| 图片调用异常 | `FAILED` | `图片审核调用腾讯云失败：<异常摘要>` |
| 视频提交异常 | `FAILED` | `提交腾讯云视频审核失败：<异常摘要>` |
| 视频任务失败态 | `FAILED` | `腾讯云视频审核任务失败：state=Failed，jobId=<腾讯云任务ID>` |
| 视频查询超过上限 | `FAILED` | `视频审核查询次数超过上限：queryCount=120，maxQueryCount=120` |

原因生成原则：

- 面向运维和后续人工审核人员可读，不直接保存大段原始响应。
- 优先包含 `label`、`result`、`score`、`state`、`jobId` 等定位字段。
- 异常信息只保留摘要，避免写入过长堆栈。
- 最终写入 `audit_reject_reason` 前必须统一截断到 512 个字符。
- 不写入 `COS_SECRET_ID`、`COS_SECRET_KEY`、签名 URL 等敏感信息。
- 腾讯云疑似违规统一落 `REVIEW_REQUIRED`，不直接按违规处理。

## 5. Job 流程调整

### 5.1 图片审核

处理顺序不变，仍然一个作品一个作品同步调用腾讯云图片审核。

落库规则：

```text
PASS:
  wf_work.audit_status = PASSED
  wf_work.audit_reject_reason = NULL

BLOCK:
  wf_work.audit_status = REJECTED
  wf_work.audit_reject_reason = 明确违规原因

REVIEW:
  wf_work.audit_status = REVIEW_REQUIRED
  wf_work.audit_reject_reason = 疑似违规原因

UNKNOWN 或调用异常:
  wf_work.audit_status = FAILED
  wf_work.audit_reject_reason = 失败原因
```

### 5.2 视频提交

提交腾讯云视频异步审核成功时：

```text
wf_work.audit_status = AUDITING
wf_work.audit_reject_reason = NULL
```

提交腾讯云失败时：

```text
wf_work.audit_status = FAILED
wf_work.audit_reject_reason = 提交失败原因
```

提交失败仍然不自动重试，后续通过 SQL 运维重置为 `PENDING` 后再进入审核。

### 5.3 视频结果查询

查询到处理中时：

```text
wf_work.audit_status = AUDITING
wf_work.audit_reject_reason = NULL
wf_work_audit_task.task_status = RUNNING
```

查询到终态时：

```text
PASS   -> PASSED + NULL
BLOCK  -> REJECTED + 明确违规原因
REVIEW -> REVIEW_REQUIRED + 疑似违规原因
UNKNOWN 或失败态 -> FAILED + 失败原因
```

查询腾讯云接口失败时：

- 未达到最大查询次数：任务回到 `RUNNING`，作品保持 `AUDITING`，拒绝原因保持为空。
- 达到最大查询次数：任务落 `FAILED`，作品落 `FAILED`，拒绝原因写入查询超限原因。

单个视频审核任务最大主动查询次数继续由配置控制，当前默认值为 `120`。

## 6. 事务颗粒度

远程腾讯云调用不放入数据库事务。

推荐事务边界：

| 阶段 | 是否开启事务 | 说明 |
|---|---|---|
| 扫描待处理作品 | 否 | 只读查询 |
| claim 图片或视频任务 | 是，短事务 | 条件更新任务状态和锁字段 |
| 调用腾讯云接口 | 否 | 避免长事务占用数据库连接和锁 |
| 写入任务结果和作品状态 | 是，短事务 | 同一审核结果下，任务表和作品表一起更新 |
| 统计待处理数量 | 否 | 只读统计 |

落库一致性要求：

- 同一次审核结果对应的 `wf_work_audit_task` 和 `wf_work` 更新应在同一个短事务内完成。
- `audit_status` 和 `audit_reject_reason` 必须同一次更新，避免状态和原因短暂不一致。
- 事务内只做本地数据库更新，不做腾讯云调用。
- 如果落库失败，让异常向上抛出，由 job 日志记录本轮失败；下一轮依赖现有状态继续处理或等待人工介入。

## 7. 日志要求

现有审核日志继续保留，并补充拒绝原因摘要：

- 当前处理作品：`workId`、`userId`、`mediaType`、`mediaObjectKey`、`taskId`。
- 腾讯云入参和出参：打印前必须脱敏 `COS_SECRET_ID`、`COS_SECRET_KEY`、签名参数。
- 审核简洁结果：`auditStatus`、`auditResult`、`ciLabel`、`ciScore`、`auditRejectReason`。
- 失败日志：记录失败原因摘要，不打印完整密钥、授权头、签名 URL。

日志示例：

```text
作品审核结果落库: workId=1001, taskId=2001, auditStatus=REVIEW_REQUIRED, auditResult=REVIEW, auditRejectReason=腾讯云判定疑似违规，需人工复核：label=Sexy，result=2，score=86
```

## 8. 测试方案

### 8.1 runtime 工程测试

运行：

```bash
cd projects/java/wefolio-java-runtime
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn test
```

建议覆盖：

- migration 测试确认存在 `audit_reject_reason` 字段。
- `WorkEntity` 结构测试确认存在 `auditRejectReason` 字段。

### 8.2 job 工程测试

运行：

```bash
cd projects/java/wefolio-java-job
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn test
```

建议覆盖：

- 图片审核通过时，作品状态更新为 `PASSED`，拒绝原因清空。
- 图片审核违规时，作品状态更新为 `REJECTED`，拒绝原因包含腾讯云标签和分数。
- 图片审核疑似时，作品状态更新为 `REVIEW_REQUIRED`，拒绝原因包含“需人工复核”。
- 图片审核异常时，作品状态更新为 `FAILED`，拒绝原因包含调用失败摘要。
- 视频提交成功时，作品状态更新为 `AUDITING`，拒绝原因清空。
- 视频提交失败时，作品状态更新为 `FAILED`，拒绝原因包含提交失败摘要。
- 视频查询通过时，作品状态更新为 `PASSED`，拒绝原因清空。
- 视频查询违规或疑似时，作品状态和拒绝原因按映射写入。
- 视频查询超过上限时，作品状态更新为 `FAILED`，拒绝原因包含最大查询次数。
- 仓储单元测试确认 `audit_status` 和 `audit_reject_reason` 同一次更新。

真实腾讯云连通性测试不作为默认单元测试执行，仍通过 `wefolio-java-job/cos-test.sh` 手动触发。

## 9. 实施步骤

1. 在 runtime 新增 `V28__add_work_audit_reject_reason.sql`。
2. 在 runtime `WorkEntity` 新增 `auditRejectReason` 字段。
3. 在 job `WorkAuditWorkEntity` 新增 `auditRejectReason` 字段。
4. 在 job `WorkAuditWorkRepository` 新增 `updateAuditStatusAndRejectReason`。
5. 在 job 审核服务中集中封装拒绝原因生成方法，并使用常量定义最大长度 `512`。
6. 将所有作品审核状态更新替换为状态和拒绝原因一起更新。
7. 补充日志中的 `auditRejectReason` 摘要。
8. 补充 runtime 和 job 两侧测试。
9. 分别运行 runtime、job 的 Maven 测试。

## 10. 运维处理

当前不建设管理入口。`FAILED` 或 `REVIEW_REQUIRED` 后如需重新审核，先通过 SQL 运维处理。

重置示例：

```sql
UPDATE `wf_work`
SET `audit_status` = 'PENDING',
    `audit_reject_reason` = NULL,
    `updated_at` = CURRENT_TIMESTAMP(3)
WHERE `id` = ? AND `deleted` = 0;
```

如对应任务也需要重新生成，应结合 `wf_work_audit_task` 的实际记录做逻辑删除或状态修正，避免唯一索引冲突。
