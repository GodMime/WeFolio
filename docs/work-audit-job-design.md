# 作品内容审核 Job 设计方案

版本：v1.0
日期：2026-07-07

本文档描述作品内容审核的整体方案。当前阶段只做审核状态和后台任务，不让审核结果实际参与作品展示、作品集渲染、访客访问等业务逻辑。

## 1. 目标与边界

### 1.1 目标

- `wf_work` 新增审核状态字段，默认未审核。
- `wefolio-java-job` 新增定时任务，每分钟主动扫描并处理待审核作品。
- 图片作品使用腾讯云数据万象图片审核能力，同步审核。
- 视频作品使用腾讯云数据万象视频审核能力，异步提交，主动查询结果，不使用回调。
- 视频审核只做画面审核，不做音频审核。
- 视频画面审核按 60 秒 1 帧截帧；例如 4 分半视频为 `ceil(270 / 60) = 5` 帧。
- 所有 SQL 变更都走 `wefolio-java-runtime` 工程的 Flyway SQL 脚本。

### 1.2 非目标

- 暂不拦截违规作品展示。
- 暂不隐藏或下架作品。
- 暂不做人工审核后台。
- 暂不接入腾讯云回调。
- 暂不审核视频音频轨道。

## 2. 外部能力

使用腾讯云数据万象内容审核能力：

- 图片审核：对 COS 对象发起图片单次审核，同步返回审核结果。
- 视频审核：提交视频审核任务，返回腾讯云审核任务 ID。
- 视频结果查询：根据腾讯云审核任务 ID 主动查询审核结果。

参考文档：

- 图片审核：https://cloud.tencent.com/document/product/460/37318
- 视频审核：https://cloud.tencent.com/document/product/460/46426
- 查询视频审核结果：https://cloud.tencent.com/document/product/460/47362

## 3. 状态设计

### 3.1 作品审核状态

`wf_work.audit_status`：

| 状态 | 含义 | 说明 |
|---|---|---|
| `PENDING` | 未审核 | 默认值，等待 job 处理 |
| `AUDITING` | 审核中 | 图片正在同步审核，或视频已提交异步审核 |
| `PASSED` | 审核通过 | 腾讯云返回正常 |
| `REJECTED` | 确认违规 | 腾讯云返回违规 |
| `REVIEW_REQUIRED` | 疑似违规 | 腾讯云返回疑似，需要后续人工能力承接 |
| `FAILED` | 审核失败 | 提交失败、审核调用失败、查询次数超过上限或数据异常 |

当前阶段这些状态只落库，不参与业务逻辑。腾讯云返回疑似违规时统一落 `REVIEW_REQUIRED`，不直接按违规处理。`FAILED` 暂不建设管理入口，需人工确认后通过运维 SQL 重置为 `PENDING` 才会重新进入审核。

### 3.2 审核任务状态

`wf_work_audit_task.task_status`：

| 状态 | 含义 | 适用场景 |
|---|---|---|
| `PENDING` | 待处理 | 任务记录已创建但尚未发起审核 |
| `SUBMITTING` | 提交中 | 已 claim，准备调用腾讯云提交接口 |
| `SUBMITTED` | 已提交 | 视频审核任务已提交，等待结果 |
| `RUNNING` | 处理中 | 腾讯云视频审核仍在处理中 |
| `QUERYING` | 查询中 | 已 claim，准备查询视频审核结果 |
| `SUCCESS` | 成功终态 | 已拿到通过、违规或疑似结果 |
| `FAILED` | 失败终态 | 提交失败、审核调用失败或查询次数超过上限 |

`SUBMITTING` 和 `QUERYING` 是短暂锁定状态，用于防止多实例或重复调度并发处理同一条任务。若进程异常退出，可通过 `locked_until` 超时释放。

### 3.3 审核结果

`wf_work_audit_task.audit_result`：

| 结果 | 含义 |
|---|---|
| `PASS` | 正常 |
| `BLOCK` | 确认违规 |
| `REVIEW` | 疑似违规 |
| `UNKNOWN` | 未知或未拿到结果 |

映射关系：

```text
PASS   -> wf_work.audit_status = PASSED
BLOCK  -> wf_work.audit_status = REJECTED
REVIEW -> wf_work.audit_status = REVIEW_REQUIRED
UNKNOWN + 视频结果查询失败未超查询次数上限 -> 保持 AUDITING
UNKNOWN + 提交失败 / 审核调用失败 / 查询次数超限 -> wf_work.audit_status = FAILED
```

## 4. 数据库模型

### 4.1 `wf_work` 变更

通过 `wefolio-java-runtime` 新增 Flyway migration：

```text
V27__add_work_audit.sql
```

字段：

```sql
ALTER TABLE `wf_work`
  ADD COLUMN `audit_status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'PENDING' COMMENT '审核状态：PENDING未审核 AUDITING审核中 PASSED通过 REJECTED违规 REVIEW_REQUIRED疑似 FAILED失败'
    AFTER `status`,
  ADD KEY `idx_work_audit_scan` (`audit_status`, `media_type`, `deleted`, `id`);
```

约束：

```sql
ALTER TABLE `wf_work`
  ADD CONSTRAINT `chk_work_audit_status`
  CHECK (`audit_status` IN ('PENDING', 'AUDITING', 'PASSED', 'REJECTED', 'REVIEW_REQUIRED', 'FAILED'));
```

### 4.2 新增 `wf_work_audit_task`

建议字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT UNSIGNED | 主键 |
| `work_id` | BIGINT UNSIGNED | 作品 ID |
| `user_id` | BIGINT UNSIGNED | 用户 ID，冗余便于查询和隔离 |
| `media_type` | VARCHAR(16) ascii_bin | `IMAGE` / `VIDEO` |
| `media_object_key` | VARCHAR(512) | COS 媒体对象键 |
| `media_sha256` | CHAR(64) ascii_bin NULL | 文件 SHA-256 |
| `provider` | VARCHAR(32) ascii_bin | 默认 `TENCENT_CI` |
| `task_status` | VARCHAR(32) ascii_bin | 任务状态 |
| `audit_result` | VARCHAR(32) ascii_bin | 审核结果 |
| `ci_job_id` | VARCHAR(128) NULL | 腾讯云视频审核任务 ID |
| `ci_state` | VARCHAR(64) NULL | 腾讯云任务状态 |
| `ci_result` | INT NULL | 腾讯云结果码或业务结果 |
| `ci_label` | VARCHAR(128) NULL | 命中的主要标签 |
| `ci_score` | INT NULL | 命中分数摘要 |
| `snapshot_interval_seconds` | INT UNSIGNED NULL | 视频截帧间隔，默认 60 |
| `snapshot_count` | INT UNSIGNED NULL | 视频截帧数量 |
| `attempt_count` | INT UNSIGNED | 提交或图片审核调用尝试次数；当前提交失败和图片调用失败不自动重试，主要用于留痕 |
| `query_count` | INT UNSIGNED | 视频结果查询次数，每次 job 主动查询前递增 |
| `last_query_at` | DATETIME(3) NULL | 最近一次查询视频结果时间 |
| `locked_by` | VARCHAR(128) NULL | 当前处理实例 |
| `locked_until` | DATETIME(3) NULL | 锁过期时间 |
| `started_at` | DATETIME(3) NULL | 开始处理时间 |
| `submitted_at` | DATETIME(3) NULL | 视频提交成功时间 |
| `finished_at` | DATETIME(3) NULL | 终态时间 |
| `last_error_message` | VARCHAR(1000) NULL | 最近一次错误 |
| `request_payload` | JSON NULL | 请求摘要 |
| `response_payload` | JSON NULL | 响应摘要 |
| `created_at` | DATETIME(3) | 创建时间 |
| `updated_at` | DATETIME(3) | 更新时间 |
| `deleted` | BIGINT UNSIGNED | 逻辑删除 |
| `version` | INT UNSIGNED | 乐观锁版本号 |

核心索引：

```sql
KEY `idx_work_audit_task_video_query` (`media_type`, `task_status`, `deleted`, `id`)
KEY `idx_work_audit_task_work` (`work_id`, `deleted`, `id`)
KEY `idx_work_audit_task_ci_job` (`ci_job_id`)
UNIQUE KEY `uk_work_audit_task_media` (`work_id`, `media_sha256`, `deleted`)
```

`deleted` 使用和 runtime 实体一致的逻辑删除约定，唯一索引包含 `deleted`。

## 5. Job 流程

### 5.1 调度入口

`wefolio-java-job` 中新增 `WorkAuditJob`：

```text
cron: 0 * * * * ?
```

每分钟触发一次。进程内使用 `AtomicBoolean` 防止同一实例内重入：

```text
如果上一轮尚未结束，本轮直接跳过。
```

考虑未来可能部署多个 job 实例，数据库层仍必须用条件更新和短锁字段做兜底，避免同一作品或同一视频任务被多个实例重复处理。

### 5.2 单轮执行顺序

单轮流程固定为：

```text
0. 查询之前还没到结束态的视频审核结果，并更新状态
1. 扫描未审核视频作品，逐个发起异步审核，不等待结果
2. 扫描未审核图片作品，逐个同步审核
3. 查询所有还没到结束态的视频审核结果，并更新状态
```

第 3 步的范围包括：

- 本轮第 1 步刚提交的视频任务。
- 第 0 步查询后仍未结束的视频任务。
- 更早之前仍未结束的视频任务。

### 5.3 第 0 步：查询历史视频结果

查询条件：

```text
media_type = VIDEO
task_status IN (SUBMITTED, RUNNING)
deleted = 0
query_count < video-query-max-attempts
```

按 `id ASC` 限量查询。默认配置放大：

```yaml
work-audit:
  max-query-video-per-run: 1000
```

处理方式：

1. 短事务 claim 一条任务：`SUBMITTED/RUNNING -> QUERYING`，写入 `locked_by`、`locked_until`，并将 `query_count + 1`。
2. 事务提交。
3. 事务外调用腾讯云查询视频审核结果接口。
4. 新事务落库查询结果。
5. 如果腾讯云仍在处理中，且本次查询后未达到查询次数上限，任务状态更新为 `RUNNING`，本轮不再等待；下一轮 job 会再次自动查询。
6. 如果腾讯云返回终态，任务状态改为 `SUCCESS`，作品审核状态改为 `PASSED / REJECTED / REVIEW_REQUIRED`。
7. 如果查询失败且未超过查询次数上限，直接记录失败原因，任务回到 `RUNNING`；本轮不做任何立即重试，下一轮 job 再自动查询。
8. 如果本次查询后任务查询次数达到上限且仍未拿到终态，任务改为 `FAILED`，作品审核状态改为 `FAILED`，记录“查询次数超过上限”等失败原因，后续人工介入。

### 5.4 第 1 步：提交新视频审核

查询条件：

```text
wf_work.audit_status = PENDING
wf_work.media_type = VIDEO
wf_work.deleted = 0
```

按 `id ASC` 限量查询。默认配置放大：

```yaml
work-audit:
  max-submit-video-per-run: 500
```

处理方式：

1. 短事务 claim 一个作品：`wf_work.audit_status: PENDING -> AUDITING`。
2. 同一事务内创建 `wf_work_audit_task`，状态为 `SUBMITTING`，记录 `locked_by`、`locked_until`。
3. 事务提交。
4. 事务外调用腾讯云提交视频审核任务接口。
5. 新事务写入 `ci_job_id`，任务状态改为 `SUBMITTED`，写入 `submitted_at`。
6. 不等待视频审核结果，继续处理下一个视频。

如果提交腾讯云失败：

- 不做自动重试。
- 新事务记录失败原因，写入 `last_error_message` 和 `response_payload`。
- 任务状态改为 `FAILED`，作品审核状态改为 `FAILED`。
- 后续由人工介入处理，当前阶段只通过运维 SQL 重置为 `PENDING` 后重新审核。

### 5.5 第 2 步：同步审核图片

查询条件：

```text
wf_work.audit_status = PENDING
wf_work.media_type = IMAGE
wf_work.deleted = 0
```

按 `id ASC` 限量查询。默认配置放大：

```yaml
work-audit:
  max-audit-image-per-run: 500
```

处理方式：

1. 短事务 claim 一个作品：`wf_work.audit_status: PENDING -> AUDITING`。
2. 同一事务内创建 `wf_work_audit_task`，状态为 `SUBMITTING`。
3. 事务提交。
4. 事务外调用腾讯云图片审核接口。
5. 新事务写入审核结果，任务状态改为 `SUCCESS`，作品审核状态改为 `PASSED / REJECTED / REVIEW_REQUIRED`。

如果图片审核失败：

- 不做自动重试。
- 新事务记录失败原因，写入 `last_error_message` 和 `response_payload`。
- 任务状态改为 `FAILED`，作品审核状态改为 `FAILED`。
- 后续由人工介入处理，当前阶段只通过运维 SQL 重置为 `PENDING` 后重新审核。

### 5.6 第 3 步：再次查询视频结果

执行逻辑与第 0 步相同。

第 3 步的目的不是等待视频完成，而是尽快收敛本轮刚提交后立刻完成的小视频任务，以及第 0 步查询时还未完成但随后已完成的任务。

## 6. 视频审核参数

视频只做画面审核，不做音频审核。

截帧策略：

```text
snapshot_interval_seconds = 60
snapshot_count = ceil(duration_ms / 60000)
```

边界规则：

- `duration_ms` 为空或小于等于 0 时，默认截 1 帧。
- `snapshot_count` 至少为 1。
- 可配置最大截帧数，防止超长视频产生过多审核帧。

默认配置：

```yaml
work-audit:
  video-snapshot-interval-seconds: 60
  max-video-snapshot-count: 120
```

如果视频时长超过 120 分钟，按最多 120 帧处理。当前小程序上传限制下通常不会触达该上限。

## 7. 配置设计

### 7.1 数据库与 COS 配置

`wefolio-java-job` 连接数据库和 COS 的配置命名参考 `wefolio-java-runtime`，沿用同一组环境变量。job 工程不引入 Flyway，因此不配置 `spring.flyway`。

`wefolio-java-job` 只复用数据库和 COS 配置约定，不复用 `wefolio-java-runtime` 的 Java 类。job 内部需要读写 `wf_work` 和 `wf_work_audit_task` 时，独立定义自己的 entity 和 repo。

```yaml
spring:
  application:
    name: wefolio-java-job

  datasource:
    # 数据库连接地址，参考 wefolio-java-runtime。
    url: ${DB_URL}
    # 数据库用户名，参考 wefolio-java-runtime。
    username: ${DB_USERNAME}
    # 数据库密码，参考 wefolio-java-runtime。
    password: ${DB_PASSWORD}
    # MySQL JDBC 驱动。
    driver-class-name: com.mysql.cj.jdbc.Driver

mybatis-plus:
  configuration:
    # 开启下划线字段到 Java 驼峰字段的自动映射。
    map-underscore-to-camel-case: true
  # Mapper XML 扫描路径，和 runtime 保持同类结构。
  mapper-locations: classpath:mapper/**/*.xml
  # job 工程实体包路径。
  type-aliases-package: com.jxc.wefolio.job.entity

wefolio:
  mybatis:
    # MyBatis SQL 日志开关，默认关闭。
    sql-log-enabled: ${WEFOLIO_MYBATIS_SQL_LOG_ENABLED:false}

server:
  # 默认只监听本机地址，和 runtime 保持一致。
  address: 127.0.0.1
  # job 工程默认端口。
  port: 8091

cos:
  # 腾讯云访问密钥 ID，参考 wefolio-java-runtime。
  secret-id: ${COS_SECRET_ID}
  # 腾讯云访问密钥 Secret，参考 wefolio-java-runtime。
  secret-key: ${COS_SECRET_KEY}
  # COS 地域。
  region: ${COS_REGION:ap-guangzhou}
  # COS 存储桶名称。
  bucket-name: ${COS_BUCKET_NAME}
  # 小程序直传上传域名；job 当前不直传，但保留同名配置以复用 CosProperties。
  upload-base-url: ${COS_UPLOAD_BASE_URL:https://cos.we-folio.dingchenyong.top}
  # COS 或 CDN 对外访问域名。
  public-base-url: ${COS_PUBLIC_BASE_URL:https://cos.we-folio.dingchenyong.top}
```

### 7.2 审核任务配置

默认先放大处理量，但保留配置口：

```yaml
work-audit:
  # 是否启用作品审核定时任务。
  enabled: true

  # 作品审核任务调度表达式；默认每分钟第 0 秒执行一次。
  cron: "0 * * * * ?"

  # 当前 job 实例锁标识前缀；默认使用主机名，便于排查是哪台实例 claim 了任务。
  lock-owner-prefix: ${HOSTNAME:local}

  # 单条审核任务锁定秒数；必须大于单次腾讯云远端调用超时时间。
  task-lock-seconds: 300

  # 每轮最多查询多少条未结束的视频审核任务；默认先放大，后续可按积压和耗时调小。
  max-query-video-per-run: 1000

  # 每轮最多提交多少个未审核视频作品到腾讯云异步审核；提交后不等待结果。
  max-submit-video-per-run: 500

  # 每轮最多同步审核多少个未审核图片作品。
  max-audit-image-per-run: 500

  # 单个视频审核任务最多主动查询次数；每分钟查一次时，120 次约等于 2 小时。
  video-query-max-attempts: 120

  # 视频画面审核截帧间隔秒数；当前只做画面审核，不做音频审核。
  video-snapshot-interval-seconds: 60

  # 单个视频最多截帧数量，防止超长视频产生过多审核帧。
  max-video-snapshot-count: 120

  # 腾讯云远端接口调用超时时间，单位秒。
  remote-call-timeout-seconds: 30
```

说明：

- `video-query-max-attempts: 120` 表示单个视频审核任务最多被 job 主动查询 120 次；按每分钟一轮约等于 2 小时。
- `task-lock-seconds` 必须大于单次腾讯云调用超时时间。
- 每轮处理量默认偏大，后续如果积压导致单轮执行过久，可直接调小配置。

## 8. 事务颗粒度

核心原则：

```text
远端调用不放在数据库事务里。
数据库事务只包短小的状态 claim、任务创建、结果落库。
```

### 8.1 为什么远端调用不能包在事务里

- 腾讯云接口耗时不可控，长事务会占用数据库连接。
- 视频任务查询可能遇到网络超时，长事务会扩大锁范围。
- 图片同步审核虽然通常较快，但仍属于外部 IO，不应占用数据库事务。
- 如果事务中调用外部接口成功但事务回滚，会造成外部状态和内部状态不一致。

### 8.2 推荐事务边界

每个作品或每个审核任务最多拆成三个短事务：

```text
事务 A：claim
  - 条件更新作品或任务状态
  - 创建或锁定审核任务
  - 提交事务

远端调用：
  - 调腾讯云图片审核、视频提交或视频查询
  - 不持有数据库事务

事务 B：落库结果
  - 写腾讯云 jobId 或审核结果
  - 更新 task 状态
  - 更新 work.audit_status
  - 清理 locked_by / locked_until
  - 提交事务
```

失败时使用独立事务记录失败：

```text
事务 C：记录错误
  - 写 last_error_message
  - 视频结果查询失败且未超查询次数上限时回到 RUNNING，等待下一轮 job 自动再次查询
  - 提交失败、图片审核调用失败或视频查询次数超限时进入终态 FAILED
  - 清理 locked_by / locked_until
  - 提交事务
```

### 8.3 Claim 条件

作品 claim：

```sql
UPDATE wf_work
SET audit_status = 'AUDITING',
    updated_at = NOW(3),
    version = version + 1
WHERE id = ?
  AND audit_status = 'PENDING'
  AND deleted = 0;
```

只有影响行数为 1 才能继续处理。

视频任务查询 claim：

```sql
UPDATE wf_work_audit_task
SET task_status = 'QUERYING',
    locked_by = ?,
    locked_until = DATE_ADD(NOW(3), INTERVAL ? SECOND),
    query_count = query_count + 1,
    updated_at = NOW(3),
    version = version + 1
WHERE id = ?
  AND media_type = 'VIDEO'
  AND task_status IN ('SUBMITTED', 'RUNNING')
  AND deleted = 0
  AND query_count < ?
  AND (locked_until IS NULL OR locked_until < NOW(3));
```

只有影响行数为 1 才能调用腾讯云查询接口。

### 8.4 异常恢复

如果进程在远端调用后、落库前崩溃：

- 图片审核调用失败后直接落 `FAILED` 并记录失败原因，不依赖 `locked_until` 做自动重试。
- 视频提交任务如果调用成功但未落库 `ci_job_id`，存在重复提交风险。为降低风险，提交前写入唯一任务记录，提交请求中使用可追踪的业务 ID 或请求摘要；如果 SDK/API 支持透传 `JobName` 或类似字段，使用 `workId-taskId` 作为幂等辅助标识。提交接口抛错时直接落 `FAILED` 并记录失败原因，由人工介入判断是否重置后重新提交。
- 视频查询任务重复查询同一个 `ci_job_id` 是幂等可接受的；但每个任务有 `video-query-max-attempts` 上限，超过上限后进入 `FAILED`，等待人工介入。

### 8.5 `FAILED` 运维重置

当前阶段不做管理后台入口。确认需要重新审核时，由运维 SQL 同时处理作品状态和失败任务记录：

- 将 `wf_work.audit_status` 从 `FAILED` 改回 `PENDING`。
- 将对应未删除的 `wf_work_audit_task` 历史失败记录逻辑删除，即 `deleted = id`，保留失败原因和原始请求/响应。
- 不直接把旧任务改回 `PENDING`，避免复用历史错误上下文，也避免重新创建任务时撞 `uk_work_audit_task_media` 唯一索引。

示例 SQL：

```sql
UPDATE wf_work_audit_task
SET deleted = id,
    updated_at = NOW(3),
    version = version + 1
WHERE work_id = ?
  AND task_status = 'FAILED'
  AND deleted = 0;

UPDATE wf_work
SET audit_status = 'PENDING',
    updated_at = NOW(3),
    version = version + 1
WHERE id = ?
  AND audit_status = 'FAILED'
  AND deleted = 0;
```

### 8.6 多实例安全

虽然当前可以只部署一个 job 实例，但数据库 claim 必须按多实例安全设计：

- 同一作品只能从 `PENDING` claim 一次。
- 同一视频查询任务只能被一个实例从 `SUBMITTED/RUNNING` claim 到 `QUERYING`。
- 锁过期后允许其它实例接管。
- 所有状态更新使用条件更新或乐观锁，避免后写覆盖先写。

## 9. 服务拆分

`wefolio-java-runtime`：

- 新增 Flyway migration：`V27__add_work_audit.sql`
- `WorkEntity` 新增 `auditStatus`
- 新增 `WorkAuditStatusDict`
- 如有结构测试，只补充字段、字典和 migration 测试

`wefolio-java-runtime` 本次不新增审核任务 entity、repo、service、job、腾讯云数据万象 client，也不承载审核任务调度逻辑。

`wefolio-java-job`：

- `config`：数据万象/COS 配置、审核任务配置
- `entity`：job 内部使用的 `WorkAuditWorkEntity`、`WorkAuditTaskEntity`
- `repo`：job 内部使用的 `WorkAuditWorkRepository`、`WorkAuditTaskRepository`
- `dict`：审核状态、任务状态、审核结果、媒体类型
- `service`：`WorkAuditService`
- `service`：`TencentCiAuditClient`
- `task`：`WorkAuditJob`

job 内部 entity 和 repo 说明：

- `WorkAuditWorkEntity` 映射 `wf_work`，只保留审核任务需要的字段，例如 `id`、`userId`、`mediaType`、`mediaObjectKey`、`mediaSha256`、`durationMs`、`auditStatus`、`deleted`、`version`。
- `WorkAuditTaskEntity` 映射 `wf_work_audit_task`，覆盖审核任务完整字段。
- `WorkAuditWorkRepository` 封装扫描待审核作品、claim 作品、更新作品审核状态等 SQL。
- `WorkAuditTaskRepository` 封装创建审核任务、claim 视频查询任务、写入腾讯云结果、记录失败原因、逻辑删除失败任务等 SQL。
- repo 底层可以使用 MyBatis-Plus Mapper 或 XML SQL，但对 service 只暴露明确的审核语义方法，不直接把通用 Mapper 传到业务编排层。
- job 工程不依赖 runtime 工程的 `WorkEntity`、`WorkEntityMapper` 或 `WorkAuditStatusDict`。如果 job 侧需要状态常量，在 job 内定义同值内部 dict，和数据库枚举值保持一致。

`TencentCiAuditClient` 只负责远端接口：

- `submitImageAudit(objectKey)`
- `submitVideoAudit(objectKey, snapshotIntervalSeconds, snapshotCount)`
- `queryVideoAudit(ciJobId)`

`WorkAuditService` 负责编排：

- `queryPendingVideoResults(limit)`
- `submitPendingVideoAudits(limit)`
- `auditPendingImages(limit)`
- `queryAllPendingVideoResults(limit)`

`WorkAuditJob` 只负责调度和互斥，不承载业务细节。

## 10. 测试策略

### 10.1 runtime 测试

- migration 测试：验证 `wf_work.audit_status` 字段、约束、索引存在。
- 实体结构测试：验证 `WorkEntity.auditStatus` 存在。
- 字典测试：验证审核状态字典值完整。

### 10.2 job 单元测试

- 互斥测试：上一轮未结束时，本轮跳过。
- repo 测试：验证 `WorkAuditWorkRepository` 和 `WorkAuditTaskRepository` 的扫描、claim、状态更新、失败记录 SQL 语义。
- 视频历史查询测试：`SUBMITTED/RUNNING -> QUERYING -> SUCCESS/RUNNING/FAILED`。
- 视频提交测试：`PENDING VIDEO -> AUDITING + SUBMITTED`，不等待结果。
- 图片同步测试：`PENDING IMAGE -> PASSED/REJECTED/REVIEW_REQUIRED`。
- 事务边界测试：远端 client 调用发生在 claim 事务提交之后。
- 提交失败测试：腾讯云提交视频审核失败时记录失败原因，任务和作品进入 `FAILED`，不自动重试。
- 图片调用失败测试：腾讯云图片审核调用失败时记录失败原因，任务和作品进入 `FAILED`，不自动重试。
- 查询失败测试：视频结果查询异常后记录失败原因，不在本轮立即重试；未超查询次数上限时回到 `RUNNING`，超限后进入 `FAILED`。
- 查询次数上限测试：视频任务每次被 job 主动查询时 `query_count` 递增，达到 `video-query-max-attempts` 后不再调用腾讯云并进入 `FAILED`。
- 截帧数量测试：4 分半视频得到 5 帧。

### 10.3 集成验证

- `wefolio-java-runtime` 执行 `mvn test`。
- `wefolio-java-job` 执行 `mvn test`。
- 依赖检查：`wefolio-java-job` 不引入 Flyway。

## 11. 已确认事项

当前方案已确认：

- 视频只做画面审核。
- 视频不回调，主动查询。
- 每轮先查历史视频结果，再提交新视频，再同步图片，最后再查视频结果。
- 默认处理量先放大，保留配置。
- 当前阶段不建设 `FAILED` 状态的管理重置入口，人工确认后先通过 SQL 运维重置为 `PENDING`。
- 腾讯云审核结果中的疑似违规统一落 `REVIEW_REQUIRED`，不直接按违规处理。
