# 访问记录查档与预留信息明细设计

## 背景

维护端“访问记录”页目前已有“累计访问次数、今日访问、查询档期”三个统计指标。访客侧查询档期是一个专门按钮触发的提交动作，已有逻辑会更新访问汇总并写入访问事件。新需求是在保留原逻辑的前提下，将按钮查询档期的业务信息单独沉淀到一张新表，并让维护端可以从统计卡片进入分页明细弹层。

本设计只覆盖个人作品集的访客查档和预留信息展示。团队作品集如后续接入，不计入本次维护端个人访问记录页的“查询档期”统计。

## 目标

- 访客点击“查询档期”按钮并提交成功时，新增一条结构化查档记录。
- 原访问汇总、访问事件、访问明细摘要逻辑保留。
- 维护端“访问记录”页的“查询档期”计数改为按新表统计个人作品集查档记录。
- 维护端“访问记录”页新增“预留信息”计数，来源为现有 `wf_contact_lead`。
- 点击“查询档期”或“预留信息”统计卡片后，弹层按创建时间倒序分页展示明细，并支持滚动触底加载下一页。

## 非目标

- 不记录月历选项加载、日期切换、档位选择、作品集打开等非按钮查询动作。
- 不新增预留信息表，预留信息继续使用已有 `wf_contact_lead`。
- 不在查档新表中保存 `portfolio_share_code_snapshot`、`portfolio_revision`、`trigger_type`、`idempotency_key`、`component_key`。
- 不返回手机号、微信号密文明文。

## 当前相关逻辑

访客查档按钮提交接口：

```text
POST /api/visitor/portfolios/{shareCode}/schedule-query
```

现有请求字段来自 `PortfolioScheduleQueryRequest`：

| 字段 | 含义 |
|---|---|
| `visitorKey` | 匿名访客摘要 |
| `componentKey` | 档期查询组件实例键，仅用于服务端校验组件存在 |
| `queriedDate` | 查询日期 |
| `slotDefinitionId` | 查询档位定义 ID |
| `idempotencyKey` | 事件幂等键，仅用于现有访问事件去重 |

现有响应字段来自 `PortfolioScheduleQueryResponse`：

| 字段 | 含义 |
|---|---|
| `queriedDate` | 查询日期 |
| `slotDefinitionId` | 档位定义 ID |
| `slotName` | 档位名称 |
| `startTime` | 开始时间 |
| `endTime` | 结束时间 |
| `color` | 展示颜色 |
| `status` | 查询结果状态编码 |
| `statusText` | 查询结果状态文案 |
| `available` | 是否可约 |
| `message` | 结果提示 |

现有保留逻辑：

- `wf_visit_record.schedule_query_count` 继续累加。
- `wf_visit_event` 继续写入 `SCHEDULE_QUERIED` 事件。
- 访问明细摘要继续使用访问汇总字段生成。

## 新增表模型

表名：`wf_schedule_query_record`

含义：一行代表访客在个人作品集里点击一次“查询档期”按钮并查询成功后的业务快照。

### 字段设计

| 字段 | 类型建议 | 是否必填 | 来源 |
|---|---:|---:|---|
| `id` | BIGINT UNSIGNED | 是 | 自增主键 |
| `portfolio_id` | BIGINT UNSIGNED | 是 | `PortfolioEntity.id` |
| `portfolio_type` | VARCHAR(16) ascii_bin | 是 | 当前只写 `PERSONAL`，来源为 `PortfolioTypeDict.PERSONAL` |
| `portfolio_title_snapshot` | VARCHAR(100) | 是 | 从作品集发布配置解析标题；解析失败使用“个人作品集” |
| `visit_record_id` | BIGINT UNSIGNED | 是 | 本次访客对应的 `wf_visit_record.id` |
| `visitor_id` | BIGINT UNSIGNED | 否 | `VisitRecordEntity.visitorId`，未建立全局访客资料时为空 |
| `visitor_key` | CHAR(64) | 是 | 优先使用访问记录里的 `visitorKey`，兜底使用请求 `visitorKey` |
| `owner_type` | VARCHAR(16) ascii_bin | 是 | `PortfolioEntity.ownerType`，个人作品集为 `USER` |
| `owner_id` | BIGINT UNSIGNED | 是 | `PortfolioEntity.ownerId` |
| `source_type` | VARCHAR(32) ascii_bin | 是 | 优先使用 `VisitRecordEntity.sourceType`，没有则 `UNKNOWN` |
| `display_mode` | VARCHAR(32) ascii_bin | 是 | 查档组件配置 `displayMode`，没有则 `MODAL_CALENDAR` |
| `queried_date` | DATE | 是 | `PortfolioScheduleQueryRequest.queriedDate` |
| `slot_definition_id` | BIGINT UNSIGNED | 是 | `PortfolioScheduleQueryRequest.slotDefinitionId` |
| `slot_name_snapshot` | VARCHAR(50) | 是 | `PortfolioScheduleQueryResponse.slotName` |
| `start_time_snapshot` | TIME | 是 | `PortfolioScheduleQueryResponse.startTime` 转换为 `LocalTime` |
| `end_time_snapshot` | TIME | 是 | `PortfolioScheduleQueryResponse.endTime` 转换为 `LocalTime` |
| `color_snapshot` | VARCHAR(16) | 否 | `PortfolioScheduleQueryResponse.color` |
| `result_status` | VARCHAR(32) ascii_bin | 是 | `PortfolioScheduleQueryResponse.status` |
| `result_status_text` | VARCHAR(32) | 是 | `PortfolioScheduleQueryResponse.statusText` |
| `available` | TINYINT UNSIGNED | 是 | `PortfolioScheduleQueryResponse.available`，1 表示可约 |
| `result_message` | VARCHAR(100) | 是 | `PortfolioScheduleQueryResponse.message` |
| `queried_at` | DATETIME(3) | 是 | 服务端按钮查询成功时间 |
| `created_at` | DATETIME(3) | 是 | 公共创建时间 |
| `updated_at` | DATETIME(3) | 是 | 公共更新时间 |
| `deleted` | BIGINT UNSIGNED | 是 | 逻辑删除，0 未删除，删除时为主键 ID |
| `version` | INT UNSIGNED | 是 | 乐观锁版本号 |

### DDL 草案

```sql
CREATE TABLE `wf_schedule_query_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `portfolio_id` BIGINT UNSIGNED NOT NULL COMMENT '来源作品集ID',
  `portfolio_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'PERSONAL个人 TEAM团队',
  `portfolio_title_snapshot` VARCHAR(100) NOT NULL COMMENT '来源作品集标题快照',
  `visit_record_id` BIGINT UNSIGNED NOT NULL COMMENT '访问汇总记录ID',
  `visitor_id` BIGINT UNSIGNED NULL COMMENT '全局访客ID',
  `visitor_key` CHAR(64) NOT NULL COMMENT '匿名访客摘要',
  `owner_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'USER用户 TEAM团队',
  `owner_id` BIGINT UNSIGNED NOT NULL COMMENT '记录归属用户或团队ID',
  `source_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'UNKNOWN'
    COMMENT 'WECHAT_SHARE_CARD QR_CODE TEAM_PORTFOLIO PERSONAL_PORTFOLIO UNKNOWN',
  `display_mode` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'MODAL_CALENDAR' COMMENT '查档组件展示方式',
  `queried_date` DATE NOT NULL COMMENT '查询日期',
  `slot_definition_id` BIGINT UNSIGNED NOT NULL COMMENT '档位定义ID',
  `slot_name_snapshot` VARCHAR(50) NOT NULL COMMENT '档位名称快照',
  `start_time_snapshot` TIME NOT NULL COMMENT '开始时间快照',
  `end_time_snapshot` TIME NOT NULL COMMENT '结束时间快照',
  `color_snapshot` VARCHAR(16) NULL COMMENT '展示颜色快照',
  `result_status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT '查询结果状态',
  `result_status_text` VARCHAR(32) NOT NULL COMMENT '查询结果状态文案',
  `available` TINYINT UNSIGNED NOT NULL COMMENT '是否可约：1是 0否',
  `result_message` VARCHAR(100) NOT NULL COMMENT '查询结果提示',
  `queried_at` DATETIME(3) NOT NULL COMMENT '查询成功时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  KEY `idx_schedule_query_owner_portfolio_time`
    (`owner_type`, `owner_id`, `portfolio_type`, `queried_at`, `id`),
  KEY `idx_schedule_query_visit_time` (`visit_record_id`, `queried_at`),
  KEY `idx_schedule_query_portfolio_time` (`portfolio_id`, `queried_at`),
  CONSTRAINT `chk_schedule_query_portfolio_type`
    CHECK (`portfolio_type` IN ('PERSONAL', 'TEAM')),
  CONSTRAINT `chk_schedule_query_owner_type`
    CHECK (`owner_type` IN ('USER', 'TEAM')),
  CONSTRAINT `chk_schedule_query_source_type`
    CHECK (`source_type` IN (
      'WECHAT_SHARE_CARD', 'QR_CODE', 'TEAM_PORTFOLIO',
      'PERSONAL_PORTFOLIO', 'UNKNOWN'
    )),
  CONSTRAINT `chk_schedule_query_available`
    CHECK (`available` IN (0, 1)),
  CONSTRAINT `chk_schedule_query_time`
    CHECK (`start_time_snapshot` < `end_time_snapshot`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='访客查询档期记录';
```

## 写入流程

写入入口只放在：

```text
POST /api/visitor/portfolios/{shareCode}/schedule-query
```

服务流程：

1. 查询并校验作品集已发布，且归属为个人用户。
2. 解析作品集配置，校验请求中的 `componentKey` 对应启用的查档组件。
3. 校验 `queriedDate` 与 `slotDefinitionId`。
4. 计算查询结果，生成 `PortfolioScheduleQueryResponse`。
5. 使用请求 `idempotencyKey` 查询 `wf_visit_event` 是否已存在；已存在时直接返回查询结果，不再累加汇总、不再写事件、不再写新表。
6. 调用现有访问服务，继续累加 `wf_visit_record.schedule_query_count` 并写入 `wf_visit_event.SCHEDULE_QUERIED`。
7. 使用同一条访问汇总记录和查询结果快照写入 `wf_schedule_query_record`。
8. 返回查询结果给访客。

第 5 到第 7 步需要在同一个事务中完成，保证汇总、事件、新表记录一致。新表不保存 `idempotency_key`，去重能力由现有 `wf_visit_event.uk_visit_event_idempotency` 和写入前查询承担。

## 维护端统计口径

访问记录首页接口保持：

```text
GET /api/mine/visits
```

`summary` 增加 `contactLeadCount`，并调整 `scheduleQueryCount` 口径。

| 指标 | 数据来源 |
|---|---|
| `totalVisitCount` | 当前维护者名下 `wf_visit_record.visit_count` 汇总，保持现状 |
| `todayVisitCount` | 当前维护者今日 `wf_visit_event.PORTFOLIO_OPENED` 数量，保持现状 |
| `scheduleQueryCount` | `wf_schedule_query_record` 按 owner 统计，仅个人作品集 |
| `contactLeadCount` | `wf_contact_lead` 按 owner 统计 |

查档计数过滤条件：

```sql
WHERE owner_type = 'USER'
  AND owner_id = 当前维护者ID
  AND portfolio_type = 'PERSONAL'
  AND deleted = 0
```

预留信息计数过滤条件：

```sql
WHERE owner_type = 'USER'
  AND owner_id = 当前维护者ID
  AND deleted = 0
```

## 维护端分页接口

### 查询档期明细

```text
GET /api/mine/visits/schedule-queries?pageNo=1&pageSize=20
```

过滤条件：

```sql
WHERE owner_type = 'USER'
  AND owner_id = 当前维护者ID
  AND portfolio_type = 'PERSONAL'
  AND deleted = 0
ORDER BY queried_at DESC, id DESC
```

响应建议：

| 字段 | 来源 |
|---|---|
| `id` | `wf_schedule_query_record.id` |
| `visitorLabel` | 有 `visitor_id` 时取 `wf_visitor.nickname`，否则用 `visitor_key` 短码 |
| `visitorAvatarUrl` | 有 `visitor_id` 时取 `wf_visitor.avatar_url` |
| `visitorInitial` | 访客短码首字符 |
| `portfolioTitle` | `portfolio_title_snapshot` |
| `queriedDateText` | `queried_date` 格式化 |
| `slotText` | `slot_name_snapshot + start_time_snapshot + end_time_snapshot` |
| `resultStatus` | `result_status` |
| `resultStatusText` | `result_status_text` |
| `available` | `available` |
| `resultMessage` | `result_message` |
| `sourceText` | `source_type` 转展示文案 |
| `createdTimeText` | `queried_at` 格式化 |

分页响应统一包含：

```json
{
  "pageNo": 1,
  "pageSize": 20,
  "hasMore": true,
  "items": []
}
```

### 预留信息明细

```text
GET /api/mine/visits/contact-leads?pageNo=1&pageSize=20
```

过滤条件：

```sql
WHERE owner_type = 'USER'
  AND owner_id = 当前维护者ID
  AND deleted = 0
ORDER BY submitted_at DESC, id DESC
```

响应建议：

| 字段 | 来源 |
|---|---|
| `id` | `wf_contact_lead.id` |
| `contactName` | `contact_name` |
| `phoneLast4` | `phone_last4` |
| `wechatMaskHint` | `wechat_mask_hint` |
| `desiredSchedule` | `desired_schedule` |
| `needs` | `needs` |
| `portfolioTitle` | `portfolio_title_snapshot` |
| `sourceText` | `source_type` 转展示文案 |
| `followStatus` | `follow_status` |
| `followStatusText` | 跟进状态文案 |
| `submittedTimeText` | `submitted_at` 格式化 |

安全要求：

- 不返回 `phone_ciphertext`。
- 不返回 `wechat_ciphertext`。
- 手机仅展示尾号。
- 微信仅展示脱敏提示。

## 小程序交互

维护端页面：`projects/miniapp/pages/visits/`

页面调整：

- 顶部统计从 3 项调整为 4 项。
- 建议使用 2 列布局，避免 4 项在窄屏横向拥挤。
- “查询档期”统计卡片可点击，打开查询档期明细弹层。
- “预留信息”统计卡片可点击，打开预留信息明细弹层。
- 访问明细列表和现有访问事件弹层保留。

弹层行为：

- 首次打开加载第一页。
- `scroll-view` 触底调用下一页。
- 加载中、加载更多、空状态、失败重试状态与现有访问事件弹层保持一致。
- 弹层关闭时取消当前请求上下文，避免过期请求覆盖新弹层数据。

查询档期列表展示建议：

- 访客头像或匿名首字。
- 访客昵称或“微信访客 XXXX”。
- 查询日期与档位时间。
- 查询结果文案，例如“档期空闲”或“该档期已约”。
- 来源作品集标题。
- 查询时间。

预留信息列表展示建议：

- 联系人。
- 手机尾号和微信脱敏提示。
- 意向档期。
- 需求描述。
- 来源作品集标题。
- 提交时间。

## 后端改动范围

新增：

- `V25__schedule_query_record.sql`
- `ScheduleQueryRecordEntity`
- `ScheduleQueryRecordEntityMapper`
- 查询档期明细响应 DTO
- 预留信息明细响应 DTO

调整：

- `VisitorPortfolioService.submitScheduleQuery(...)`：按钮查询成功后写入新表。
- `PortfolioVisitService.recordScheduleQuery(...)`：返回或暴露本次访问汇总记录，便于新表关联 `visit_record_id`。
- `MineVisitService.getVisitRecords()`：`scheduleQueryCount` 改为新表计数，新增 `contactLeadCount`。
- `MineVisitService` 新增两个分页查询方法。
- `MineController` 新增两个分页接口。

## 测试计划

后端单测：

- 按钮查档成功时，访问汇总、访问事件、新表记录同时写入。
- 重复 `idempotencyKey` 重试时，不重复累加、不重复写新表。
- `scheduleQueryCount` 只统计当前维护者、`portfolio_type = PERSONAL` 的查档记录。
- `contactLeadCount` 只统计当前维护者的预留信息。
- 查询档期分页按 `queried_at desc, id desc` 返回。
- 预留信息分页按 `submitted_at desc, id desc` 返回，并不包含密文字段。

小程序 Node 测试：

- 访问记录统计归一化支持第四个指标。
- 点击“查询档期”卡片会请求查档分页接口。
- 点击“预留信息”卡片会请求预留信息分页接口。
- 两个弹层都支持滚动触底追加下一页。
- 空状态、错误状态和加载更多状态可正确渲染。

验证命令：

```bash
cd projects/java/wefolio-java-runtime
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn test
```

```bash
cd projects/miniapp
node --test tests/*.test.js
```

## 历史数据处理

默认不回填历史 `wf_visit_event.SCHEDULE_QUERIED` 到新表。新表上线后开始记录结构化查档明细，维护端“查询档期”统计与弹层列表使用同一张表，保证数字和列表一致。

如果产品需要延续历史数量，可以通过一次性迁移从 `wf_visit_event` 回填新表；但历史事件未必具备完整档位快照，回填数据展示质量会弱于上线后的新记录。
