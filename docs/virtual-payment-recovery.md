# 微信虚拟支付余额补查与人工恢复

本文用于排查充值漏结算、退款后的余额同步，以及微信已返回成功、本地尚未完成结算的扣币任务和赠送订单。以下 SQL 均为运维只读查询，不是 Flyway 脚本，不涉及表结构或数据变更；示例不包含真实环境地址和密钥。

## 未入账充值核对与退款通知处理

- job 复用既有虚拟支付扣币调度轮次，按订单 ID 调用 runtime 的内部核对接口；job 不直接请求微信，不判断支付成功，也不修改积分。扫描包含微信虚拟支付渠道的 `PENDING_PAYMENT`、`CLOSED` 订单，以及 `paid_fee > 0`、已确认收款的 `PAYMENT_FAILED` 订单；这些候选都要求 `next_query_at` 为空或已到期、订单未删除且存在 `AVAILABLE` 维护者会话。`PAID` 订单退出定时核对，即使历史数据仍留有到期的 `next_query_at` 也不会被选中；runtime 在加锁前后均检查状态，跳过扫描后已经入账的候选。
- `PENDING_PAYMENT` 恢复漏结算充值，`CLOSED` 保留迟到支付的核对机会。已保存成功收款事实、尚未本地结算的订单，在公开同步按兼容契约进入 `PAYMENT_FAILED` 后仍能后台补偿；`paid_fee` 为空或零的明确失败单不进入补偿候选。后台恢复成功进入 `PAID` 后清除本次核对计划，不再安排每天查退款。
- 已入账订单的退款由微信退款通知触发，沿用幂等退款记账及余额同步。退款通知缺失时按具体订单排查，不再通过扫描所有历史已入账订单兜底。已退款订单不进入充值核对候选；账户退款后的失效余额仍由独立的账户恢复候选补查，仅处理已有退款事实的账户。
- runtime 按用户加锁并重读状态和核对时间，同一订单的通知、公开同步及后台核对复用幂等资金流程。已验证的微信成功事实先独立持久化，再完成余额同步和本地结算；本地过期关单会排除 `paid_fee > 0` 的已确认支付订单。若关单先发生，原订单仍可从 `CLOSED` 恢复，不需新建充值订单。
- 待确认和暂时失败通过 `next_query_at`、`query_retry_count` 按 1、2、4、8、16、32、60 分钟退避，后续最多等待 60 分钟；已核对确认的 `CLOSED` 仍每 24 小时继续核对。没有有效会话时保留业务状态并等待会话恢复，runtime 已收到的此类核对会延后 5 分钟。上述间隔是下次允许执行的时间，实际执行还受开关、批量及扫描进度影响。
- job 不会为缺少 `AVAILABLE` 会话的充值订单主动请求微信；`AVAILABLE` 也不保证微信仍接受该会话。需要维护者重新登录或刷新微信会话后才能继续依赖 session_key 的核对，不能承诺无人登录时也会完成补偿。

内部单订单核对接口沿用现有后台积分密钥和 HTTPS，不新增公开客户端要求：

```http
POST <RUNTIME_BASE_URL>/api/admin/points/virtual-payment/recharge-orders/<ORDER_ID>/reconciliations
X-Admin-Point-Secret: <ADMIN_POINT_SECRET>
```

请求无 body，响应沿用 `data.targetId`、`data.taskType`、`data.outcome`。`SUCCEEDED` 表示本次核对完成；`RETRY_WAIT` 和 `WAITING_SESSION` 表示已保存下次核对计划，均不表示资金已结算。`SKIPPED_DISABLED`、`SKIPPED_NOT_DUE`、`SKIPPED_TERMINAL` 表示本次跳过。不要通过删除成功事实或重建订单进行恢复。

## 调度额度、失败隔离和发布顺序

- `VIRTUAL_PAYMENT_DISPATCH_ENABLED` 默认仍为 `false`，runtime 的 `WECHAT_VIRTUAL_PAYMENT_ENABLED` 默认也仍为 `false`。本次代码发布不会自动开启任何开关；关闭期间没有这条 job 自动核对路径。维护既有环境的启停决策，不因部署代码自行改开关。
- 扣费轮次固定延迟仍默认 30 秒，由 `VIRTUAL_PAYMENT_DEBIT_DISPATCH_INTERVAL` 控制。充值核对、缺失扣费任务补建、退款余额恢复、已有活动扣费任务分别使用 `VIRTUAL_PAYMENT_DISPATCH_BATCH_SIZE` 上限，默认各 100 条；赠送调度沿用原设置。工作线程数和单次请求超时也沿用既有配置。
- 充值订单和两类账户恢复各有独立递增 ID 游标，到尾部后从头轮转；请求失败也推进扫描位置。退款账户不会占用缺失扣费任务的额度，较小 ID 的失败记录也不会永久挡住较大 ID。游标保存在 job 进程内，重启后从头扫描；多实例可能发现同一候选，最终状态和幂等保护仍由 runtime 负责。同轮两类账户恢复命中同一用户时只发送一次请求。
- 充值的远端结果退避由 runtime 持久化；job 的 HTTP 失败不在同一轮重试。账户恢复沿用固定扫描间隔和轮转，未新增账户级持久退避。不要将固定扫描间隔误认为每条候选一定每 30 秒执行一次。
- 新增恢复查询失败时记录 `VIRTUAL_PAYMENT_RECOVERY_SCAN_FAILED` ERROR 事件，含任务类型和异常类型，并继续其他恢复类别及原有活动扣费分发。该隔离用于避免迁移缺列等异常阻断旧功能，不能替代正确的发布顺序。
- 日志分别汇总 `充值订单核对`、`扣币任务恢复`、`退款余额恢复` 的候选、请求、处理、跳过、失败数。`processedCount` 包含已保存退避或等待会话的核对结果，不能用作成功入账数量；HTTP 成功也不等于资金恢复完成，应复查订单和账户。

分批发布按以下顺序执行：

1. 在正常发布门禁下暂停或保持关闭 job 虚拟支付分发，先由 runtime Flyway 执行 `V58__add_recharge_order_reconciliation.sql`。该迁移仅新增 `wf_recharge_order.next_query_at`、`query_retry_count` 及核对索引，存量订单可继续由旧版代码读取；job 不运行 Flyway。
2. 发布所有 runtime 实例，确认新内部核对接口和成功事实、退款恢复逻辑均已可用，内部密钥配置一致。避免 job 请求仍路由到不支持新端点的旧实例。
3. 发布新版 job，确认 HTTPS runtime 地址、内部密钥、V58 字段及开关状态，再按目标环境原有授权启用或恢复分发。检查三类恢复汇总与扫描失败事件，并复查少量历史订单的 `status`、`paid_fee`、`paid_at`、`next_query_at` 和 `query_retry_count`。
4. 回退应用时优先关闭新增分发，不删除新增字段或回滚已执行 migration；保留订单号和已经记录的微信成功事实。

## 成功后补查规则

- `last_error_code` 为 `SUCCESS` 或 `DUPLICATE_SUCCESS` 时，表示已经持久化微信成功事实。后续执行只补查余额并完成本地结算，不再发起扣币或赠送。
- 暂时失败按 1、2、4、8、16、32、60 分钟退避，之后每次最多等待 60 分钟；补查不增加普通 `retry_count`。实际执行时间还受定时调度、租约和功能开关影响。
- 配置错误或永久错误进入 `FAILED`，保留成功事实；扣币同时保留 `active_flag = 1` 的活动槽位，防止同一笔待扣被新的任务再次扣除。先修复配置、身份或会话问题，再通过现有人工重试接口恢复。
- 扣币缺少有效会话时进入 `WAITING_SESSION`，维护者会话更新事件可以唤醒；赠送继续使用 `RETRY_WAIT`，不新增等待状态。会话更新也可以提前唤醒未被有效租约占用的已成功赠送补查。
- `last_failed_at` 是最近一次失败时间；`next_execute_at` 是下次执行时间，两者均不表示首次进入补查的时间。
- 转入人工处理时输出 `WECHAT_BALANCE_CONFIRMATION_MANUAL_REQUIRED` ERROR 事件，包含原任务或订单 ID、成功事实及失败分类。暂时失败继续退避，不反复输出这条人工处理事件。
- 普通扣币应答与完整补查余额不一致时输出 `WECHAT_DEBIT_BALANCE_SNAPSHOT_CHANGED` WARN 事件。充值或退款也会造成差异，因此仍使用同一次完整补查快照，不取两次余额的最小值拼接结算。本轮只增加可检索日志，未配置外部告警通知。

## 查询创建超过 1 小时仍未结算的记录

查询按 `created_at` 判断记录年龄，并优先展示最早创建的 100 条。命中表示“创建超过 1 小时仍未结算”，不能据此认定已经连续补查 1 小时。`last_error_message` 为最近一次脱敏失败原因；普通 `retry_count` 不能用于统计余额补查次数。

### 扣币任务

```sql
SELECT user_id,
       id AS task_id,
       task_no,
       status,
       last_error_code,
       retry_count,
       created_at,
       last_failed_at,
       next_execute_at,
       last_error_message AS failure_reason
FROM wf_point_debit_task
WHERE deleted = 0
  AND active_flag = 1
  AND last_error_code IN ('SUCCESS', 'DUPLICATE_SUCCESS')
  AND status IN ('WAITING', 'WAITING_SESSION', 'RETRY_WAIT', 'RUNNING', 'FAILED')
  AND created_at <= CURRENT_TIMESTAMP(3) - INTERVAL 1 HOUR
ORDER BY created_at ASC, id ASC
LIMIT 100;
```

### 赠送订单

```sql
SELECT user_id,
       id AS order_id,
       order_no,
       status,
       last_error_code,
       retry_count,
       created_at,
       last_failed_at,
       next_execute_at,
       last_error_message AS failure_reason
FROM wf_point_gift_order
WHERE deleted = 0
  AND last_error_code IN ('SUCCESS', 'DUPLICATE_SUCCESS')
  AND status IN ('READY', 'RETRY_WAIT', 'FAILED')
  AND created_at <= CURRENT_TIMESTAMP(3) - INTERVAL 1 HOUR
ORDER BY created_at ASC, id ASC
LIMIT 100;
```

## 人工恢复

1. 记录查询结果中的任务 ID 或订单号及失败原因，先排查并修复对应配置、用户身份或会话问题。需要新会话时，让维护者重新登录；检查运行时服务、调度服务及虚拟支付开关是否符合该环境的预期。
2. 对仍处于 `FAILED` 的原记录调用下列重试接口。扣币接口使用 `task_id`，赠送接口使用 `order_no`，均无请求体。占位符须由获授权的运维人员在目标环境替换，不要把真实密钥写入本文档或提交到仓库。
3. 接口响应 `data = true` 仅表示该失败记录已恢复为可调度状态，不代表余额已经结算；`data = false` 表示本次没有重置记录，应先复查当前状态。人工重试保留成功标记，因此后续仍只补查余额，不重复扣币或赠送。等待调度后复查原记录和对应账户。

扣币任务：

```http
POST <RUNTIME_BASE_URL>/api/admin/points/debit-tasks/<TASK_ID>/retry
X-Admin-Point-Secret: <ADMIN_POINT_SECRET>
```

赠送订单：

```http
POST <RUNTIME_BASE_URL>/api/admin/points/gift-orders/<ORDER_NO>/retry
X-Admin-Point-Secret: <ADMIN_POINT_SECRET>
```

`WAITING_SESSION` 等待会话恢复；`WAITING`、`READY`、`RETRY_WAIT` 等待自动调度；`RUNNING` 由当前执行器或租约接管机制处理。人工重试接口仅重置 `FAILED`，不要通过清除成功标记、新建同笔任务或直接改表来绕过这些状态。

## 本轮保留的对象清理限制

手动上传的封面在作品保存失败后会保留，供原任务重试。当前仓库没有针对这类封面孤儿对象的过期清理兜底；任务过期仅更新任务状态。按照本轮收敛范围的要求，不新增清理任务。本文未核查云端 COS 生命周期配置。
