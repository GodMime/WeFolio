# 微信虚拟支付余额补查与人工恢复

本文用于排查微信已返回成功、本地尚未完成结算的扣币任务和赠送订单。以下 SQL 均为运维只读查询，不是 Flyway 脚本，不涉及表结构或数据变更；示例不包含真实环境地址和密钥。

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
