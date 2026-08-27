# 技术信息与服务配置台账

版本：v0.5
日期：2026-08-26
维护人：dingchenyong

## 1. 文档目的

本文档用于集中记录项目运行所需的技术资源与服务配置，包括服务器、对象存储、大模型服务、数据库、缓存、域名、证书、环境变量、备份与告警等信息。

注意：生产环境密钥、数据库密码、AccessKey Secret、API Key 等敏感信息不建议在 Git 仓库中明文保存。建议只记录脱敏值、密钥用途、权限范围和密钥存放位置，例如密钥管理系统、云厂商 Secret Manager、团队密码管理器或部署平台环境变量名称。

## 2. 维护规范

- 每次新增、变更、下线技术资源时，同步更新本文档。
- 所有敏感字段使用脱敏格式，例如 `sk-****abcd`、`AKIA****1234`。
- 密钥轮换、数据库迁移、服务器下线等高风险操作需记录在「变更记录」中。
- 不同环境的信息分开维护，避免测试环境误连生产资源。

## 3. 环境概览

| 环境 | 用途 | 访问域名 | 部署方式 | 负责人 | 备注 |
| --- | --- | --- | --- | --- | --- |
| 开发环境 | 本地开发与联调 | 待补充 | 待补充 | 待补充 | 待补充 |
| 测试环境 | 功能测试与验收 | 待补充 | 待补充 | 待补充 | 待补充 |
| 生产环境 | 线上用户访问 | `api.we-folio.dingchenyong.top` | Runtime 双节点滚动发布；Job 单节点发布；SCP 上传 JAR + systemctl | 待补充 | Nginx 位于老节点；Runtime 在两台服务器的 8090 端口运行，Job 在老节点的 8091 端口运行 |

## 4. 服务器信息

| 环境 | 云厂商 | 地域/可用区 | 实例名称 | 公网 IP/域名 | 内网 IP | 系统 | 登录方式 | 开放端口 | 部署目录 | 日志目录 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 生产环境 | 腾讯云 | 待补充 | 老节点（Runtime / Nginx / Job） | `49.235.146.161` | 待确认；Nginx 通过 `127.0.0.1:8090` 访问本机 Runtime | CentOS | `root` SSH 密钥登录，使用本机 `~/.ssh/id_ed25519` | 22 / 80 / 443；本机服务 8090（Runtime）/ 8091（Job） | Runtime：`/root/java`；Job：`/root/java/job` | `LOG_PATH`，未配置时默认为 `./logs` | SSH 密钥登录已验证；`PasswordAuthentication no` 已生效；运行 `wefolio.service`、`wefolio-job.service`；Nginx 配置为 `/etc/nginx/conf.d/myapp.conf` |
| 生产环境 | 待确认 | 待补充 | 新节点（Runtime） | `124.222.148.233` | `10.0.4.7` | 待补充 | `root` SSH 密钥登录，部署脚本使用 BatchMode | 22；内网 8090（供老节点 Nginx 访问 Runtime） | `/root/java` | `LOG_PATH`，未配置时默认为 `./logs` | 仅运行 `wefolio.service`，不部署 Nginx 和 Job；Runtime JAR 与老节点保持相同 SHA-256 |

### 4.1 当前生产部署拓扑

以下信息以 `projects/java/wefolio-java-runtime/deploy.sh` 和 `projects/java/wefolio-java-job/deploy.sh` 的当前配置为准：

| 组件 | 老节点 `49.235.146.161` | 新节点 `124.222.148.233` | 服务与路径 | 访问/调度方式 |
| --- | --- | --- | --- | --- |
| Runtime | 已部署 | 已部署 | `wefolio.service`；`/root/java/wefolio-java-runtime.jar` | Nginx upstream 使用 `127.0.0.1:8090` 与 `10.0.4.7:8090`，正常状态为默认 round-robin |
| Job | 已部署 | 未部署 | `wefolio-job.service`；`/root/java/job/wefolio-java-job.jar` | 老节点单实例，服务端口 8091，Web 接口前缀为 `/job-api` |
| Nginx | 已部署 | 未部署 | `nginx`；`/etc/nginx/conf.d/myapp.conf` | 公网流量入口；Runtime 发布时在 `new_only`、`old_only`、`round_robin` 三种路由状态间切换 |

Runtime 发布脚本在本地只构建一次 JAR，计算 SHA-256 后把同一制品依次发布到两个节点。单节点发布时先上传为 `.uploading`，校验通过后将原 JAR 保存为固定的 `.backup`，再原子替换正式 JAR、重启 `wefolio.service` 并执行健康检查。任一节点发布失败时脚本会停止后续步骤，并保持当时的摘流状态，需人工排障后重新发布。

### 4.2 问题反馈 Runtime 配置与出网检查

问题反馈飞书通知依赖两项敏感环境变量。老节点 `49.235.146.161` 与新节点 `124.222.148.233` 的 `wefolio.service` 必须同时配置，不能只更新其中一个 Runtime 节点。状态接口无认证；卡片中的 `curl` 默认使用生产 API 域名，可通过非敏感变量 `FEEDBACK_STATUS_API_BASE_URL` 覆盖。

1. 分别在两台节点执行 `systemctl cat wefolio.service`，确认当前单元使用的 `EnvironmentFile` 或 drop-in；沿用现有注入位置，不在命令行、shell history 或本台账中写入真实值。
2. 在仅 `root` 可读的环境文件或 `systemctl edit wefolio.service` 创建的 drop-in 中配置 `FEISHU_FEEDBACK_WEBHOOK_URL`、`FEISHU_FEEDBACK_WEBHOOK_SECRET`，文件权限保持 `0600`。非生产域名部署另行设置 `FEEDBACK_STATUS_API_BASE_URL`。
3. 分别执行 `systemctl daemon-reload`。发布时沿用 Runtime 双节点滚动顺序，逐节点重启 `wefolio.service` 并完成健康检查，避免两个节点同时中断服务。
4. 重启后在每台节点执行下方命令，检查两个敏感变量已进入实际 Runtime 进程且值非空。命令只输出 `SET` 或 `MISSING`，任一项缺失时以非零状态退出，不会回显密钥：

```bash
runtime_pid="$(systemctl show --property MainPID --value wefolio.service)"
test "${runtime_pid}" -gt 0
sudo awk -v RS='\0' -F= '
  $1 == "FEISHU_FEEDBACK_WEBHOOK_URL" && length($2) > 0 { webhook = 1 }
  $1 == "FEISHU_FEEDBACK_WEBHOOK_SECRET" && length($2) > 0 { secret = 1 }
  END {
    print "FEISHU_FEEDBACK_WEBHOOK_URL=" (webhook ? "SET" : "MISSING")
    print "FEISHU_FEEDBACK_WEBHOOK_SECRET=" (secret ? "SET" : "MISSING")
    exit !(webhook && secret)
  }
' "/proc/${runtime_pid}/environ"
```

5. 分别从两台节点验证 `open.feishu.cn` 的 DNS 解析和 `443` 端口 TCP/TLS 连通性。连通性检查只访问主机和端口，不调用真实机器人 Webhook 路径。
6. 在腾讯云 COS 控制台确认生产 Bucket 的版本控制状态为“未开启（Off）”。反馈直传票据会签入 `x-cos-forbid-overwrite=true`，Runtime 也会在签票前实时校验版本控制；状态不是 `Off` 时拒绝签票，避免已提交附件被原票据覆盖。

反馈附件清理 Job 默认开启。发布顺序固定为先发布 Runtime 并确认 Flyway V53 成功，再发布 Job；不得让新版 Job 在 V53 建表前启动。

本次仓库变更只交付代码和运行手册。实际密钥写入、服务重启、双节点出网验证和真实通知验收，必须在取得生产密钥及部署授权后执行。

飞书配置缺失不会阻断 Runtime 启动；通知配置缺失、网络异常或远端错误只记录一条不含敏感内容的 `error`，不回滚问题创建或追加结果，也不进入重试或补偿流程。

状态接口不读取认证请求头。飞书卡片会按问题编号展示以下同类命令，团队成员复制后修改 `feedbackResult` 即可执行：

```bash
curl -X PUT 'https://api.we-folio.dingchenyong.top/api/internal/feedbacks/FB替换为实际编号' -H 'Content-Type: application/json' -d '{"status":"WAITING_FOLLOW_UP","feedbackResult":"请补充更多信息"}'
curl -X PUT 'https://api.we-folio.dingchenyong.top/api/internal/feedbacks/FB替换为实际编号' -H 'Content-Type: application/json' -d '{"status":"RESOLVED","feedbackResult":"问题已修复，请更新小程序（重新进入小程序后会自动更新）"}'
```

该接口无认证，问题编号等同于操作凭据。禁止把飞书卡片、问题编号或完整命令转发到无关群组、工单或公开日志；如发生泄露，应按数据安全事件处理并评估增加认证或网关访问限制。

### 4.3 作品最终轮人工审核配置与操作

作品第 3 轮（当前 `max-rounds=3` 的最终轮）重审进入 `AUDITING` 后由审核员在飞书完成。该功能使用以下三项专用配置，不读取问题反馈的同类变量：

- `FEISHU_WORK_AUDIT_WEBHOOK_URL`：作品审核机器人 Webhook，敏感，Runtime 两节点必配。
- `FEISHU_WORK_AUDIT_WEBHOOK_SECRET`：作品审核机器人签名密钥，敏感，Runtime 两节点必配。
- `WORK_AUDIT_REVIEW_API_BASE_URL`：卡片审核回传命令的 API 基础地址，默认 `https://api.we-folio.dingchenyong.top`；非生产环境必须覆盖，末尾斜杠会自动移除。

作品审核与问题反馈可以使用相同或不同的飞书机器人配置。作品审核专用值与问题反馈值相同时会投递到同一个群，不同时会分群；即使值相同，也必须分别配置专用变量，禁止代码层回退读取问题反馈变量。

沿用 `wefolio.service` 现有 `EnvironmentFile` 或 root-only drop-in，在两个 Runtime 节点分别写入配置并保持文件权限 `0600`。重启后执行以下检查；命令只输出 `SET` 或 `MISSING`，不回显值。基础地址显示 `MISSING` 表示使用应用默认值，不影响生产配置检查的退出状态：

```bash
runtime_pid="$(systemctl show --property MainPID --value wefolio.service)"
test "${runtime_pid}" -gt 0
sudo awk -v RS='\0' -F= '
  $1 == "FEISHU_WORK_AUDIT_WEBHOOK_URL" && length($2) > 0 { webhook = 1 }
  $1 == "FEISHU_WORK_AUDIT_WEBHOOK_SECRET" && length($2) > 0 { secret = 1 }
  $1 == "WORK_AUDIT_REVIEW_API_BASE_URL" && length($2) > 0 { base_url = 1 }
  END {
    print "FEISHU_WORK_AUDIT_WEBHOOK_URL=" (webhook ? "SET" : "MISSING")
    print "FEISHU_WORK_AUDIT_WEBHOOK_SECRET=" (secret ? "SET" : "MISSING")
    print "WORK_AUDIT_REVIEW_API_BASE_URL=" (base_url ? "SET" : "MISSING")
    exit !(webhook && secret)
  }
' "/proc/${runtime_pid}/environ"
```

发布顺序固定为：先滚动发布两个 Runtime 节点并确认 Flyway `V54__add_work_manual_audit.sql` 成功，再立即发布 Job。旧 Job 通常只扫描 `PENDING` 作品；新版 Job 仍在候选查询、任务领取和结果写回三层排除 `manual_audit_no IS NOT NULL` 的作品，用于防御异常数据和未来调度语义变化。

最终轮状态与人工编号在事务内持久化，事务提交后只尝试一次飞书通知。配置缺失、用户记录失效、网络异常或飞书错误只写一条脱敏错误日志：不回滚作品的 `AUDITING` 状态，不自动重试，也没有人工重发接口。人工审核编号一旦生成便不轮换，即使后续调整 `max-rounds` 也不能据此恢复自动重审。

卡片中的 `curl` 与问题反馈飞书交互保持一致，使用单行 `-d '<json>'`，审核员只修改 JSON 结论和拒绝原因。`PASSED` 的 `auditRejectReason` 必须为空；`REJECTED` 必须提供不超过 512 个 Unicode 码点的原因。拒绝原因含单引号时需改用双引号包裹请求体并转义 JSON 双引号；双引号、反斜杠和控制字符仍需按 JSON 规则转义。回传接口无认证，人工审核编号等同操作凭据；禁止转发卡片、编号和完整命令到无关群组、外部工单或公开日志。

飞书通知失败后没有自动积压巡检。值班人员需先结合脱敏通知错误日志与审核群消息，使用下列手工 SQL 查找超过 24 小时仍无结论的记录：

```sql
SELECT id, manual_audit_no, audit_round, updated_at
FROM wf_work
WHERE audit_status = 'AUDITING'
  AND manual_audit_no IS NOT NULL
  AND manual_audit_result_at IS NULL
  AND updated_at < NOW(3) - INTERVAL 24 HOUR
  AND deleted = 0
ORDER BY updated_at ASC;
```

该查询的时间锚点存在盲区：用户在最终轮 `AUDITING` 期间编辑标题、描述或封面会刷新 `updated_at`，因此可能漏报通知失败记录。彻底盘点时必须再执行不带 24 小时条件的全量清单：

```sql
SELECT id, manual_audit_no, audit_round, updated_at
FROM wf_work
WHERE audit_status = 'AUDITING'
  AND manual_audit_no IS NOT NULL
  AND manual_audit_result_at IS NULL
  AND deleted = 0
ORDER BY updated_at ASC;
```

全量清单无法区分“通知失败”和“审核员尚未处理”，必须结合脱敏错误日志与审核群消息交叉确认。人工编号是最终轮的持久标记，不按当前 `max-rounds` 推导。

上线前仅在非生产 MySQL 8 环境准备专用作品并执行并发验收，禁止使用生产作品或把真实生产人工编号写入测试日志：

1. 从两个终端同时执行卡片中相同结论的审核命令，预期一条响应 `changed=true`、另一条 `changed=false`，两条响应的 `manualAuditResultAt` 完全相同。
2. 另准备一条专用作品，从两个终端同时提交 `PASSED` 与带原因的 `REJECTED`，预期只有第一笔结论成功，另一笔返回结论冲突，数据库最终状态与第一笔一致。
3. 验收后仅清理专用测试数据并保留脱敏结果记录；不得以 Flyway migration 写入任何测试数据清理 SQL。

## 5. 域名、CDN 与证书

| 环境 | 域名 | DNS 服务商 | CDN 服务商 | 源站 | 证书到期日 | 自动续期 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 生产环境 | `api.we-folio.dingchenyong.top` | 待补充 | 待补充 | `49.235.146.161`（Nginx 入口） | 待补充 | 待补充 | DNS 解析到老节点；Nginx 将 Runtime 流量转发到本机 `127.0.0.1:8090` 和新节点内网地址 `10.0.4.7:8090` |

## 6. 对象存储

| 环境 | 云厂商 | Bucket | 地域 | Endpoint | CDN/访问域名 | AccessKey ID | Secret 存放位置 | 权限范围 | 生命周期策略 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 |

问题反馈附件依赖 COS `POST Object` 的禁止覆盖能力。生产 Bucket 必须保持版本控制为 `Off`；如果未来需要启用版本控制，必须先改造反馈附件快照，使其固定引用对象版本，再调整此约束。

## 7. 大模型服务

| 环境 | 服务商 | 用途 | 模型/能力 | Base URL | API Key 标识 | Secret 存放位置 | 额度/计费 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 待补充 | 待补充 | 作品集 AI 生成 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 |

## 8. 数据库

| 环境 | 类型/版本 | 主机地址 | 端口 | 数据库名 | 用户名 | 密码存放位置 | 白名单/安全组 | 备份策略 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 |

## 9. 缓存、队列与其他中间件

| 环境 | 服务类型 | 服务地址 | 端口 | 用户名 | 密码存放位置 | 用途 | 备份/持久化 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 生产环境 | Nginx | `49.235.146.161` | 80/443 | — | — | Runtime 双节点反向代理与滚动发布切流；Job 请求转发到老节点服务 | 待补充 | 配置文件：`/etc/nginx/conf.d/myapp.conf`；Runtime upstream：`127.0.0.1:8090`、`10.0.4.7:8090` |

## 10. 环境变量索引

| 变量名 | 环境 | 用途 | 示例/脱敏值 | 配置位置 | 是否敏感 | 备注 |
| --- | --- | --- | --- | --- | --- | --- |
| `DATABASE_URL` | 待补充 | 数据库连接地址 | 待补充 | 待补充 | 是 | 不建议明文提交 |
| `OBJECT_STORAGE_ACCESS_KEY_ID` | 待补充 | 对象存储 AccessKey ID | 待补充 | 待补充 | 是 | 建议最小权限 |
| `OBJECT_STORAGE_ACCESS_KEY_SECRET` | 待补充 | 对象存储 AccessKey Secret | 待补充 | 待补充 | 是 | 只记录存放位置 |
| `LLM_API_KEY` | 待补充 | 大模型服务调用密钥 | 待补充 | 待补充 | 是 | 只记录存放位置 |
| `FEISHU_FEEDBACK_WEBHOOK_URL` | 生产 Runtime 双节点 | 问题反馈飞书机器人 Webhook | 不记录值 | `wefolio.service` 现有 `EnvironmentFile` 或 root-only drop-in | 是 | URL 含访问令牌，禁止进入日志、数据库和命令历史 |
| `FEISHU_FEEDBACK_WEBHOOK_SECRET` | 生产 Runtime 双节点 | 飞书机器人签名密钥 | 不记录值 | `wefolio.service` 现有 `EnvironmentFile` 或 root-only drop-in | 是 | 两台 Runtime 必须使用同一有效配置 |
| `FEEDBACK_STATUS_API_BASE_URL` | Runtime | 飞书卡片状态更新 curl 的 API 基础地址 | `https://api.we-folio.dingchenyong.top` | `application.yml` 默认值或 `wefolio.service` 环境配置 | 否 | 非生产环境需要覆盖；末尾斜杠会自动移除 |
| `FEISHU_WORK_AUDIT_WEBHOOK_URL` | 生产 Runtime 双节点 | 作品最终轮人工审核飞书机器人 Webhook | 不记录值 | `wefolio.service` 现有 `EnvironmentFile` 或 root-only drop-in | 是 | 与问题反馈值相同则同群，不同则分群；禁止回退读取反馈变量 |
| `FEISHU_WORK_AUDIT_WEBHOOK_SECRET` | 生产 Runtime 双节点 | 作品最终轮人工审核飞书签名密钥 | 不记录值 | `wefolio.service` 现有 `EnvironmentFile` 或 root-only drop-in | 是 | 两台 Runtime 必须使用同一有效配置 |
| `WORK_AUDIT_REVIEW_API_BASE_URL` | Runtime | 人工审核卡片 curl 的 API 基础地址 | `https://api.we-folio.dingchenyong.top` | `application.yml` 默认值或 `wefolio.service` 环境配置 | 否 | 非生产环境必须覆盖；末尾斜杠会自动移除 |
| `FEEDBACK_UPLOAD_CLEANUP_ENABLED` | 生产 Job | 是否启用反馈临时附件清理 | `true` | `wefolio-job.service` 环境配置 | 否 | 默认启用 |
| `FEEDBACK_UPLOAD_CLEANUP_CRON` | 生产 Job | 反馈临时附件清理调度表达式 | `0 40 3 * * ?` | `wefolio-job.service` 环境配置 | 否 | 默认每日低峰执行 |
| `FEEDBACK_UPLOAD_CLEANUP_ZONE` | 生产 Job | 反馈清理 cron 调度时区 | `Asia/Shanghai` | `wefolio-job.service` 环境配置 | 否 | 只影响调度触发；数据库过期比较固定使用 `Asia/Shanghai` |
| `FEEDBACK_UPLOAD_CLEANUP_BATCH_SIZE` | 生产 Job | 单批反馈上传任务数量 | `200` | `wefolio-job.service` 环境配置 | 否 | 有效范围 1 至 1000 |
| `FEEDBACK_UPLOAD_CLEANUP_MAX_BATCHES` | 生产 Job | 单轮最多处理批次数 | `10` | `wefolio-job.service` 环境配置 | 否 | 有效范围 1 至 100 |

## 11. 备份与恢复

| 资源 | 环境 | 备份频率 | 保留周期 | 备份位置 | 恢复方式 | 最近演练时间 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 数据库 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 |
| 对象存储 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 |

## 12. 告警与监控

| 环境 | 监控平台 | 监控对象 | 告警规则 | 通知渠道 | 负责人 | 备注 |
| --- | --- | --- | --- | --- | --- | --- |
| 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 |

积分规则部署校时：

- Flyway 新规则的 `effective_from` 使用数据库 `CURRENT_TIMESTAMP(3)`，运行时活动规则过滤使用应用服务器 `LocalDateTime.now()`；部署前后需确认数据库与应用服务器均已启用 NTP，且两者时钟无明显漂移。
- 发布 V51 后若查档或新版留资返回“积分规则不存在或未启用”，应先比较数据库当前时间、应用日志时间与两条新规则的 `effective_from`，排除时钟漂移后再排查规则状态。

## 13. 安全检查清单

- 生产密钥未明文提交到代码仓库。
- 对象存储密钥使用最小权限策略。
- 数据库未向公网无限制开放。
- 服务器 SSH 登录限制来源 IP 或使用堡垒机。
- 生产数据库启用自动备份，并定期验证恢复流程。
- 证书到期前有自动续期或人工提醒。
- 大模型服务配置了额度、调用频率或账单告警。
- 问题反馈 Webhook URL 和飞书签名密钥未进入代码仓库、数据库、应用日志或部署命令历史。
- 问题反馈状态接口无认证；问题编号和飞书卡片仅在授权团队范围内流转，未进入公开日志或外部工单。
- 作品人工审核回传接口无认证；人工审核编号、飞书卡片和完整审核命令仅在授权审核群内流转。
- 作品人工审核 Webhook URL 和签名密钥未进入代码仓库、数据库、应用日志或部署命令历史。
- 两台 Runtime 均能访问 `open.feishu.cn:443`，连通性检查未调用真实机器人 Webhook。
- 生产 COS Bucket 版本控制为 `Off`，反馈票据中的 `x-cos-forbid-overwrite=true` 已通过联调验证。

## 14. 变更记录

| 日期 | 变更人 | 变更内容 | 影响范围 | 回滚/恢复方式 |
| --- | --- | --- | --- | --- |
| 2026-06-23 | dingchenyong | 添加域名 `api.we-folio.dingchenyong.top`，DNS 解析到 49.235.146.161 | DNS 配置 | 删除 DNS 解析记录 |
| 2026-06-23 | dingchenyong | 服务器部署 Nginx，配置反向代理到 localhost:8090 | Web 访问入口 | 回滚 Nginx 配置 |
| 2026-06-23 | dingchenyong | Java 后端服务部署至 8090 端口，由 systemctl `wefolio.service` 管理 | 后端服务 | 重启旧版本 JAR |
| 2026-08-13 | dingchenyong | 补充 V51 积分规则部署的数据库与应用双时钟检查 | 查档与新版留资积分规则 | 校准 NTP 后重启后端实例 |
| 2026-08-15 | dingchenyong | 根据当前部署脚本补充新服务器 `124.222.148.233`，同步 Runtime 双节点滚动发布、Nginx upstream 与 Job 单节点现状 | 生产服务器与部署台账 | 仅文档更新，不涉及线上资源变更；可恢复本文档上一版本 |
| 2026-08-26 | dingchenyong | 增加问题反馈单次飞书卡片通知、无认证状态更新 curl、COS 禁止覆盖前置条件和过期附件清理说明 | Runtime 双节点、COS Bucket、Job 单节点与团队操作流程 | 仅文档更新；生产配置尚未执行，可恢复本文档上一版本 |
| 2026-08-26 | dingchenyong | 增加作品最终轮人工审核飞书配置、回传安全说明、积压盘点 SQL 与非生产并发验收步骤 | Runtime 双节点、Job 单节点与审核团队操作流程 | 仅文档更新；生产配置尚未执行，可恢复本文档上一版本 |
| 2026-06-19 | 待补充 | 服务器1 启用 SSH 密钥登录并关闭密码登录 | SSH 登录方式 | 服务器备份文件：`/etc/ssh/sshd_config.bak-20260619-before-disable-passwordauth`；恢复后执行 `sshd -t` 并重载 `sshd` |
| 2026-06-19 | 待补充 | 初始化技术信息与服务配置台账 | 文档模板 | 不涉及 |
