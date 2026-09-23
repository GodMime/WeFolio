# 技术信息与服务配置台账

版本：v0.6
日期：2026-09-21
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
| 生产环境 | 待确认 | 待补充 | 新节点（Runtime） | `124.222.148.233` | `10.0.4.7` | CentOS Linux 7 | `root` SSH 密钥登录，部署脚本使用 BatchMode | 22；内网 8090（供老节点 Nginx 访问 Runtime） | `/root/java` | `LOG_PATH`，未配置时默认为 `./logs` | 仅运行 `wefolio.service`，不部署 Nginx 和 Job；Runtime JAR 与老节点保持相同 SHA-256 |

### 4.1 当前生产部署拓扑

以下信息以 `projects/java/wefolio-java-runtime/deploy.sh` 和 `projects/java/wefolio-java-job/deploy.sh` 的当前配置为准：

| 组件 | 老节点 `49.235.146.161` | 新节点 `124.222.148.233` | 服务与路径 | 访问/调度方式 |
| --- | --- | --- | --- | --- |
| Runtime | 已部署 | 已部署 | `wefolio.service`；`/root/java/wefolio-java-runtime.jar` | Nginx upstream 使用 `127.0.0.1:8090` 与 `10.0.4.7:8090`，正常状态为默认 round-robin |
| Job | 已部署 | 未部署 | `wefolio-job.service`；`/root/java/job/wefolio-java-job.jar` | 老节点单实例，服务端口 8091，Web 接口前缀为 `/job-api` |
| Nginx | 已部署 | 未部署 | `nginx`；`/etc/nginx/conf.d/myapp.conf` | 公网流量入口；Runtime 发布时在 `new_only`、`old_only`、`round_robin` 三种路由状态间切换 |

Runtime 发布脚本在本地只构建一次 JAR，计算 SHA-256 后把同一制品依次发布到两个节点。单节点发布时先上传为 `.uploading`，校验通过后将原 JAR 保存为固定的 `.backup`，再原子替换正式 JAR、重启 `wefolio.service` 并执行健康检查。任一节点发布失败时脚本会停止后续步骤，并保持当时的摘流状态，需人工排障后重新发布。

### 4.2 问题反馈 Runtime 配置与出网检查

问题反馈飞书通知依赖三项敏感环境变量：Webhook URL、Webhook 签名密钥和复用的内部回传密钥 `ADMIN_POINT_SECRET`。老节点 `49.235.146.161` 与新节点 `124.222.148.233` 的 `wefolio.service` 必须同时配置，不能只更新其中一个 Runtime 节点。状态接口要求请求头 `X-Admin-Point-Secret` 与 `ADMIN_POINT_SECRET` 完全一致；卡片中的 `curl` 默认使用生产 API 域名，可通过非敏感变量 `FEEDBACK_STATUS_API_BASE_URL` 覆盖。

1. 分别在两台节点执行 `systemctl cat wefolio.service`，确认当前单元使用的 `EnvironmentFile` 或 drop-in；沿用现有注入位置，不在命令行、shell history 或本台账中写入真实值。
2. 在仅 `root` 可读的环境文件或 `systemctl edit wefolio.service` 创建的 drop-in 中配置 `FEISHU_FEEDBACK_WEBHOOK_URL`、`FEISHU_FEEDBACK_WEBHOOK_SECRET`、`ADMIN_POINT_SECRET`，文件权限保持 `0600`。非生产域名部署另行设置 `FEEDBACK_STATUS_API_BASE_URL`。
3. 分别执行 `systemctl daemon-reload`。发布时沿用 Runtime 双节点滚动顺序，逐节点重启 `wefolio.service` 并完成健康检查，避免两个节点同时中断服务。
4. 重启后在每台节点执行下方命令，检查三个敏感变量已进入实际 Runtime 进程且值非空。命令只输出 `SET` 或 `MISSING`，任一项缺失时以非零状态退出，不会回显密钥：

```bash
runtime_pid="$(systemctl show --property MainPID --value wefolio.service)"
test "${runtime_pid}" -gt 0
sudo awk -v RS='\0' -F= '
  $1 == "FEISHU_FEEDBACK_WEBHOOK_URL" && length($2) > 0 { webhook = 1 }
  $1 == "FEISHU_FEEDBACK_WEBHOOK_SECRET" && length($2) > 0 { secret = 1 }
  $1 == "ADMIN_POINT_SECRET" && length($2) > 0 { admin_secret = 1 }
  END {
    print "FEISHU_FEEDBACK_WEBHOOK_URL=" (webhook ? "SET" : "MISSING")
    print "FEISHU_FEEDBACK_WEBHOOK_SECRET=" (secret ? "SET" : "MISSING")
    print "ADMIN_POINT_SECRET=" (admin_secret ? "SET" : "MISSING")
    exit !(webhook && secret && admin_secret)
  }
' "/proc/${runtime_pid}/environ"
```

5. 分别从两台节点验证 `open.feishu.cn` 的 DNS 解析和 `443` 端口 TCP/TLS 连通性。连通性检查只访问主机和端口，不调用真实机器人 Webhook 路径。
6. 在腾讯云 COS 控制台确认生产 Bucket 的版本控制状态为“未开启（Off）”。反馈直传票据会签入 `x-cos-forbid-overwrite=true`，Runtime 也会在签票前实时校验版本控制；状态不是 `Off` 时拒绝签票，避免已提交附件被原票据覆盖。

反馈附件清理 Job 默认开启。发布顺序固定为先发布 Runtime 并确认 Flyway V53 成功，再发布 Job；不得让新版 Job 在 V53 建表前启动。

本次仓库变更只交付代码和运行手册。实际密钥写入、服务重启、双节点出网验证和真实通知验收，必须在取得生产密钥及部署授权后执行。

飞书配置缺失不会阻断 Runtime 启动；通知配置或 `ADMIN_POINT_SECRET` 缺失、网络异常或远端错误只记录一条不含敏感内容的 `error`，不回滚问题创建或追加结果，也不进入重试或补偿流程。`ADMIN_POINT_SECRET` 为空时不会发送缺少认证头的不可执行卡片。

状态接口读取 `X-Admin-Point-Secret` 并在任何业务读写前校验。飞书卡片会把当前 Runtime 配置的完整 `ADMIN_POINT_SECRET` 写入以下同类命令，团队成员复制后修改 `feedbackResult` 即可执行：

```bash
curl -X PUT 'https://api.we-folio.dingchenyong.top/api/internal/feedbacks/FB替换为实际编号' -H 'Content-Type: application/json' -H 'X-Admin-Point-Secret: <ADMIN_POINT_SECRET实际值>' -d '{"status":"WAITING_FOLLOW_UP","feedbackResult":"请补充更多信息"}'
curl -X PUT 'https://api.we-folio.dingchenyong.top/api/internal/feedbacks/FB替换为实际编号' -H 'Content-Type: application/json' -H 'X-Admin-Point-Secret: <ADMIN_POINT_SECRET实际值>' -d '{"status":"RESOLVED","feedbackResult":"问题已修复，请更新小程序（重新进入小程序后会自动更新）"}'
```

完整飞书命令包含共享内部密钥。禁止把飞书卡片或完整命令转发到无关群组、工单或公开日志；如发生泄露，应按数据安全事件处理，立即轮换两台 Runtime 及所有内部调用方的 `ADMIN_POINT_SECRET`。

### 4.3 作品最终轮人工审核配置与操作

作品第 3 轮（当前 `max-rounds=3` 的最终轮）重审进入 `AUDITING` 后由审核员在飞书完成。该功能使用以下三项专用配置，不读取问题反馈的同类变量，并额外复用内部回传密钥 `ADMIN_POINT_SECRET`：

- `FEISHU_WORK_AUDIT_WEBHOOK_URL`：作品审核机器人 Webhook，敏感，Runtime 两节点必配。
- `FEISHU_WORK_AUDIT_WEBHOOK_SECRET`：作品审核机器人签名密钥，敏感，Runtime 两节点必配。
- `WORK_AUDIT_REVIEW_API_BASE_URL`：卡片审核回传命令的 API 基础地址，默认 `https://api.we-folio.dingchenyong.top`；非生产环境必须覆盖，末尾斜杠会自动移除。
- `ADMIN_POINT_SECRET`：与积分内部接口共用的回传密钥，敏感，Runtime 两节点必配；卡片 curl 会包含完整实际值。

作品审核与问题反馈可以使用相同或不同的飞书机器人配置。作品审核专用值与问题反馈值相同时会投递到同一个群，不同时会分群；即使值相同，也必须分别配置专用变量，禁止代码层回退读取问题反馈变量。

沿用 `wefolio.service` 现有 `EnvironmentFile` 或 root-only drop-in，在两个 Runtime 节点分别写入配置并保持文件权限 `0600`。重启后执行以下检查；命令只输出 `SET` 或 `MISSING`，不回显值。基础地址显示 `MISSING` 表示使用应用默认值，不影响生产配置检查的退出状态：

```bash
runtime_pid="$(systemctl show --property MainPID --value wefolio.service)"
test "${runtime_pid}" -gt 0
sudo awk -v RS='\0' -F= '
  $1 == "FEISHU_WORK_AUDIT_WEBHOOK_URL" && length($2) > 0 { webhook = 1 }
  $1 == "FEISHU_WORK_AUDIT_WEBHOOK_SECRET" && length($2) > 0 { secret = 1 }
  $1 == "WORK_AUDIT_REVIEW_API_BASE_URL" && length($2) > 0 { base_url = 1 }
  $1 == "ADMIN_POINT_SECRET" && length($2) > 0 { admin_secret = 1 }
  END {
    print "FEISHU_WORK_AUDIT_WEBHOOK_URL=" (webhook ? "SET" : "MISSING")
    print "FEISHU_WORK_AUDIT_WEBHOOK_SECRET=" (secret ? "SET" : "MISSING")
    print "WORK_AUDIT_REVIEW_API_BASE_URL=" (base_url ? "SET" : "MISSING")
    print "ADMIN_POINT_SECRET=" (admin_secret ? "SET" : "MISSING")
    exit !(webhook && secret && admin_secret)
  }
' "/proc/${runtime_pid}/environ"
```

发布顺序固定为：先滚动发布两个 Runtime 节点并确认 Flyway `V54__add_work_manual_audit.sql` 成功，再立即发布 Job。旧 Job 通常只扫描 `PENDING` 作品；新版 Job 仍在候选查询、任务领取和结果写回三层排除 `manual_audit_no IS NOT NULL` 的作品，用于防御异常数据和未来调度语义变化。

最终轮状态与人工编号在事务内持久化，事务提交后只尝试一次飞书通知。配置缺失、用户记录失效、网络异常或飞书错误只写一条脱敏错误日志：不回滚作品的 `AUDITING` 状态，不自动重试，也没有人工重发接口。人工审核编号一旦生成便不轮换，即使后续调整 `max-rounds` 也不能据此恢复自动重审。

卡片中的 `curl` 与问题反馈飞书交互保持一致，使用单行 `-d '<json>'`，并携带完整的 `X-Admin-Point-Secret: <ADMIN_POINT_SECRET实际值>`。审核员只修改 JSON 结论和拒绝原因，不得修改或删除认证头。`PASSED` 的 `auditRejectReason` 必须为空；`REJECTED` 必须提供不超过 512 个 Unicode 码点的原因。拒绝原因含单引号时需改用双引号包裹请求体并转义 JSON 双引号；双引号、反斜杠和控制字符仍需按 JSON 规则转义。回传服务会在任何业务读写前校验内部密钥；禁止转发包含完整密钥的卡片和命令到无关群组、外部工单或公开日志。

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

### 4.4 作品集字体 Python 运行环境（已安装）

2026-09-21 已在老节点 `49.235.146.161` 和新节点 `124.222.148.233` 安装相同的独立字体工具链。两台实际系统均为 CentOS Linux 7 / x86_64 / glibc 2.17，系统 `/usr/bin/python3` 仍为 3.6.8；新环境没有替换系统 Python 或修改全局 PATH。

| 项目 | 两节点一致的版本或路径 |
| --- | --- |
| 环境目录 | `/opt/wefolio/font-runtime` |
| Python | `3.12.14`；`/opt/wefolio/font-runtime/bin/python` |
| FontTools | `4.59.0` |
| HarfBuzz | `14.2.1`；`/opt/wefolio/font-runtime/bin/hb-subset` |
| zlib / OpenSSL | `1.3.2` / `3.6.4` |
| 安装工具 | `/opt/wefolio/tools/micromamba-2.9.0`，官方 conda-forge 包经 SHA-256 校验 |
| 依赖锁文件 | `/opt/wefolio/font-runtime/conda-explicit.lock`，含 66 个固定版本/构建及包校验值 |
| 环境验收记录 | `/opt/wefolio/font-runtime/environment-verification.json` |
| 依赖缓存 | `/opt/wefolio/.mamba-font-runtime`，仅本独立环境使用 |
| 工具链指纹 | `24accdc16a01fe2a62a4aa7d2f079f9b2ed5fab81dfa55cb8155e793372c0b26` |

第二台根据第一台导出的 `@EXPLICIT` 清单安装，两台锁文件中的包 URL/校验值完全一致。规范化包清单 SHA-256 为 `97f9a84bff2eb3ed5e08f3a85d80dca6172f1f3f3eee212125c594c8c2865421`；计算口径为取锁文件中以 `https://` 开头的行、排序、以换行连接且末尾无换行。

已用当时仓库的正式 `subset.py`、源字体和完整许可文件，分别批量生成九个真实字重组合。最终 WOFF 的格式、字重、cmap、nameID 0 原版权、nameID 13 完整许可及源有的 nameID 14 均通过重开校验；两台九份文件 SHA-256 逐项相同，`pip check` 均通过。代表性短语批次生成耗时为老节点 621 ms、新节点 478 ms，仅用于环境验收，未测真实 COS 上传、端到端保存或负载下延迟。临时脚本、源字体副本和 WOFF 已从服务器清理，仅留验收 JSON。

本次未发布 JAR、执行数据库迁移、重启服务、改 systemd 或启用字体功能。两台 Runtime 安装前后 PID 分别保持 `2104` / `18749`，最终本机健康检查均返回 `UP`；第二台健康地址为 `http://10.0.4.7:8090/api/health`。

后续发布字体功能时，Java 应使用以下明确路径，不依赖系统 `python3`。此处是待发布配置说明，本次未写入生产应用配置：

```yaml
portfolio:
  fonts:
    python: /opt/wefolio/font-runtime/bin/python
    harfbuzz: /opt/wefolio/font-runtime/bin/hb-subset
    toolchain-hash: 24accdc16a01fe2a62a4aa7d2f079f9b2ed5fab81dfa55cb8155e793372c0b26
```

`expected-build-id` 还依赖本次发布的源清单、脚本及许可证，必须按最终 JAR 重新计算并通过启动自检后再启用，不能把工具链指纹直接用作构建指纹。运行时不需要激活 Conda，也不需要后台 Python 服务。重建环境使用保留的锁文件；环境已被业务引用后，升级或移除前先完成业务摘流和配置迁移。

### 4.5 最终字体 JAR 双节点隔离验收（2026-09-22）

将同一最终 JAR 临时放到两台服务器，通过独立 main 校验源文件、工具链、许可证及 12 组生成自检；未启动 Spring、连接数据库或 COS，也未替换线上 JAR。每台冷、热两轮均为 `enabled=false, ready=true, selfTestGroups=12`，工具链指纹与 §4.4 一致。

- JAR：127,767,803 字节；SHA-256 `b05a3fe89d4ab7e17bb6e0273c0f8747b12ad4555c2f7d86d1952ff83e8539cd`。
- 构建指纹：`657bbaae35d6f36318f25289b0023f3e276efbd499b8a58281240570a45d8725`；仅适用于本次最终制品，重新构建后须重新核验。
- 包内 23 份字体源、清单、脚本及许可资源与源码逐项 SHA-256 相同，未包含 Python 缓存。

| 节点 | 冷自检耗时 / 最大 RSS | 热自检耗时 / 最大 RSS |
| --- | --- | --- |
| 49.235.146.161 | 3185 ms / 147380 KiB | 2797 ms / 148740 KiB |
| 124.222.148.233 | 2516 ms / 162072 KiB | 1820 ms / 157992 KiB |

每节点提取资源 83,602,790 字节。上述耗时是独立启动自检耗时，不是保存接口延迟或容量结论。线上服务 PID/运行状态前后保持一致；本次临时 JAR、工作目录和日志已清理，已安装的 Python 环境保留。详细记录在本地 `docs/design/portfolio-multi-font/final-jar-node-verification.json` 和 `final-jar-resources.json`。Runtime/Job 部署、数据库迁移、历史目录补建和生产字体开关均未执行。

### 4.6 字体修复 v2 冻结与隔离验收（2026-09-22）

§4.5 保留为 v1 历史制品证据。v2 已更新 manifest 样张引用和子集完成协议，必须采用下列新身份，不能复用旧 expected-build-id：

- JAR：127778481 字节；SHA-256 `ca8d215f2dad74a9bf66d805b045622e794e0d20499575422f2c4ff85a332ecb`。
- manifest SHA-256：`6199ed70803f3c3371f7d26047b306c351c7549b3d3d017309179883f62e8268`；包内 23 份字体源、清单、脚本、许可均与源码逐项匹配，无 Python 缓存。
- `expected-build-id` / `FONT_TARGET_BUILD_ID`：`b3b75f94f637a46fccaf379a4c1f328a7c872db80c4e254b9fdcee1c2cf660c8`。
- `FONT_TOOLCHAIN_HASH`：`24accdc16a01fe2a62a4aa7d2f079f9b2ed5fab81dfa55cb8155e793372c0b26`，两节点一致。
- 同一 JAR 的两节点冷/热四轮自检均为 `enabled=false, ready=true, selfTestGroups=12`；老节点 3136 / 2603 ms，新节点 2545 / 2144 ms。独立 main 不启动 Spring、不连接数据库或 COS。
- 两台 `wefolio.service` 始终 active，PID 保持 2104 / 18749；本次临时 JAR、提取目录均已清理，线上 JAR 与服务未更改。
- 本机必验入口 `projects/java/wefolio-java-runtime/scripts/verify-portfolio-fonts.sh`：282 项 Java、18 项 Python、32 个 Shell 函数零失败/零跳过。Runtime 全量 2277 项无失败，13 项非字体可选测试跳过；小程序 2479 项全通过。必验登记、解析脚本属 runtime 工程源码，不读取 `.env` 或真实凭据。
- 字体进程要求 macOS/Linux 的 POSIX 会话和 `/bin/kill`，运行前建组，异常时停止并确认孤立写入者；本次两台 Linux 自检均覆盖新启动方式。

本轮只完成代码和隔离验收，未部署、迁移、补历史目录、开启生产字体或发小程序包。九处实际界面和完整 iOS/Android 业务链仍待对应版本验收。详细证据在本地 `portfolio-multi-font-repair-results-v2.md`、`final-jar-node-verification-v2.json`、`final-jar-resources-v2.json`。

新 buildId 只影响新子集摘要；旧 READY 资产仍按原 weight/objectKey/URL 复用，不迁移、不重发。清理拒绝的旧目录、缺桶或越界引用仅记录为可能残留；人工处置前核实归属与草稿/发布引用，本轮没有清理历史对象。

### 4.7 Runtime / Job 实际发布与历史目录修复（2026-09-23）

按用户授权，依次执行 Runtime 部署脚本、服务器检查、Job 部署脚本、服务器检查及现有历史目录修复任务。以下是当前线上状态，§4.4—4.6 的未部署描述保留为对应日期的历史记录。

- Runtime：双节点滚动发布成功，耗时 98.6 秒；Nginx 恢复双节点轮询，两节点及正式 HTTPS 健康入口均 UP。两台 JAR SHA-256 均为 `dc5ff4d6bc5986e04c01b4fcac0a26cfec0e50efd562aa20e62a15f17468b235`，127778481 字节。版本 `971336c`，构建时间 `2026-09-23 09:36:55`；该版本号为工作区 Git 基点，包含本次未提交改动，制品身份以 SHA-256 为准。
- 与 §4.6 冻结包逐项比较，`BOOT-INF/classes/` 和 `BOOT-INF/lib/` 中只有 `version.properties` 不同；应用代码、字体资源和依赖完全一致。字体 buildId / toolchainHash 沿用 §4.6 已验证身份。
- 发布阶段为 `disabled`：两节点 `enabled=false, ready=false, reasonCodes=[DISABLED]`。脚本已写入 `/root/java/portfolio-fonts.env` 和 `/etc/systemd/system/wefolio.service.d/zzzz-portfolio-fonts.conf`。未开启字体生成、启动自检或另行配置字体告警。
- Flyway：第一节点从 V58 升到 V59，第二节点确认 V59；只新增作品集草稿/发布字体资源两列。未执行数据删除或回填。
- Job：老节点 `49.235.146.161` 部署成功，耗时 18.7 秒；旧实例停用返回 `activeTaskCount=0` 后重启。新服务及正式 HTTPS 健康接口均 UP。JAR SHA-256 `74b1d5f38535f16214cb777577dcb1ddb6d1c6d5272b4142066a8032dab7ec5b`，版本 `971336c`，构建时间 `2026-09-23 09:40:10`。发布前 Job 全量测试 308 项，零失败/错误，2 项 COS 远端集成测试跳过。
- Job 旧 JAR 备份：`/root/java/job/wefolio-java-job.jar.pre-font-20260923`，SHA-256 `ba1eea145af6b7e7f74f59f2bb2ed19cb559e2ccca7fcf994f87f70952b9c435`。
- 通过服务器本机调用一次 `POST /job-api/user-storage/folders/repair`，密钥只在进程内存读取使用，未输出。执行 ID `8a422e47-bd47-4f11-8a74-79ce6b161fa7`；首次响应显示未确认，随即通过该执行日志确认成功，未重复提交。
- `09:41:56.834` 目录修复完成：48 个用户，96 个已有目录，48 个新建目录，0 失败；4 个团队，0 个已有字体目录，4 个新建字体目录，0 失败。只补空目录，不处理字体文件。
- `09:42:33` 最终检查：两 Runtime、Job 健康均 UP，Nginx active。Runtime PID 老/新 `10105` / `9894`，Job PID `11208`。未发布小程序、未处理 HTTPS 证书。

本地操作日志：`/tmp/wefolio-runtime-deploy-20260923.log`、`/tmp/wefolio-job-deploy-20260923.log`、`/tmp/wefolio-job-predeploy-20260923.log`。后续开启字体仍须完成相应阶段准入；本次只完成 disabled 发布。

### 4.8 Runtime prevalidated 发布（2026-09-23 10:08）

用户授权进入预校验阶段后，执行现有 Runtime 部署脚本，`FONT_DEPLOY_PHASE=prevalidated`；target 和 accepted 均使用 §4.6 已独立验收、§4.7 已发布的字体 buildId `b3b75f94f637a46fccaf379a4c1f328a7c872db80c4e254b9fdcee1c2cf660c8`，toolchainHash 不变。

- 双节点滚动发布成功，退出码 0，总耗时 102.6 秒；Nginx 已恢复双节点轮询。
- 当前运行开关：`PORTFOLIO_FONTS_ENABLED=false`、`PORTFOLIO_FONTS_VALIDATION_ENABLED=true`。两节点 `ready=true`、`reasonCodes=[]`，启动字体环境和生成许可自检通过，但用户请求仍不能生成新子集。
- 新 JAR SHA-256：`c4088f38ceb63ae1ac4a8f36f3bc04858a6f6cd659888514f42d63d817a1a5f4`，127778481 字节；版本 `971336c`，构建时间 `2026-09-23 10:05:54`。两服务器摘要与本地一致；应用代码/资源/依赖对比 §4.6 冻结包，仅 `version.properties` 不同。
- 使用 `check-font-nodes.py --allow-disabled` 只读检查，退出码 0，`ok=true, readyNodes=2, requiredReadyNodes=2`，无漂移或故障原因；未发送告警。
- 最终检查：两 Runtime active/running，PID 老/新 `17298` / `16436`；正式 HTTPS 健康入口 UP，字体 ready=true。Job 仍为 PID `11208`、健康 UP；本次未重发 Job 或重复执行目录修复。
- 发布日志：`/tmp/wefolio-runtime-prevalidated-20260923.log`。本阶段不等同于 enabled；未启用字体生成，也未发布小程序。

### 4.9 字体启用与部署脚本解耦（2026-09-23 12:33）

按用户最新要求，先用原部署流程完成 enabled 发布，再移除 Runtime 部署脚本中的字体发布流程。§4.7—4.8 的阶段命令保留为历史操作记录，后续发布不再使用 `FONT_DEPLOY_PHASE`、`FONT_TARGET_BUILD_ID`、`FONT_ACCEPTED_BUILD_ID` 等部署变量。

- enabled 双节点滚动发布成功，退出码 0，总耗时 99.1 秒。两节点 `enabled=true, ready=true, reasonCodes=[]`，buildId 均为 `b3b75f94f637a46fccaf379a4c1f328a7c872db80c4e254b9fdcee1c2cf660c8`；独立双节点检查 `ok=true, readyNodes=2`，未发送告警。
- 当前 Runtime JAR SHA-256 两节点一致：`d87addd6d14fcba20386a646c36fa130f8adbbd67238397ba0f95c96cb5ea21b`。版本 `971336c`，构建时间 `2026-09-23 12:28:27`。对比原验收包的应用代码、资源及依赖，仅 `version.properties` 变化。
- 老/新 Runtime PID 为 `17354` / `15413`；Nginx 已恢复双节点轮询；正式 HTTPS 健康入口 UP，字体 enabled/ready 均为 true。Job 保持 PID `11208` 且 UP，没有重发或重复目录修复。
- `deploy.sh` 已移除字体阶段变量、构建身份入参约束、`portfolio-fonts.env` / systemd 写入及字体专用健康校验。删除无调用方的 `scripts/validate-font-health.py`。保留构建一次、SHA-256 核对、JAR 备份、滚动重启、Nginx 切流及服务级健康检查；健康仅解析 `data.status == UP`，拒绝无效 JSON、嵌套假 UP 和网络失败。
- 后续在 runtime 工程执行 `bash deploy.sh` 即可。脚本不再管理字体配置，也不因字体 ready/buildId 阻断普通后端发布。服务器现有 `/root/java/portfolio-fonts.env` 与 `/etc/systemd/system/wefolio.service.d/zzzz-portfolio-fonts.conf` 保留，当前启用状态持续生效。
- 应用自身的字体启动自检、构建身份核对、故障锁存和告警能力保持原行为。若将来调整字体源/清单/脚本/工具链，需要独立核验并更新两台服务器的 `PORTFOLIO_FONTS_EXPECTED_BUILD_ID`、`PORTFOLIO_FONTS_TOOLCHAIN_HASH` 等配置；普通部署不会自动更新这些值。`scripts/check-font-nodes.py` 保留为独立运维检查工具。
- 部署回归 30 项全部通过；更新后的完整必验 Java 337 项、Python 18 项、Shell 30 个函数，零失败、零错误、零跳过。报告 `target/font-verification/run-jpdh4n0e/`。已用简化脚本在无字体参数时执行生产只读 `preflight`，退出码 0；没有为验证脚本而重复重启线上服务。

日志：`/tmp/wefolio-runtime-enabled-20260923.log`、`/tmp/wefolio-deploy-simplified-tests-20260923.log`、`/tmp/wefolio-font-gate-deploy-decoupled-20260923.log`。小程序发包与完整真机业务验收尚未执行。

## 5. 域名、CDN 与证书

| 环境 | 域名 | DNS 服务商 | CDN 服务商 | 源站 | 证书到期日 | 自动续期 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 生产环境 | `api.we-folio.dingchenyong.top` | 待补充 | 待补充 | `49.235.146.161`（Nginx 入口） | 待补充 | 待补充 | DNS 解析到老节点；Nginx 将 Runtime 流量转发到本机 `127.0.0.1:8090` 和新节点内网地址 `10.0.4.7:8090` |

## 6. 对象存储

| 环境 | 云厂商 | Bucket | 地域 | Endpoint | CDN/访问域名 | AccessKey ID | Secret 存放位置 | 权限范围 | 生命周期策略 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 | 待补充 |

问题反馈附件依赖 COS `POST Object` 的禁止覆盖能力。生产 Bucket 必须保持版本控制为 `Off`；如果未来需要启用版本控制，必须先改造反馈附件快照，使其固定引用对象版本，再调整此约束。

### 6.1 作品集小程序码资源

本功能使用现有 COS Bucket，官方原码对象键固定为 `{uniqueCode}/protfolio/miniapp-code/{portfolioId}/{envVersion}/{codeHash}.{ext}`。个人使用作品集所属用户唯一码，团队使用所属团队唯一码；团队成员分享不写入成员个人目录。保留既有 `protfolio` 拼写，环境分为 `release`、`trial`、`develop`。原码参数摘要不包含头像或标题，扩展名及 Content-Type 与实际 PNG/JPEG 格式一致。

Redis 只保存原码索引、生成锁和限流信息；索引到期后优先从确定性 COS 对象恢复。Redis TTL 不删除 COS 图片，不能为此更改整个 `protfolio/` 的存储生命周期。头像继续使用原 `others/` 对象；最终分享图由手机 Canvas 合成，不上传 COS，也不依赖后端中文字体。

分享页的“logo 使用头像”开关默认关闭，完全由前端控制，不向后端发送开关状态。两个现有 POST 资源接口仅返回原码与所属个人/团队的资料信息；头像不参与远端 HEAD、下载或格式校验，头像信息异常不能阻断原码返回。当前自有无参数头像地址沿用固定首帧缩略 PNG 规则，历史域名或带参数的资料头像地址原样返回。前端仅在打开开关后下载、解码和覆盖头像，切换复用本页资源；头像下载或绘制失败可关闭开关恢复原码，鉴权失效或作品集下架仍阻断分享。

接口路径、请求参数、响应字段和 COS 原码目录保持不变，后端不感知 `useAvatar`，无需新增环境变量。正常头像上传使用新对象地址，头像内容版本按资料 URL 计算；手工覆盖同一地址或外部服务原址换图不会由后端主动探测，当前页面可继续复用已下载头像。故障排查需先核对两节点 `/api/version` 与部署包，不能仅凭前端版本判断后端修复已生效。

发布前检查（当前未执行线上配置变更）：

- 原码及头像的 HTTPS 图片域名须配置为微信小程序 `downloadFile` 合法域名，真机关闭开发工具的域名校验豁免后验证。
- COS 数据万象须支持固定首帧、缩略 PNG 处理规则；GIF/WebP 头像处理结果由手机直接下载。
- CDN 缓存键须区分图片处理参数和 `v` 版本参数；头像常规上传使用新地址，原码修复依赖对象版本参数避开旧缓存。
- Runtime 双节点使用一致的 AppID、环境、落地页校验配置及共享 Redis；正式环境保持 `release` 和 `check_path=true`。
- 个人/团队访客落地页必须已发布。中心头像覆盖须通过 iOS/Android 的扫一扫、相册及聊天压缩图片识别验收；自动化绘图测试不替代真机识别。

相册保存的平台配置（2026-09-18 已在开发者工具复现拦截，尚未修改后台声明）：

- 原生错误 `saveImageToPhotosAlbum:fail api scope is not declared in the privacy agreement` 表示平台缺少对应隐私类型声明，官方错误码字段为 `errno=112`；不属于用户拒绝授权或图片文件失效。
- 管理员在“小程序管理后台 → 设置 → 服务内容声明 → 用户隐私保护指引”补充“使用你的相册（仅写入）权限”。用途建议填写：`用于将用户主动生成的作品集小程序码分享图片保存到手机相册，便于分享作品集。` 请由管理员核对后提交平台声明；官方文档说明补充声明约 5 分钟后生效。
- 生效后重新进入分享码页，点击保存并完成微信官方隐私弹窗和相册授权，分别验收模拟器、安卓及 iOS。声明缺失不能通过修改 `app.json`、反复打开设置或新增 `wx.authorize` 解决。
- 小程序失败日志标签为 `portfolio-miniapp-code-save-failed`，记录 `getSetting` / `saveImageToPhotosAlbum` / `openSetting` 阶段、脱敏错误及数字错误码。失败不写分享记录，仍可预览本地图片。
- 2026-09-18 的 iPhone 截图报错来自旧后端头像校验；当日排查发现线上两台 JAR 均未包含此前兼容修复。最终方案移除了生成原码对头像 URL、MIME、大小和 ETag 的硬依赖，头像只作为可选展示资料交给前端。此修复需发布 Runtime，接口和环境变量不变；新版生成接口不再产生“头像读取失败”业务错误。前端开启头像后的下载/绘制故障仍须按具体资源排查，不可仅凭终端类型判断原因。
- 官方依据：[隐私授权开发指引](https://developers.weixin.qq.com/miniprogram/dev/framework/user-privacy/PrivacyAuthorize.html)、[隐私类型与接口对应关系](https://developers.weixin.qq.com/miniprogram/dev/framework/user-privacy/miniprogram-intro.html)。

### 6.2 作品集字体静态素材发布（2026-09-22）

Mock 体验版九份物理字重 WOFF 与六张样张 PNG 已发布至 `https://cdn2.we-folio.dingchenyong.top/demo/portfolio-fonts/{fontId}/{fontVersion}/{sha256}.{woff|png}`。固定语料包括体验版作品集、默认文字组件、内链演示页和选择器样张。各最终 WOFF 使用正式许可校验器复核原版权、OFL 全文及源已有许可链接；发布后逐个 GET 核验 SHA-256、`font/woff` 和 `Access-Control-Allow-Origin: *`；PNG 核验摘要及 `image/png`。本地记录在 `design/portfolio-multi-font/mock-static/publication.json`，素材共 416,635 字节。

CDN 响应头已新增九个 WOFF 的精确路径规则，并追加仅匹配 WOFF 文件后缀的 CORS 规则（`Access-Control-Allow-Origin: *`），覆盖后续个人/团队 UUID 字体子集路径；保留原有规则，图片和视频响应不变。已用不在九个精确路径中的 WOFF 验证后缀规则生效。未修改 COS 全桶权限或 CORS、未部署 Runtime/Job JAR、未执行迁移或历史目录修复、未开启线上字体生成。微信合法下载域名与正式 iOS/Android 页面业务链仍需上线前实机验收。

字体告警已确定复用现有飞书机器人，使用独立 `PORTFOLIO_FONTS_ALERT_*` 配置；本轮只实现代码及本地 HTTP 验证，未修改生产机器人配置或发送群测试消息。节点数量/构建漂移脚本供现有监控设施调用，不新增字体生成、重试或清理任务。

### 6.3 字体样张 v2（2026-09-22）

六张新 PNG 已上传 COS；三张中文样张统一“以光为笔，记录心动”，三张英文复用原字节。PNG 改用自身 SHA-256 命名，与同款 400 字重 WOFF 摘要解耦；旧图片对象保留，九份 WOFF 内容与地址未变。

HTTPS GET 核验因 CDN 证书过期未通过。**用户已明确本轮不处理 HTTPS 证书问题**；未关闭证书校验，发布记录保持 `published=false` / `cdnVerified=false`。该项不计作 CDN 验收成功。

正式样张引用须随包含上述 buildId 的 Runtime 包生效；Mock sampleUrl 与四处模板修复须随小程序包发布并被采用后生效。两条生效链均未发布，不能用 COS 上传成功代替客户端生效。§6.2 是此前地址的历史证据，新地址状态以本节及本地 `design/portfolio-multi-font/mock-static/publication.json` 为准。

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
| `WEFOLIO_LOGIN_DEFAULT_TAB` | Runtime 双节点 | 小程序登录页普通入口默认标签 | `experience` / `maintainer` | `application.yml` 默认值或 `wefolio.service` 环境配置 | 否 | 默认 `experience`（体验）；`maintainer` 表示登录 / 注册；双节点应一致，修改后重启 Runtime 生效 |
| `PORTFOLIO_MINIAPP_CODE_ENV_VERSION` | Runtime 双节点 | 官方小程序码打开的微信版本 | `release` / `trial` / `develop` | `application.yml` 默认值或服务环境配置 | 否 | 默认 `release`；不同环境使用独立 COS 目录与缓存身份 |
| `PORTFOLIO_MINIAPP_CODE_CHECK_PATH` | Runtime 双节点 | 微信是否检查落地页存在 | `true` | `application.yml` 默认值或服务环境配置 | 否 | 默认 `true`；正式环境保持开启，双节点一致 |
| `FEISHU_FEEDBACK_WEBHOOK_URL` | 生产 Runtime 双节点 | 问题反馈飞书机器人 Webhook | 不记录值 | `wefolio.service` 现有 `EnvironmentFile` 或 root-only drop-in | 是 | URL 含访问令牌，禁止进入日志、数据库和命令历史 |
| `FEISHU_FEEDBACK_WEBHOOK_SECRET` | 生产 Runtime 双节点 | 飞书机器人签名密钥 | 不记录值 | `wefolio.service` 现有 `EnvironmentFile` 或 root-only drop-in | 是 | 两台 Runtime 必须使用同一有效配置 |
| `ADMIN_POINT_SECRET` | Runtime 双节点及内部调用方 | 积分、反馈状态和作品人工审核回传的共享内部密钥 | 不记录值 | root-only 服务环境配置 | 是 | 两台 Runtime 与所有调用方必须一致；反馈和审核飞书卡片会包含完整值 |
| `FEEDBACK_STATUS_API_BASE_URL` | Runtime | 飞书卡片状态更新 curl 的 API 基础地址 | `https://api.we-folio.dingchenyong.top` | `application.yml` 默认值或 `wefolio.service` 环境配置 | 否 | 非生产环境需要覆盖；末尾斜杠会自动移除 |
| `FEISHU_WORK_AUDIT_WEBHOOK_URL` | 生产 Runtime 双节点 | 作品最终轮人工审核飞书机器人 Webhook | 不记录值 | `wefolio.service` 现有 `EnvironmentFile` 或 root-only drop-in | 是 | 与问题反馈值相同则同群，不同则分群；禁止回退读取反馈变量 |
| `FEISHU_WORK_AUDIT_WEBHOOK_SECRET` | 生产 Runtime 双节点 | 作品最终轮人工审核飞书签名密钥 | 不记录值 | `wefolio.service` 现有 `EnvironmentFile` 或 root-only drop-in | 是 | 两台 Runtime 必须使用同一有效配置 |
| `WORK_AUDIT_REVIEW_API_BASE_URL` | Runtime | 人工审核卡片 curl 的 API 基础地址 | `https://api.we-folio.dingchenyong.top` | `application.yml` 默认值或 `wefolio.service` 环境配置 | 否 | 非生产环境必须覆盖；末尾斜杠会自动移除 |
| `FEEDBACK_UPLOAD_CLEANUP_ENABLED` | 生产 Job | 是否启用反馈临时附件清理 | `true` | `wefolio-job.service` 环境配置 | 否 | 默认启用 |
| `FEEDBACK_UPLOAD_CLEANUP_CRON` | 生产 Job | 反馈临时附件清理调度表达式 | `0 40 3 * * ?` | `wefolio-job.service` 环境配置 | 否 | 默认每日低峰执行 |
| `FEEDBACK_UPLOAD_CLEANUP_ZONE` | 生产 Job | 反馈清理 cron 调度时区 | `Asia/Shanghai` | `wefolio-job.service` 环境配置 | 否 | 只影响调度触发；数据库过期比较固定使用 `Asia/Shanghai` |
| `FEEDBACK_UPLOAD_CLEANUP_BATCH_SIZE` | 生产 Job | 单批反馈上传任务数量 | `200` | `wefolio-job.service` 环境配置 | 否 | 有效范围 1 至 1000 |
| `FEEDBACK_UPLOAD_CLEANUP_MAX_BATCHES` | 生产 Job | 单轮最多处理批次数 | `10` | `wefolio-job.service` 环境配置 | 否 | 有效范围 1 至 100 |

登录页通过免登录接口 `GET /api/auth/login-page-config` 查询配置，不需要 `Authorization` 请求头。接口沿用 `Response` 包装，例如 `{"success":true,"message":"ok","data":{"defaultTab":"experience"}}`，并返回 `Cache-Control: no-store`。仅新增接口，不修改既有登录、注册或预检接口，旧版小程序可继续正常使用。

`WEFOLIO_LOGIN_DEFAULT_TAB=maintainer` 将普通入口默认标签切换为“登录 / 注册”；未设置、空值或未知值均回退“体验”，取值严格区分大小写。更新 Runtime 环境配置并重启后，用户重新进入登录页时读取新配置。新版小程序遇到旧后端的 404、请求失败或无效配置时保留“体验”；有效推荐码入口仍优先展示“登录 / 注册”，迟到的配置响应不会覆盖用户已手动选择的标签。

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
- 问题反馈状态接口已校验 `X-Admin-Point-Secret`；包含完整共享密钥的飞书卡片仅在授权团队范围内流转，未进入公开日志或外部工单。
- 作品人工审核回传接口已校验 `X-Admin-Point-Secret`；包含完整共享密钥的飞书卡片和审核命令仅在授权审核群内流转。
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
| 2026-08-27 | Codex | 为问题反馈和作品人工审核回传复用 `ADMIN_POINT_SECRET`，飞书 curl 携带完整认证头 | Runtime 双节点、内部调用方与飞书审核流程 | 回滚应用版本；如已轮换密钥，需同步恢复所有调用方配置 |
| 2026-09-18 | Codex | 增加作品集小程序码 COS 目录、手机合成、环境变量及 CDN/真机验收要求 | Runtime 双节点、COS/CDN、微信小程序 | 尚未执行线上配置变更；应用回滚不应删除既有作品集素材 |
| 2026-06-19 | 待补充 | 服务器1 启用 SSH 密钥登录并关闭密码登录 | SSH 登录方式 | 服务器备份文件：`/etc/ssh/sshd_config.bak-20260619-before-disable-passwordauth`；恢复后执行 `sshd -t` 并重载 `sshd` |
| 2026-06-19 | 待补充 | 初始化技术信息与服务配置台账 | 文档模板 | 不涉及 |
| 2026-09-21 | Codex | 两台 Runtime 服务器安装独立 Python 3.12.14、FontTools 4.59.0、HarfBuzz 14.2.1；固定 66 个依赖并完成九份 WOFF 一致性验收 | `/opt/wefolio/font-runtime`；系统 Python、现有应用进程与配置未改动 | 本次尚未被应用引用，可移除新环境；后续接入后须先迁移应用配置。锁文件可用于重建 |

| 2026-09-22 | Codex | 发布九份 Mock 字体和六张样张，增加 WOFF CORS；最终 JAR 双节点隔离自检通过 | CDN 静态对象及 WOFF 响应头；未部署 Runtime/Job 或迁移数据库 | 可移除本次新增的字体 CORS 规则；已被客户端引用的不可变静态对象不应直接删除；隔离验收临时文件已清理 |

| 2026-09-23 | Codex | 按授权完成 Runtime 双节点 disabled 滚动发布、V59 迁移、Job 单节点发布及历史目录补建；三服务健康 UP | Runtime 双节点、Job、Nginx、数据库新增列及 52 个 COS 空目录 | Runtime 脚本保留旧 JAR；Job 保留 pre-font-20260923 备份。恢复应用前检查配置兼容；保留新增数据库列和已补空目录 |

| 2026-09-23 | Codex | Runtime 双节点进入 prevalidated 阶段，启动字体自检通过，ready=true、enabled=false；恢复双节点轮询 | Runtime 双节点及字体自检工作目录 | 如需停用自检，按 disabled 阶段滚动发布；保留现有字体资源和数据库列 |

| 2026-09-23 | Codex | 两 Runtime 字体已 enabled 且 ready；按用户要求移除部署脚本字体阶段和配置写入，普通发布仅检查服务级健康 | Runtime 双节点、部署脚本及测试；现有字体配置保留 | 字体开关由服务器独立配置；部署脚本保留原 JAR 备份和切流保障 |
