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

### 4.10 高级作品集后端发布（2026-10-09 12:49—12:55）

按用户授权，依次执行 Runtime 发布脚本、服务器检查脚本、Job 发布脚本，最后再次检查服务器及正式 HTTPS 入口。发布时间均为上海时间；本次从当前未提交工作区构建，版本字段仍是 HEAD `0bf1880`，需结合构建时间及 JAR 摘要识别实际制品。

| 服务 | 节点 | 构建时间 | JAR SHA-256 | 最终 PID |
| --- | --- | --- | --- | --- |
| Runtime | 老节点 `49.235.146.161`、新节点 `124.222.148.233` | `2026-10-09 12:49:32` | `e1296269795899f2209053bf0a872a75488b3a0b17ceccb40a973ecf98e60d3c` | `23224` / `3581` |
| Job | 老节点 `49.235.146.161` | `2026-10-09 12:53:40` | `4d6cf9307ff416e41bf079a5ee1fc589f61813aa903cfe23425b44090ba573c6` | `24761` |

- Runtime 双节点滚动发布退出码 0，耗时 100.1 秒，两个远端 JAR 与本地摘要相同，Nginx 已恢复 round-robin 且 active。
- 老节点启动日志确认 Flyway 从 V60 成功执行 V61，新节点启动确认当前 V61；迁移前零数据守卫未阻断。V61 已实际执行，此后禁止修改该脚本。
- Job 发布前额外保存 `/root/java/job/wefolio-java-job.jar.backup-advanced-20261009125324`，原包 SHA-256 为 `cd7ade5f2c47ab590f3bc4435441308b8bdaa5eb9415020a51933c6179adbfbb`。旧调度停用时 `activeTaskCount=0`，随后发布退出码 0、耗时 18.2 秒。Runtime 旧包由脚本保留在两节点 `/root/java/wefolio-java-runtime.jar.backup`。
- 用户预先配置的 `WEFOLIO_AI_ENABLED=true` 已进入两台 Runtime 和 Job 实际进程；Job API Key 非空，老节点 Runtime/Job 的模型根地址相同。未输出凭据，未变更服务器环境配置；Runtime SQL 调试日志开关未设置，沿默认 false。
- 发布前后两次 `scripts/server-status.sh` 均退出 0。最终三服务均 active、健康 UP，两台 Runtime 字体 enabled/ready 均为 true；按脚本规则扫描的最近 24 小时错误日志匹配数均为 0。
- 正式 HTTPS `/api/health`、`/api/version`、`/job-api/health`、`/job-api/version` 均验证成功，构建时间与上述一致。

本次未发布小程序、未提交 Git，也未主动创建真实 AI 任务或完成模型/小程序端到端验收。应用恢复前须核查新产生的高级数据与 V61 表结构兼容性，不自动回退数据库。

本地日志：`/private/tmp/wefolio-runtime-deploy-20261009.log`、`/private/tmp/wefolio-server-status-after-runtime-20261009.log`、`/private/tmp/wefolio-job-deploy-20261009.log`、`/private/tmp/wefolio-server-status-final-20261009.log`。服务器检查日志仅保留错误数量及异常类型，不记录业务错误原文。

### 4.11 高级作品集 AI 模式清理后端发布（2026-10-10）

按用户授权，依次执行 Runtime 发布脚本、Job 发布脚本和 `scripts/server-status.sh`，再独立核对线上制品及正式 HTTPS 入口。以下时间均为上海时间；本次从未提交工作区构建，版本字段仍为 HEAD `0bf1880`，实际制品以构建时间和 SHA-256 识别。

| 服务 | 发布脚本开始 | 构建时间 | JAR SHA-256 | 最终 PID / 启动时间 |
| --- | --- | --- | --- | --- |
| Runtime 双节点 | `2026-10-10 09:05:54` | `2026-10-10 09:05:56` | `bd348187df409be8e08f189e4d34c8b118b4c4ddacd5661ee79294dde5d81fb8` | 老节点 `8075` / `09:06:40`；新节点 `13370` / `09:07:23` |
| Job 老节点 | `2026-10-10 09:09:13` | `2026-10-10 09:09:14` | `0a6da49b363366389bae07d13ef0bef9a241a38b4b787098a041a9e855992a53` | `9196` / `09:09:31` |

- Runtime 双节点滚动发布退出码 0，总耗时 105.804 秒；两个远端 JAR 摘要均与本地相同。Nginx active，Runtime upstream 的两个 server 均无 `down` 标记，已移除临时 `least_conn` 并恢复默认 round-robin。
- Job 发布退出码 0，总耗时 18.621 秒。发布前已额外保存远端旧包 `/root/java/job/wefolio-java-job.jar.backup-20261010-before-release`，旧包 SHA-256 为 `4d6cf9307ff416e41bf079a5ee1fc589f61813aa903cfe23425b44090ba573c6`；停用旧调度时 `activeTaskCount=0`，随后重启成功。
- `scripts/server-status.sh` 退出码 0，两个 Runtime 与 Job 均 active、健康 UP；两台 Runtime 的字体 enabled/ready 均为 true。
- 按脚本规则扫描最近 24 小时错误日志：两个 Runtime 各匹配 2 条，仅为 `2026-10-09 16:10:08` / `16:10:36` 的 `GlobalExceptionHandler` 与 `BusinessException` 历史记录；本次发布后未检出新的匹配，Job 匹配 0 条。保留的检查日志已过滤业务错误原文。
- 独立检查确认线上 JAR SHA-256、版本与构建时间均匹配本地制品；正式 HTTPS `/api/health`、`/api/version`、`/job-api/health`、`/job-api/version` 四个入口均验证成功。Job 包内仅保留 `CREATE`、`MODIFY_PAGE`、`system`、`retry` 提示词及当前契约，没有已取消模式的旧模板。

| 检查指标 | 老节点 `49.235.146.161` | 新节点 `124.222.148.233` |
| --- | --- | --- |
| CPU 使用率 | 0.8% | 1.7% |
| 内存使用率 | 35.8% | 33.3% |
| 磁盘使用率 | 19% | 11% |

本次未修改服务器环境配置、未新增数据库迁移，既有已执行 V61 未修改；未发布小程序、未调用真实 AI、未提交 Git。恢复应用前仍须核查当前高级数据与 V61 表结构兼容性，不自动回退数据库。

本地证据：`/private/tmp/wefolio-runtime-deploy-20261010.log`、`/private/tmp/wefolio-job-deploy-20261010.log`、`/private/tmp/wefolio-server-status-20261010.log`、`/private/tmp/wefolio-new-node-verification-20261010.json`。敏感输出未保留，服务器错误检查仅留数量、时间和异常类型。

### 4.12 Job 字体选用上下文发布（2026-10-12）

按用户授权，仅发布 Job 单节点，将系统默认、微信字体及六款远程字体的语言适用性、风格和用途加入生产 AI 系统提示词。以下时间均为上海时间；本次从未提交工作区构建，版本字段仍为 HEAD `3775bf4`，实际制品以构建时间和 SHA-256 识别。

- 发布脚本退出码 0，总耗时 19.665 秒；旧调度停用返回 `DISABLED`、`activeTaskCount=0` 后重启。新进程 PID `8265`，启动时间 `2026-10-12 18:10:36`，服务 active。
- 构建时间 `2026-10-12 18:10:19`；本地与线上 JAR SHA-256 均为 `a16398210312fca6316fdf6468451f671d848185408ada7303c0d1c586b3217f`。包内 `portfolioai/system.txt` 与本地源文件一致，含八款字体指南；线上与本地提示词 SHA-256 均为 `7959c3f458a5b77619345a97c4580074f5849ffad6f63f994686db86f2dd69e6`。
- 服务器本机及正式 HTTPS 的 `/job-api/health` 均返回 UP；`/job-api/version` 的版本与构建时间均匹配本地制品。发布前 Job 全量测试 735 项，零失败/错误，2 项远端 COS 集成测试跳过。
- 旧包备份为 `/root/java/job/wefolio-java-job.jar.backup-font-guidance-20261012180956`，SHA-256 `c8895964b9700c738ffea44465b3ed3569e75e79da54b307ac4fcab0e4ca3e19`，原构建时间 `2026-10-12 16:21:21`。
- 只读日志对照发现历史 Snapshot 终态清理补偿的 WARN 及 `org.apache.http.NoHttpResponseException` 的 INFO 级连接重试在发布前已反复出现，发布后仍有同类记录；失败时保留历史截图供后续清理，服务探活正常。新实例日志未检出 ERROR/FATAL。检查只保留时间、类型及分类计数，未保留业务日志原文；分类证据为 `/private/tmp/wefolio-job-font-guidance-journal-summary.json`。

本次未发布 Runtime 或小程序，未修改服务器环境配置、数据库或 Git 提交。发布日志：`/private/tmp/wefolio-job-deploy-font-guidance-20261012180956.log`；本地制品及 HTTPS 验证证据：`/private/tmp/wefolio-job-font-guidance-local-artifact.json`、`/private/tmp/wefolio-job-font-guidance-public-verification.json`。

### 4.13 高级作品集超时调整后端发布（2026-10-13）

按用户授权，依次执行 Runtime 双节点滚动发布和 Job 单节点发布，将高级作品集总期限、单次模型调用期限和恢复窗口调整上线，详情见 §7.1。以下时间均为上海时间；本次从未提交工作区构建，两制品版本字段仍为 HEAD `3775bf4`，实际制品以构建时间和 SHA-256 识别。

| 服务 | 构建时间 | JAR SHA-256 | 最终 PID / 启动时间 |
| --- | --- | --- | --- |
| Runtime 双节点 | `2026-10-13 08:23:50` | `e629c6fe5e5505ba188b6140a65a7ffb18b286fbb764f8f75017cadfb3d524fa` | 老节点 `8953` / `08:24:31`；新节点 `22402` / `08:25:12` |
| Job 老节点 | `2026-10-13 08:25:54` | `f8ce58862c8590bc87f7b4cd33868d70eb83fc6d06a6dbbba78e0312c5563a13` | `9814` / `08:26:12` |

- Runtime 双节点滚动发布退出码 0，总耗时 102.650 秒；Job 发布退出码 0，总耗时 19.832 秒。Job 旧调度停用返回 `DISABLED`、`activeTaskCount=0` 后重启。
- 两台 Runtime 的旧包由脚本保留在 `/root/java/wefolio-java-runtime.jar.backup`，旧包 SHA-256 均为 `5c058eed5bd37afb0122f42f27d96be731f97798d3315330baf3aa605d628444`；Job 发布前另存 `/root/java/job/wefolio-java-job.jar.backup-timeout-20261013-before-release`，旧包 SHA-256 为 `a16398210312fca6316fdf6468451f671d848185408ada7303c0d1c586b3217f`。
- 发布后独立只读核验退出码 0，`verified=true`。两个 Runtime 与 Job 均 active、健康 UP，线上 JAR 摘要、版本及构建时间与本地制品一致；正式 HTTPS `/api/health`、`/api/version`、`/job-api/health`、`/job-api/version` 四个入口均验证成功。两台 Runtime 字体 enabled/ready 均为 true。
- 有效期限配置来源均为制品内 `application.yml`：两个 Runtime 总期限均为 600 秒；Job 总期限 600 秒、单次 HTTP 180 秒、恢复窗口 240 秒。未检出期限环境变量、命令行、JVM、Spring JSON 或外部应用配置覆盖。
- Nginx active，Runtime upstream 两个预期节点均无 `down` 标记，已恢复默认 round-robin。仅扫描各服务本次启动后、当前 PID 的 ERROR/FATAL 日志，三服务匹配数均为 0；核验只保留分类计数，不记录业务日志原文。
- 发布前 Runtime 全量测试 2580 项，零失败/错误，30 项跳过；Job 全量 743 项，零失败/错误，2 项远端 COS 集成测试跳过；小程序高级 AI 页面及协议测试 53 项全部通过。

本次未修改服务器环境配置，未新增数据库迁移，未发布小程序、未提交 Git。已失败的任务不自动恢复，需要重新提交生成；本次发布核验未验证新的真实 AI 任务结果。恢复应用前须核查当前任务与既有表结构兼容性，不自动回退数据库。

本地证据：`/private/tmp/wefolio-runtime-deploy-timeout-20261013.log`、`/private/tmp/wefolio-job-deploy-timeout-20261013.log`、`/private/tmp/wefolio-ai-postdeploy-verification.json`。测试日志：`/private/tmp/wefolio-timeout-runtime-full-unrestricted.log`、`/private/tmp/wefolio-timeout-job-full-final.log`、`/private/tmp/wefolio-timeout-miniapp.log`。

### 4.14 高级作品集输出预算与思考深度修复发布（2026-10-13）

用户明确批准本轮生产发布后，依次执行 Job 单节点发布和 Runtime 双节点滚动发布，将 §7.2 的输出截断修复上线。以下时间均为上海时间；从未提交工作区构建，版本字段仍为 HEAD `3775bf4`，实际制品以构建时间和 SHA-256 识别。§4.13 保留为本日此前超时调整的历史发布记录。

| 服务 | 构建时间 | JAR SHA-256 | 最终 PID / 启动时间 |
| --- | --- | --- | --- |
| Job 老节点 | `2026-10-13 09:05:14` | `91a59ec83a92cd01bdc9338f98f031a8623647e1f602c8767b4cb1f582097dfd` | `19144` / `09:05:31` |
| Runtime 双节点 | `2026-10-13 09:06:25` | `5b3f0f94d2a5423579fd31faa1b138fd763646e4878e392882474f92fa316753` | 老节点 `19700` / `09:07:07`；新节点 `32054` / `09:07:46` |

- Job 发布脚本退出码 0，耗时 18.679 秒；Runtime 滚动发布脚本退出码 0，耗时 101.431 秒。
- 两台 Runtime 旧包备份 `/root/java/wefolio-java-runtime.jar.backup` 已独立核验，SHA-256 均为 `e629c6fe5e5505ba188b6140a65a7ffb18b286fbb764f8f75017cadfb3d524fa`；Job 旧包备份 `/root/java/job/wefolio-java-job.jar.backup-output-20261013-before-release` 已核验，SHA-256 为 `f8ce58862c8590bc87f7b4cd33868d70eb83fc6d06a6dbbba78e0312c5563a13`。
- 发布后独立只读核验 `verified=true`。两台 Runtime 和 Job 均 active、健康 UP，线上制品摘要、版本与构建时间均匹配本地包；正式 HTTPS `/api/health`、`/api/version`、`/job-api/health`、`/job-api/version` 四个入口均通过。
- 有效配置来源均为制品内 `application.yml`，无显式覆盖：两台 Runtime 总期限 600 秒、新任务页面输出预算 16384、思考深度 `high`；Job 总期限 600 秒、单次调用 180 秒、恢复窗口 240 秒。历史任务继续使用冻结参数，缺少思考深度字段时继续省略，不将旧任务改按新默认值发送。
- Nginx active，Runtime upstream 两个预期节点均无 `down`，恢复默认 round-robin。逐服务检查当前 PID 启动后的 journal 与实际 application 日志文件，三服务新增 ERROR/FATAL 匹配数均为 0；仅保留脱敏分类计数，不保存业务日志原文。
- 发布前 Runtime 全量 2589 项、跳过 30 项，Job 全量 763 项、跳过 2 项，均零失败/错误；小程序相关测试 60 项全部通过。制品均构建成功。

本次未修改服务器环境配置，未新增数据库迁移，未发布小程序、未提交 Git。发布及有效配置核验不代表新的真实 AI 任务已通过业务验收；§7.2 第二次模型复测的协议验证失败限制继续保留。已失败任务不会自动恢复，需重新提交生成；恢复应用前核查当前任务与既有表结构兼容性，不自动回退数据库。

本地证据：`/private/tmp/wefolio-output-postdeploy-verification.json`、`/private/tmp/wefolio-output-backup-verification.json`、`/private/tmp/wefolio-output-ready-artifacts.json`。

### 4.15 高级作品集行高规范化修复发布（2026-10-13）

按用户授权完成 Runtime 双节点滚动发布，上线 §7.3 的行高规范化保护修复；本次未部署 Job。以下时间均为上海时间；Runtime 从未提交工作区构建，版本字段仍为 HEAD `3775bf4`，实际制品以构建时间与 SHA-256 识别。

| 服务 | 构建时间 | JAR SHA-256 | 最终 PID / 启动时间 |
| --- | --- | --- | --- |
| Runtime 双节点 | `2026-10-13 09:43:20` | `434ccb85c240c2aa40a9fb6653e22c8f21496b68784b765e8d99e21990dd4913` | 老节点 `28786` / `09:44:00`；新节点 `7994` / `09:44:42` |
| Job 老节点（沿用 §4.14） | `2026-10-13 09:05:14` | `91a59ec83a92cd01bdc9338f98f031a8623647e1f602c8767b4cb1f582097dfd` | `19144` / `09:05:31` |

- Runtime 发布脚本退出码 0，总耗时 103.512 秒。
- 两台 Runtime 旧包备份 `/root/java/wefolio-java-runtime.jar.backup` 已独立核验，SHA-256 均为 `5b3f0f94d2a5423579fd31faa1b138fd763646e4878e392882474f92fa316753`。
- 发布后独立只读核验 `verified=true`：两台 Runtime 与 Job 均 active、健康 UP，制品摘要、版本及构建时间匹配本地包；正式 HTTPS `/api/health`、`/api/version`、`/job-api/health`、`/job-api/version` 四个入口通过。
- 有效配置仍来自制品内 `application.yml`，无显式覆盖：Runtime 总期限 600 秒、页面输出预算 16384、思考深度 `high`；Job 总期限 600 秒、单次调用 180 秒、恢复窗口 240 秒。Nginx active，双节点恢复默认 round-robin。
- 按当前 PID 启动时间扫描 journal 与实际 application 日志文件，三服务新增 ERROR/FATAL 匹配数均为 0，仅保留脱敏分类计数。
- 对真正已部署的 Runtime JAR 只读回放任务 `8` 的原保存结果，不使用 class 叠加。原 AI 中间协议编译通过，共 4 个组件；候选中只有一个显式行高，为整数 `2`，单独使用普通行高规则规范化为同值 `2.0`，部署版本保护接受该精确数值等价变化，`verified=true`。原保存中间结果未修改，没有新的模型调用；未对原结果运行完整普通配置规范化或渲染，该验证只证明对应行高保护，不声称完整渲染、采用、保存或发布链已通过。
- 发布前 Runtime 全量 2602 项、零失败/错误、跳过 30 项，定向测试 112 项及小程序相关 43 项通过，`clean package` 退出码 0。

本次没有新增 Runtime 终态普通配置校验向模型反馈的完整修复链路，现有边界继续见 §7.3。本次未发布 Job 或小程序，未修改数据库或新增迁移，未提交 Git。恢复应用前核对当前任务和既有表结构兼容性，不自动回退数据库。

本轮服务器私有临时目录 `/tmp/wefolio-normalization-20261013` 已清理，任务 `8` 原数据库结果未修改，本地脱敏证据保留：`/private/tmp/wefolio-task8-postdeploy-verification.json`、`/private/tmp/wefolio-task8-normalization-replay-deployed.json`、`/private/tmp/wefolio-task8-backup-cleanup-verification.json`。

### 4.16 高级作品集 32K 输出预算发布（2026-10-13）

按用户明确要求，将最终页面输出预算从 16384 扩大到 32768，先完成 Job 单节点发布及独立核验，再滚动发布 Runtime 双节点。以下时间均为上海时间；制品从未提交工作区构建，版本字段仍为 HEAD `3775bf4`，实际制品以构建时间与 SHA-256 识别。此前发布记录保留，当前配置以本节及 §7.4 为准。

| 服务 | 构建时间 | JAR SHA-256 | 最终 PID / 启动时间 |
| --- | --- | --- | --- |
| Job 老节点 | `2026-10-13 10:30:47` | `554fa2345725f4e6d8c01349d82e0506a2e631578ee6a2287e218d086293e028` | `7559` / `10:31:06` |
| Runtime 双节点 | `2026-10-13 10:32:33` | `f1119ed7aef10e6ca2245587045e29b789394660243d2393c8a14015170cd733` | 老节点 `8261` / `10:33:13`；新节点 `18942` / `10:33:50` |

- Job 发布脚本退出码 0，总耗时 19.957 秒；Runtime 双节点滚动发布脚本退出码 0，总耗时 95.869 秒。
- Job 旧包备份为 `/root/java/job/wefolio-java-job.jar.backup-token32k-20261013-before-release`，SHA-256 `91a59ec83a92cd01bdc9338f98f031a8623647e1f602c8767b4cb1f582097dfd`；两台 Runtime 旧包均保留在 `/root/java/wefolio-java-runtime.jar.backup`，SHA-256 均为 `434ccb85c240c2aa40a9fb6653e22c8f21496b68784b765e8d99e21990dd4913`。三份备份独立核验 `verified=true`。
- 发布后独立只读核验 `verified=true`：两个 Runtime 与 Job 均 active、健康 UP；实际部署 JAR 摘要、版本及构建时间匹配本地制品。包内 Runtime / Job 的 `MAX_OUTPUT_TOKENS` 均核验为 32768，线上摘要与对应已检包相同。
- 两台 Runtime 的有效 `max-tokens=32768`、`reasoning-effort=high`、总期限 600 秒；Job 总期限 600 秒、单次调用 180 秒、恢复窗口 240 秒。来源均为制品内 `application.yml`，未检出环境、命令行、JVM、Spring JSON 或外部应用配置覆盖。费用限制沿原配置保留，本轮未独立核验其线上覆盖值。
- 正式 HTTPS `/api/health`、`/api/version`、`/job-api/health`、`/job-api/version` 四个入口通过；Nginx active，两个预期 Runtime 节点均无 `down`，已恢复默认 round-robin。按当前 PID 启动时间扫描 journal 与实际 application 日志文件，三服务新增 ERROR/FATAL 匹配数均为 0，仅保留脱敏分类计数。
- 发布前 Runtime 全量 2603 项、跳过 30 项，Job 全量 765 项、跳过 2 项，均零失败/错误；相关 Runtime 98 项、Job 186 项全部通过。两模块完整测试打包成功。

本次未修改服务器环境配置，未新增数据库迁移，未发布小程序、未提交 Git；没有新模型调用，也未提交新的真实 AI 任务验证完整候选、渲染、采用、保存或发布链。新默认值只影响后续创建任务，历史任务保留冻结参数。回退顺序及费用预留边界见 §7.4。

本地脱敏证据：`/private/tmp/wefolio-token32k-job-postdeploy-verification.json`、`/private/tmp/wefolio-token32k-postdeploy-verification.json`、`/private/tmp/wefolio-token32k-backup-verification.json`；部署日志：`/private/tmp/wefolio-token32k-job-deploy.log`、`/private/tmp/wefolio-token32k-runtime-deploy.log`；测试打包日志：`/private/tmp/wefolio-token32k-runtime-package-full.log`、`/private/tmp/wefolio-token32k-job-package-full.log`。

### 4.17 高级作品集 Token 用量查询 Runtime 发布（2026-10-13）

用户选择用量展示 A 方案后，明确要求发布 Runtime。本次仅发布 Runtime 双节点；任务查询在请求人与作品集维护权校验后追加可选 `usage`，只返回回执完整性和已知输入＋输出累计 token。历史无账本或未发送占位不显示用量；部分未知保留已知累计，损坏或溢出仅降级用量，不影响原候选查询。提交、取消与能力查询契约保留。

| 服务 | 构建时间 | JAR SHA-256 | 最终 PID / 启动时间 |
| --- | --- | --- | --- |
| Runtime 双节点 | `2026-10-13 13:48:44` | `48a29287b9737469775c15a19d17d8db79ef291df47dcdbaaf3ab0a506594edc` | 老节点 `20745` / `13:49:25`；新节点 `29915` / `13:50:03` |

时间均为上海时间，版本字段仍为 HEAD `3775bf4`，制品以构建时间与 SHA-256 识别。标准发布脚本重新打包同一已测源码，退出码 0，总耗时 98.825 秒。两台旧包保留在 `/root/java/wefolio-java-runtime.jar.backup`，独立核验 SHA-256 均为此前线上包 `f1119ed7aef10e6ca2245587045e29b789394660243d2393c8a14015170cd733`。

- 发布后独立只读核验退出码 0、`verified=true`：两个 Runtime 均 active、健康 UP，实际包 SHA、版本与构建时间匹配本地最终制品；正式 HTTPS `/api/health`、`/api/version` 通过。
- Nginx active，两个预期节点均无 `down`，已恢复默认 round-robin。按当前 PID 启动时间扫描 journal 与实际 application 日志，新增 ERROR/FATAL 匹配数均为 0，证据只保留脱敏分类计数。
- 有效默认配置保持页面输出 32768、思考深度 `high`、总期限 600 秒；独立核验来源为制品内 `application.yml`，未检出显式覆盖。
- 发布前 Runtime 相关测试 87 项通过；完整测试打包 2635 项，零失败/错误、跳过 30 项，退出码 0。小程序相关及分包测试 79 项、完整 2660 项全部通过，已覆盖旧响应兼容与旧查询/计时器恢复已放弃结果的回归。

本次未发布小程序，因此 A 方案小字展示需小程序发布后生效。Job、数据库和 32K 配置发布记录沿用 §4.16 / §7.4；未新增迁移或真实模型调用，未提交 Git。仅核验发布与只读运行状态，未新建真实 AI 任务或读取用户账本，不据此声称完整业务生成链已验收。

本地脱敏证据：`/private/tmp/wefolio-token-usage-postdeploy-verification.json`；发布日志：`/private/tmp/wefolio-token-usage-runtime-deploy.log`；完整测试打包日志：`/private/tmp/wefolio-token-usage-runtime-package-full.log`；设计与实现记录：`docs/advanced-portfolio-token-usage-a.md`。

### 4.18 高级作品集素材绑定反馈 Runtime 发布（2026-10-13）

按用户明确要求，仅执行 Runtime 双节点滚动发布，部署共享协议中的主素材及资源槽位错误定位与纠正摘要。制品从当前未提交工作区构建，版本字段仍为 HEAD `3775bf4`，以构建时间及 SHA-256 识别实际包；不改变素材绑定拒绝规则、任务错误码及小程序固定错误映射。

| 服务 | 构建时间（上海） | JAR SHA-256 | 最终 PID / 启动时间（上海） |
| --- | --- | --- | --- |
| Runtime 双节点 | `2026-10-13 16:02:49` | `4206d24ba7875f0d8cf91022f0dccc09088c37d0245a3d259dbd51c0be947506` | 老节点 `19359` / `16:03:33`；新节点 `27235` / `16:04:11` |

- 标准部署脚本退出码 0，总耗时 104.543 秒。两节点线上包及关键反馈 class 摘要与本地最终制品一致；旧包均保留于 `/root/java/wefolio-java-runtime.jar.backup`，独立核验 SHA-256 为 `48a29287b9737469775c15a19d17d8db79ef291df47dcdbaaf3ab0a506594edc`。
- 独立白名单元数据验收退出码 0、`verified=true`：两台 Runtime active、健康 UP、字体 enabled/ready；正式 HTTPS `/api/health`、`/api/version` 通过。Nginx active、语法检查通过，两个预期 Runtime 节点恢复默认 round-robin。
- 按当前 PID 启动时间统计 journal 及实际 application 日志，两个节点新增 ERROR/FATAL 匹配数均为 0；仅保留数量与部署元数据，不保存业务日志原文。两节点启动均校验 63 份 Flyway migration，当前 schema 为 V63、已是最新，本次没有执行新迁移；此前发布记录的 V61 为对应时点历史状态。
- 发布前 Runtime 全量 2643 项，零失败/错误、跳过 30 项；Job 本地全量 777 项，零失败/错误、跳过 2 项；相关小程序 54 项通过。新增回归覆盖错误组件/操作/字段定位、背景槽位修正、内部节点与空主素材语义，以及安全摘要写入重试并进入下一次模型请求。

本次没有发布 Job。Job 实际 PID 仍为 `7559`、启动时间 `2026-10-13 10:31:06`，JAR SHA-256 仍为 `554fa2345725f4e6d8c01349d82e0506a2e631578ee6a2287e218d086293e028`。新生成规则、完整绑定示例及模型重试反馈运行在 Job 中，须发布 Job 后才完整生效；Runtime 单独发布不构成原任务 10 失败已在线上解决的验收。本次未改服务器环境、未新增 migration、未发布小程序、未提交 Git，也未发起真实 AI 任务或收费模型复测。

本地脱敏证据：`/private/tmp/wefolio-binding-runtime-artifact.json`、`/private/tmp/wefolio-binding-runtime-postdeploy-verification.json`；发布日志：`/private/tmp/wefolio-binding-runtime-deploy.log`；验证日志：`/private/tmp/wefolio-binding-runtime-tests-unrestricted.log`、`/private/tmp/wefolio-binding-job-tests.log`、`/private/tmp/wefolio-binding-miniapp-tests.log`。

### 4.19 高级作品集缓存优化与绑定规则 Runtime / Job 发布（2026-10-13）

用户明确要求修改完成后部署最新 Runtime 和 Job。本次从当前已验证的未提交工作区构建并发布 Runtime 双节点及 Job；Job 同时上线稳定 `prompt_cache_key`、缓存用量安全日志、主素材/绑定槽位错误定位、加强的生成规则及完整绑定示例。版本字段仍为 HEAD `3775bf4`，以构建时间及 SHA-256 识别实际制品。

| 服务 | 构建时间（上海） | JAR SHA-256 | 最终 PID / 启动时间（上海） |
| --- | --- | --- | --- |
| Runtime 双节点 | `2026-10-13 17:32:34` | `1df120a6f11ce87578daed77fdc350065b8eb638bec136ddc89c12353fc2fabc` | 老节点 `8121` / `17:33:14`；新节点 `14814` / `17:33:56` |
| Job 老节点 | `2026-10-13 17:35:53` | `0ce3ede6440bc736bb273faa99e159b2089e5a61cefaebbd61d7a054dfbaa789` | `9294` / `17:36:11` |

- Runtime 标准双节点滚动发布退出码 0，总耗时 100.506 秒，两个节点恢复默认 round-robin；两台旧包均保留于 `/root/java/wefolio-java-runtime.jar.backup`，独立核验 SHA-256 为上一版本 `4206d24ba7875f0d8cf91022f0dccc09088c37d0245a3d259dbd51c0be947506`。
- Job 沿用仓库发布脚本，通过本地临时封装在替换前先调用现有调度停用接口排空，返回 HTTP 200、DISABLED、activeTaskCount=0；再校验上传摘要、保留独立旧包并原子替换。部署退出码 0，总耗时 20.011 秒。旧包为 `/root/java/job/wefolio-java-job.jar.backup-cache-20261013-before-release`，独立核验 SHA-256 为 `554fa2345725f4e6d8c01349d82e0506a2e631578ee6a2287e218d086293e028`；仓库部署脚本未改动。
- 独立白名单元数据验收在两个服务器均退出码 0、`verified=true`：三服务 active、健康 UP；实际包、关键 class、Job 提示词资源及构建版本匹配本地最终制品。Runtime 和 Job 的共享协议 class 摘要一致，Job 的缓存参数与命名空间常量存在于实际线上客户端 class。
- 正式 HTTPS `/api/health`、`/api/version`、`/job-api/health`、`/job-api/version` 全部通过。Nginx active、配置语法检查通过、两 Runtime 节点均参与轮询；Runtime 字体 enabled/ready。
- 按当前 PID 启动时间统计 journal 及实际 application 日志，三服务新增 ERROR/FATAL 匹配数均为 0。两 Runtime 均校验 63 份 migration，schema V63 已是最新，本次没有执行新迁移；Job 包内无 Flyway migration。
- 发布前 Runtime JDK21 完整回归 2643 项、0 失败/错误、30 跳过；Job 最新完整回归 780 项、0 失败/错误、2 跳过。缓存专项先 RED（4 处缺少 key），实现后 130 项全部通过，覆盖动态内容/重试保持 key、模式/团队契约分组、媒体独立分组、实际 HTTP 请求与字节预算。

稳定 key 按模型、调用阶段和基础静态提示词的 SHA256 生成；布局重试及媒体附加事实不打散该组，媒体摘要使用自己的实际 system。用户内容、签名地址、任务编号、重试次数与时间不进入 key，原 user HMAC 与消息语义保持。增加请求字节仍由原保守预算计入，不预判命中放宽预算。

本次发布已确认优化代码进入线上服务，未新建真实 AI 任务或追加付费缓存探针，因此实际业务缓存命中率和原失败描述的完整生成/采用链仍需后续真实任务观测。此前已授权的四次合成探针最高命中 4288/4316（99.35%），不能保证每次命中，也不能据此认定缺少 key 为历史零命中的唯一根因。本次未改服务器环境、未新增 migration、未发布小程序、未提交 Git。

本地白名单证据：`/private/tmp/wefolio-latest-release-manifest.json`、`/private/tmp/wefolio-cache-release-postdeploy-verification.json`；发布日志：`/private/tmp/wefolio-cache-release-runtime-deploy.log`、`/private/tmp/wefolio-cache-release-job-deploy.log`；测试日志：`/private/tmp/wefolio-cache-release-runtime-tests.log`、`/private/tmp/wefolio-cache-key-job-tests.log`；缓存调查与实现记录：`docs/design/advanced-portfolios/2026-10-13-tokenhub-cache-investigation.md`。

### 4.20 高级作品集封面与参数语义 Runtime / Job 发布（2026-10-13）

按用户“发布runtime和job”的授权，从已验证的当前未提交工作区依次发布 Runtime 双节点和 Job。上线作品集封面 `share.coverUrl` 语义、全部 18 类组件及全局设置的参数说明、作品静态图引用和个人作品封面独立 COS 快照。版本字段仍为 HEAD `3775bf4`，实际制品以构建时间与 SHA-256 识别。

| 服务 | 构建时间（上海） | JAR SHA-256 | 最终 PID / 启动时间（上海） |
| --- | --- | --- | --- |
| Runtime 双节点 | `2026-10-13 21:15:10` | `2cb2e458f48742a5fe6a318cf20eeb5321c4ebf1ab43b1790a80a7e32fd3b3b2` | 老节点 `26912` / `21:15:51`；新节点 `31938` / `21:16:30` |
| Job 老节点 | `2026-10-13 21:17:11` | `1a39a9b189010380767f8405d9cebd3369929e569bdc64df1522658fd209b92d` | `27788` / `21:17:30` |

- Runtime 标准滚动发布退出码 0，耗时 99.287 秒；两节点使用相同制品，Nginx 恢复双节点默认轮询。两个旧包保留于 `/root/java/wefolio-java-runtime.jar.backup`，独立核验摘要均为 §4.19 的 `1df120a6f11ce87578daed77fdc350065b8eb638bec136ddc89c12353fc2fabc`。
- Job 通过临时封装，在正式包替换前停用旧调度并确认 `DISABLED / activeTaskCount=0`，校验上传摘要、备份后原子替换并重启；退出码 0，耗时 20.157 秒。独立旧包 `/root/java/job/wefolio-java-job.jar.backup-cover-20261013-2115` 摘要为 §4.19 的 `0ce3ede6440bc736bb273faa99e159b2089e5a61cefaebbd61d7a054dfbaa789`。仓库部署脚本未改动。
- 独立验收退出码 0、`verified=true`：三服务 active/running、健康 UP，线上包及本次关键 class、Job 提示词、构建版本匹配本地制品；两端共享协议、契约与绑定文案摘要一致。
- 正式 HTTPS `/api/health`、`/api/version`、`/job-api/health`、`/job-api/version` 四个入口通过。Nginx active、语法有效；两 Runtime 字体 enabled/ready。按当前 PID 启动时间统计 journal 与实际 application 日志，三服务新增 ERROR/FATAL 数均为 0。
- 两 Runtime 均校验 63 份 migration，schema V63 已是最新，未执行新迁移；Job 不包含 Flyway migration。发布前完整 Runtime 回归 2704 项、零失败/错误、30 跳过；Job 786 项、零失败/错误、2 跳过；小程序相关回归 91 项通过。

本次未创建真实 AI 任务或执行实际封面 COS 复制验收；团队分享封面仍需手动上传素材，闲置或替换的 AI 图片快照目前不自动清理。未修改服务器环境、未发布小程序、未提交 Git。

本地证据：`/private/tmp/wefolio-cover-release-manifest.json`、`/private/tmp/wefolio-cover-release-postdeploy-verification.json`；发布日志：`/private/tmp/wefolio-cover-runtime-deploy.log`、`/private/tmp/wefolio-cover-job-deploy.log`；测试日志：`/private/tmp/wefolio-cover-runtime-tests.log`、`/private/tmp/wefolio-cover-job-tests.log`、`/private/tmp/wefolio-cover-miniapp-tests.log`。

### 4.21 高级作品集默认资料组件边界 Runtime / Job 修复发布（2026-10-13）

用户反馈 §4.20 发布后相同完整描述仍多出个人资料。线上原始任务取证未获自动审批，不能断言该次模型输出经过；本地红灯验证发现 CREATE 会把新建 `initial_profile` 投影为已有正文。修复 CREATE 模型投影为空正文，冻结事实与服务端基准保持原值；同步系统、CREATE、重试提示词及资料组件说明，明确完整布局不自行补入未要求的组件。MODIFY_PAGE 仍保留未要求删除的已有组件。

沿用用户本任务中 Runtime / Job 发布授权，完整回归后依次部署两模块。版本字段仍为 HEAD `3775bf4`，实际制品以构建时间及 SHA-256 识别。

| 服务 | 构建时间（上海） | JAR SHA-256 | 最终 PID / 启动时间（上海） |
| --- | --- | --- | --- |
| Runtime 双节点 | `2026-10-13 21:36:12` | `d2ee6d60ce31e8d629e1a55d039b96bca182d6f85b8d2bb299e16e7d8ee31335` | 老节点 `32417` / `21:36:56`；新节点 `4402` / `21:37:34` |
| Job 老节点 | `2026-10-13 21:38:11` | `bfc02e2002e1337fc0a3dff26b543d94449a495a9f446272b3950ed2204ede41` | `814` / `21:38:28` |

- Runtime 滚动发布退出码 0、101.749 秒，Nginx 恢复默认双节点轮询。两台旧包 `/root/java/wefolio-java-runtime.jar.backup` 独立核验摘要均为 §4.20 的 `2cb2e458f48742a5fe6a318cf20eeb5321c4ebf1ab43b1790a80a7e32fd3b3b2`。
- Job 先停用并确认 `DISABLED / activeTaskCount=0`，校验上传、备份、原子替换后重启；退出码 0、20.841 秒。独立备份 `/root/java/job/wefolio-java-job.jar.backup-profile-20261013-2136` 摘要为 §4.20 的 `1a39a9b189010380767f8405d9cebd3369929e569bdc64df1522658fd209b92d`。
- 最终只读验收退出码 0、`verified=true`：三服务 active/running、健康 UP，线上制品、关键 class、Job 提示词及构建版本与本地一致；两端共享协议/契约一致。正式 HTTPS 四个健康/版本入口通过，Nginx active/语法正常、两 Runtime 字体 ready。本次启动后 journal/application 的 ERROR/FATAL 计数均为 0。
- Runtime 完整回归 2710 项、0失败/错误、30跳过；Job 792 项、0失败/错误、2跳过。新增6项个人/团队 CREATE 与 MODIFY 兼容测试，并独立验证11项旧队列、显式资料、原位引用和删除行为。正确四组件计划经过真实后端与小程序采用/编辑/预览均不增加 PROFILE；原文合成客户端消息的当前正文组件数从1降到0，素材与资料句柄完整保留。
- 两 Runtime schema 仍 V63、校验63份 migration，无新迁移；Job 不含 Flyway。未修改服务器环境、未发布小程序、未提交 Git。

使用合成资料向 TokenHub 进行真实模型回归的调用也被自动审批拒绝，原因为发送内部提示词与契约需要明确授权；已提出最多2次调用的授权请求。本条发布验收仅证明修复制品上线和本地链路正确，不等同于真实模型生成验收。

本地证据：`/private/tmp/wefolio-profile-release-manifest.json`、`/private/tmp/wefolio-profile-release-postdeploy-verification.json`；发布日志：`/private/tmp/wefolio-profile-runtime-deploy.log`、`/private/tmp/wefolio-profile-job-deploy.log`；回归日志：`/private/tmp/wefolio-profile-runtime-full.log`、`/private/tmp/wefolio-profile-job-full.log`。本地诊断记录：`docs/advanced-portfolio-ai-global-settings-fix.md`。

### 4.22 默认资料组件边界的两次合成实模验收（2026-10-13）

用户明确批准向当前 TokenHub 最多调用2次，用公开原文、合成素材及内部提示词验证结果。已完成两次，没有自动重试或追加调用；不读生产数据库、不创建业务任务、不保存作品集，API密钥仅在本地内存用于鉴权。

| 样本 | 模型输入 | HTTP / 完成原因 | 耗时 | 输入 / 输出 tokens | 正文结果 |
| --- | --- | --- | --- | --- | --- |
| baseline | 修复前提示词、当前正文含 PROFILE | `200 / stop` | `19.738秒` | `14126 / 1082` | 文字、单作品、档期、留资；无PROFILE |
| fixed | §4.21 已上线提示词、空正文投影 | `200 / stop` | `8.582秒` | `14485 / 584` | 文字、单作品、档期、留资；无PROFILE |

模型为 `glm-5.3-flash`，temperature=1.0、reasoningEffort=high、maxTokens=16384。fixed使用的提示词/协议 class 与 §4.21 Job 制品一致。两份实际输出经生产协议校验、编译、普通草稿规范化与只读渲染均通过，封面1、背景2、远程字体引用、作品3、音乐4、黑底完整保留。隐藏合成夹具修正不改变模型投影或payload。

基线此次也未复现多加资料，不能据此证明此前真实任务的唯一原因或保证所有采样。作品/COS采用Mock，合成字体不存在真实文件，渲染仅验证引用保留、不证明字体视觉效果；没有进行生产任务取证或真实素材端到端验收。本轮未修改或重发应用代码。

证据：`/private/tmp/wefolio-profile-probe/comparison.json`、`baseline-report.json`、`fixed-report.json`、`baseline-validation.json`、`fixed-validation.json`及同目录实际编译/规范化/渲染结果。

### 4.23 最新工作区 Runtime / Job 重新发布（2026-10-13）

按用户“帮我发布最新runtime和job”的最新授权，从当前工作区重新构建并依次发布 Runtime 两节点及 Job。实际应用内容沿用 §4.21 的默认资料组件边界修复及 §4.22 已完成的合成实模验证；构建版本字段仍为 HEAD `3775bf4`，制品以构建时间与摘要区分。

| 服务 | 构建时间（上海） | JAR SHA-256 | 最终 PID / 启动时间（上海） |
| --- | --- | --- | --- |
| Runtime 双节点 | `2026-10-13 21:54:52` | `20614d7d41732502632f4056c8044c6399539378c9f4ee6367decd1f1965f098` | 老节点 `4903` / `21:55:35`；新节点 `8661` / `21:56:15` |
| Job 老节点 | `2026-10-13 21:57:00` | `795f1ca280859142f317eca6ec3a270049c83f88a0f5619e1f930979534c3586` | `5760` / `21:57:18` |

- Runtime 滚动发布退出码 0、104.332 秒，恢复双节点默认轮询；两台旧包备份 `/root/java/wefolio-java-runtime.jar.backup` 摘要均为 §4.21 的 `d2ee6d60ce31e8d629e1a55d039b96bca182d6f85b8d2bb299e16e7d8ee31335`。
- Job 在替换前停用并排空旧调度，确认 `DISABLED / activeTaskCount=0`；上传摘要校验、独立备份和原子替换后重启，退出码 0、21.262 秒。旧包 `/root/java/job/wefolio-java-job.jar.backup-latest-20261013-2154` 独立核验摘要为 §4.21 的 `bfc02e2002e1337fc0a3dff26b543d94449a495a9f446272b3950ed2204ede41`。
- 最终只读验收退出码 0、`verified=true`：三服务 active/running、健康 UP，实际包/关键 class/提示词/版本与本地新制品一致；两端共享协议、契约及文案一致。正式 HTTPS 四个健康/版本入口通过，Nginx active/语法正常、两节点轮询，Runtime 字体 ready。
- 三服务本次启动后的 journal/application ERROR/FATAL 数均为 0。Runtime schema 仍 V63，63份 migration 校验通过且无新迁移；Job 不含 Flyway。
- 精确对比 Runtime 1082、Job 293 个 `BOOT-INF/classes/` 与 `BOOT-INF/lib/` 条目，除 `version.properties` 构建元数据外，与 §4.21 验证包全部相同，无新增、删除或内容变化；源文件审计同样无变化。因此沿用 Runtime 2710项及 Job 792项完整回归结果，不重复运行相同测试。

本轮未新增模型调用、未读取生产任务数据、未修改服务器环境、未发布小程序、未提交 Git。

证据：`/private/tmp/wefolio-latest-publish-release-manifest.json`、`/private/tmp/wefolio-latest-publish-postdeploy-verification.json`、`/private/tmp/wefolio-latest-runtime-entry-comparison.json`、`/private/tmp/wefolio-latest-job-entry-comparison.json`、`/private/tmp/wefolio-latest-release-source-audit.json`；发布日志：`/private/tmp/wefolio-latest-publish-runtime-deploy.log`、`/private/tmp/wefolio-latest-publish-job-deploy.log`。

### 4.24 视频音频转写与原视频摘要 Runtime / Job 发布（2026-10-14）

按用户“发布runtime和job，同时检查服务器”的授权，从本轮已验证的工作区依次发布 Runtime 双节点与 Job；用户随后明确选择“发布并开启摘要功能”，因此先开启 Job 原视频摘要，再滚动开启两台 Runtime 的摘要读取。版本字段为 HEAD `96a0ef9`，摘要功能尚未提交 Git，实际制品以构建时间及 SHA-256 识别。独立音频转写仍关闭，未手动执行历史 backfill/rebuild 或新增真实模型测试。

| 服务 | 构建时间（上海） | JAR SHA-256 | 最终 PID / 启动时间（上海） |
| --- | --- | --- | --- |
| Runtime 双节点 | `2026-10-14 12:06:41` | `4b8fe8ae455da83ff0306bd6d22db1881e1ebe679ce2f0b3e278bb7652e52a9d` | 老节点 `9736` / `12:14:56`；新节点 `4391` / `12:15:10` |
| Job 老节点 | `2026-10-14 12:09:14` | `e1538d1da2c7f734b55ea2a3fddd3b9fee6f5c7f44217c9906db1f484eade237` | `9346` / `12:14:08` |

- Runtime 标准滚动发布退出码 0，耗时 98.741 秒；老节点执行 V64、V65，新节点确认 schema V65 已是最新；两节点均校验 65 份 migration。数据库只读验收确认 `video_audio_text` 为可空 MEDIUMTEXT、`video_summary_json` 为可空 JSON、默认 NULL，两个 VIDEO 类型 CHECK 均 enforced。
- Job 使用原部署脚本与本地临时安装包装，在正式包替换前完成上传 SHA 校验、旧实例排空（`DISABLED / activeTaskCount=0`）和独立备份，再原子替换、重启。退出码 0，耗时 21.853 秒。Job 不包含 Flyway migration。
- 两台 Runtime 旧包均保留在 `/root/java/wefolio-java-runtime.jar.backup`，SHA 为 `20614d7d41732502632f4056c8044c6399539378c9f4ee6367decd1f1965f098`；Job 旧包保留在 `/root/java/job/wefolio-java-job.jar.backup-video-summary-20261014-before-release`，SHA 为 `795f1ca280859142f317eca6ec3a270049c83f88a0f5619e1f930979534c3586`。三份备份均独立核验一致。
- 发布后两服务器只读验收均退出码 0、`verified=true`。三服务 active/running、健康 UP，正式 HTTPS 健康和版本入口均通过；线上 JAR、关键 class、提示词和构建元数据匹配本地制品。Nginx active、语法通过并恢复双节点轮询；Runtime 字体 enabled/ready。
- 三服务启用后本次启动的 application/journal ERROR/FATAL 计数均为 0。老/新节点根盘使用率分别约 18.8% / 9.7%，可用内存约 1.82 / 2.19 GiB，发布前无失败 systemd unit，当前负载正常。老节点 Runtime 和 Job 的 `is-enabled` 均为 `disabled`，新 Runtime 为 `enabled`；本次只记录现状，未修改开机自启设置。
- 摘要通过独立 systemd drop-in 启用：Job `/etc/systemd/system/wefolio-job.service.d/video-summary.conf` 设置 `WEFOLIO_VIDEO_SUMMARY_ENABLED=true`；两台 Runtime `/etc/systemd/system/wefolio.service.d/video-summary.conf` 设置 `WEFOLIO_AI_MEDIA_STORED_VIDEO_SUMMARY_ENABLED=true`。不含密钥，不修改既有 `.env`。Job 启用前再次排空至 0；Runtime 沿用摘流、重启、健康检查、恢复轮询流程，未重新替换 JAR 或覆盖发布前备份。
- 启用后有效配置检查覆盖进程环境、命令行/JVM 参数、Spring JSON 和外部 application 配置来源，确认两个摘要开关为 `true` 且无更高优先级冲突；`WEFOLIO_VIDEO_AUDIO_ENABLED`、Runtime `WEFOLIO_AI_MEDIA_AUDIO_TEXT_ENABLED` 仍为 `false`。v3 会使用已存在的转写文字，但本轮不生成独立 ASR 文字。TokenHub 凭据存在，仅检查存在性；Job 节点 PATH 中无 FFmpeg/FFprobe，后续启用独立 ASR 前仍须准备工具，原视频摘要不依赖它们。大小默认仍 100,000,000 字节，每轮费用预留 500 分、最多五次完整上下文预留。

发布沿用本轮最终 Runtime 2,765 项、Job 1,011 项、小程序 2,666 项无失败验证；本次未再改变应用源码，部署重新执行 `clean package -DskipTests`。未发布小程序、未提交 Git。回退时先把 Runtime 摘要开关设为 false 并滚动重启，排空 v3 任务后再回退 Job；保留 V64/V65 可空列，不回滚数据库。

脱敏证据：`/private/tmp/wefolio-video-summary-release-manifest.json`、`/private/tmp/wefolio-summary-old-verification.json`、`/private/tmp/wefolio-summary-new-verification.json`、`/private/tmp/wefolio-summary-schema-result.txt`；启用后验收：`/private/tmp/wefolio-summary-old-enabled-verification.json`、`/private/tmp/wefolio-summary-new-enabled-verification.json`，均 `verified=true`；部署日志：`/private/tmp/wefolio-summary-runtime-deploy.log`、`/private/tmp/wefolio-summary-job-deploy.log`；启用日志：`/private/tmp/wefolio-summary-runtime-enable.log`、`/private/tmp/wefolio-summary-job-enable.log`。

### 4.25 视频摘要时长读取类型修复与 Job 发布（2026-10-14）

用户执行的重建 `88367297-1cf9-4e30-b718-589d45fae43a` 于 `12:37:47` 在首件作品 17 的摘要预检中遇到 `ClassCastException`，状态为 `PREFLIGHT_REFUSED`，扫描 1 件、失败 1 件，截图数及模型请求数均为 0，游标保留 0，旧派生数据未清空。

根因：生产 MySQL 的 `duration_ms INT UNSIGNED` 经 Connector/J 无指定类型的 `getObject` 返回 `java.lang.Long`，新增摘要仓储直接强转 `Integer`；原 H2 普通 INT 测试返回 Integer，未覆盖这个差异。新增 BIGINT 返回 Long 的最小回归先复现相同异常，再把读取改为 `getObject("duration_ms", Integer.class)`，沿用现有其他视频读取链路的可空整数契约；另覆盖 NULL 保留及超出 Integer 范围时拒绝，避免静默截断。没有修改字段或业务准入上限。

- 相关 63 项测试通过，Job 全量 1,014 项、0 失败/错误、2 项条件跳过。此次只有 Job 仓储一行生产代码变更；所选制品条目对比仅 `VideoSummaryRepository.class` 改变，Runtime 无变化。
- 按用户修复要求，延续本次生产发布完成 Job 更新；构建时间 `2026-10-14 12:43:41`，版本 `96a0ef9`，JAR SHA-256 `4137333cd5fc5455e3868ddc2405e1272a9290aeffa66eb4c02cc5235dd9d6e4`，PID `16852`，启动时间 `12:44:00`。发布退出码 0、24.438 秒，替换前已排空任务、校验上传并备份。旧包 `/root/java/job/wefolio-java-job.jar.backup-duration-cast-20261014-before-release`，SHA `e1538d1da2c7f734b55ea2a3fddd3b9fee6f5c7f44217c9906db1f484eade237`。
- 生产只读探针对相同作品 17、相同 MySQL 驱动和真实部署仓储类分别执行修复前后读取：修复前 `snapshotRead=false, exceptionType=ClassCastException`，修复后 `snapshotRead=true, durationPreserved=true`。探针不调用模型、不写业务数据，临时服务器文件自动清理。
- 发布后健康及版本接口、制品与备份、当前进程开关、启动日志均核验通过；Job 及老 Runtime active/UP，Nginx 轮询正常，新增 ERROR/FATAL 为 0。Job 摘要仍开启，独立音频转写仍关闭。未新增迁移、未改环境配置、未重新发布 Runtime、未提交 Git。

原失败任务不会自动续跑，本轮未重新提交有清空行为的 rebuild；可沿原参数 `afterWorkId=0` 重新提交。修复确认的是原时长类型异常消除，不表示完整截图及模型生成链已经重新执行成功。

脱敏证据：`/private/tmp/wefolio-summary-duration-production-red.txt`、`/private/tmp/wefolio-summary-duration-production-green.txt`、`/private/tmp/wefolio-summary-duration-release-manifest.json`、`/private/tmp/wefolio-summary-old-duration-fixed-verification.json`；测试日志：`/private/tmp/wefolio-summary-duration-red.log`、`/private/tmp/wefolio-summary-duration-green.log`、`/private/tmp/wefolio-summary-duration-job-full.log`；部署日志：`/private/tmp/wefolio-summary-duration-job-deploy.log`。

### 4.26 Snapshot 清理、摘要截断与预算修复，并开启音频文本（2026-10-14）

按用户要求修复并发布 Job，发布前修改服务器实际加载的 `/root/java/.env` 开启独立 ASR；本轮没有改动 Runtime 代码、数据库结构或小程序，也未提交 Git。以下时间均为上海时间。

- Snapshot 终态查询原先使用 `Instant.toString()` 的 UTC `Z`，供应商返回 HTTP 400、`InvalidArgument/startCreationTime is illegal`；只改为官方数字时区格式 `+0000` 的只读对照成功返回 20 条记录。修复日期格式并补充脱敏阶段、HTTP 状态和错误码日志，保留分页、锁和删除边界。
- 原视频摘要改为 `reasoning_effort=low`、`max_tokens=16384`，保留必须开启的思考及严格 JSON 合同。作品 28 原请求输出 4096 token 中有 4079 用于思考、正文为空；候选制品单次实测 25.546 秒生成合法摘要，输入 89404、输出 525 token，缓存 0，未写业务数据库。
- 摘要费用以每请求凭证预留，有合法 usage 后结算实际估算费用并释放差额；未知用量、未发送凭证仍保留预留，次数不回退。截断响应也传递无正文计量用于结算；重复结算、跨批次凭证及费用越界均有保护。每批 500 分硬上限保持。
- Job 全量 1028 项测试、0 失败/错误、2 项远端集成条件跳过；首次沙箱执行受回环端口和媒体工具系统探测权限限制，取得权限后完整重跑通过。Snapshot 红测 4 项失败后相关 79 项通过；摘要截断及预算均先复现后修复。
- 服务器 CentOS 7 / glibc 2.17 / kernel 3.10 不满足当前预编译 FFmpeg 的最低要求，使用官方仍维护的 5.1.10 源码以低优先级编译安装到 `/opt/wefolio/ffmpeg/5.1.10`。源码签名通过官方指纹 `FCF986EA15E6E293A5644F10B4322F04D67658D8` 验证，源码 SHA-256 为 `392306d6fc45dab0e9e0ea55381e071842e83a2fb31d320aeda40477a7766293`；没有替换系统库。MP4/AAC 提取为 16kHz、单声道、PCM16 WAV 以及候选应用原生启动自检均通过。
- 替换 JAR 前已备份 `.env` 为 `/root/java/.env.backup-audio-summary-20261014`，原子更新 `WEFOLIO_VIDEO_AUDIO_ENABLED=true`，两个工具路径为 `/opt/wefolio/ffmpeg/5.1.10/bin/ffmpeg` 和同目录 `ffprobe`；环境文件与备份权限为 `0600`。复用已有 TokenHub 密钥，没有打印或复制到本地。
- 已排空旧实例后备份、验证候选 SHA 并发布。构建时间 `2026-10-14 13:11:41`，版本 `96a0ef9`，JAR SHA-256 `88221d510c6db02558c3e389dfe3862b45619a01e0b3e38e5c57f34ae87ae897`；新 PID `6038`，启动 `13:30:48`，本机及正式 Job 健康均 UP、版本匹配，进程有效音频与摘要开关均为 true。旧 JAR 为 `/root/java/job/wefolio-java-job.jar.backup-audio-summary-20261014`，SHA `4137333cd5fc5455e3868ddc2405e1272a9290aeffa66eb4c02cc5235dd9d6e4`。
- 发布后用部署 JAR 中的真实 ASR 客户端向 `hy-asr-3.0-preview` 发送一次 1 秒合成测试音，状态完成、正文为空（无语音）、22 token，公开价估算 `0.00022` 元；不写作品数据。高级作品集现有 v3 已携带音频文字，本轮无需另启 v2 开关或重启 Runtime。未自动重跑历史 rebuild。
- `13:35:27` 复查，新进程运行约 280 秒，本机与正式 Job、正式 Runtime 健康均 UP，Job 构建版本匹配；新启动 ERROR/FATAL、Snapshot 补偿失败和 ClassCastException 均为 0。未出现实际补偿删除记录，因此此观察只确认周期告警没有复现，不宣称已清空历史输出。服务器内存使用率 51.7%、磁盘 19.4%。

本轮发布包装在任意换包后验证失败时恢复旧 JAR 与 `.env` 并再次验证旧版本健康。实际发布成功，未执行回退。今后调用仓库 Job `deploy.sh` 时注意其音频预检固定读取 `/root/java/job/.env`，本机 systemd 实际使用 `/root/java/.env`，须沿用实际配置的额外预检。

证据：`/private/tmp/wefolio-audio-summary-fix-job-full.log`、`/private/tmp/wefolio-summary-release-smoke.txt`、`/private/tmp/wefolio-install-audio-tools.log`、`/private/tmp/wefolio-enable-audio-env-result.json`、`/private/tmp/wefolio-publish-audio-summary-result.json`、`/private/tmp/wefolio-audio-provider-smoke-result.txt`、`/private/tmp/wefolio-audio-summary-postverify.json`。

### 4.27 原视频摘要校验诊断与失败视频补跑（2026-10-14，Job 已发布）

用户手动触发的重建 `224dc3fe-8792-4983-8775-08343bf009cb` 于 21:18:01 开始、21:28:21 结束。整件成功 9、失败 3；帧成功 11、121 帧；音频保存 9、有效空文本 2；摘要保存 9、校验失败 2（作品 29、33），作品 153 摘要规格不支持，最终 `PREFLIGHT_REFUSED`、`nextAfterWorkId=119`。这轮不是预算中断；摘要 11 次请求、已知 918769 token，按每次向上取整到分估算 81 分，最终费用以供应商账单为准。

29、33 均正常收到模型正文且 `finishReason=stop`，随后本地摘要正文或持久包装校验抛 `AiProtocolException`。旧日志未记录安全错误消息或具体字段，失败正文也未落库，不能还原这两次的精确违规规则；正文总长度不等于 `summary` 字段长度。

新增失败阶段、错误码、安全原因、固定字段路径、规则、实际类型/码点长度/条目数及上限；同时记录耗时、正文总长度、token 计量和异常代码位置。规格拒绝额外记录视频大小、时长及配置上限。未知字段只计数，不输出原键；摘要原文、音频文本、签名链接及普通异常原始消息不进诊断日志。两端共享校验源码同步，原接受/拒绝规则及错误提示保持不变；新增诊断已随本节记录的 Job 发布生效，Runtime 本轮未发布。

验证：5 项用例先证明旧日志缺少诊断信息；修正后相关 47 项通过。完整 Job 1039 项（2 项条件跳过）、Runtime 2771 项（30 项条件跳过），均 0 失败/错误；首次受沙箱本地监听/FFmpeg 系统探测限制，取得权限后全量重跑通过。测试证据为 `/private/tmp/wefolio-summary-logging-red.log`、`/private/tmp/wefolio-summary-logging-focused.log`、`/private/tmp/wefolio-summary-logging-job-full-approved.log`、`/private/tmp/wefolio-summary-logging-runtime-full-approved.log`。

现有 `POST /job-api/video-frames/rebuild` 支持 `(afterWorkId, throughWorkId]`：仅处理作品 29 使用 `{"afterWorkId":28,"throughWorkId":29}`，作品 33 使用 `{"afterWorkId":32,"throughWorkId":33}`。该入口会清空并重建目标作品的帧、音频和摘要，产生相应费用；须等前一任务结束再发下一件，同种执行进行中时只返回已有执行，不排队。`POST /job-api/video-frames/backfill` 仅补缺失/失效阶段，但当前不接受作品 ID 或范围，只能全量扫描；不要向该接口添加未支持的范围参数。本轮未新增接口、未自动补跑。

按用户要求使用 Job 工程现有 `deploy.sh` 完成构建、上传和重启；临时发布包装在脚本替换 JAR 前补做实际运行环境音频工具验证、排空、SHA 核验和独立备份，失败仅回退 JAR，不恢复旧 `.env`。发布构建时间 `2026-10-14 21:47:34`、Git 版本 `96a0ef9`，JAR SHA-256 为 `3a013ca390e36ec76b8c98399c33c67cfb13f290a701d13d607f2229246391d7`；新 PID `22760`，启动时间 `21:47:53`。旧 JAR 备份为 `/root/java/job/wefolio-java-job.jar.backup-summary-logging-20261014-2146`，SHA-256 为 `88221d510c6db02558c3e389dfe3862b45619a01e0b3e38e5c57f34ae87ae897`。

`21:48:39` 验证 Job 本机/公网健康 UP、版本和构建时间一致、制品 SHA 一致，新增诊断异常类存在；当前进程音频和摘要均开启，两个 FFmpeg 路径保持不变，实际 `.env` 内容未变且权限 `0600`。启动后 ERROR/FATAL、Snapshot 清理失败、ClassCastException 均为 0；公网 Runtime 健康 UP，服务器内存 55.0%、磁盘 19.1%。未执行数据库迁移、未重启 Runtime、未提交 Git，发布未触发视频补跑。发布证据：`/private/tmp/wefolio-logging-deploy.log`、`/private/tmp/wefolio-logging-release-result.json`、`/private/tmp/wefolio-logging-postdeploy.json`。

### 4.28 原视频摘要条目超限规整与 Job 发布（2026-10-14）

用户单件重试作品 29 的执行 `fcbc5d11-a038-4d52-8ce3-e8d5d1144a25` 于 21:57:40 结束。新增诊断明确记录 `CONTENT_VALIDATE / INVALID_MEDIA_SUMMARY / path=$.observations / ARRAY_COUNT / actualCount=12 / maxCount=8`；模型正常返回且计量已结算，失败原因是条目数量不符合持久摘要合同。帧 21 张及音频文本 275 字成功保存，摘要未保存。

修复在原视频生产端新增条目数量规整：严格解析原始 JSON、检查字段及全部条目（包括最终舍弃的尾项）的字符串类型、码点长度和内容清理，再按模型顺序保留前 8 条观察、前 4 条识别局限。超长文本、错误类型、未知字段及非法 JSON 仍拒绝；不静默截断文字，不增加模型调用。旧 `validateContent`、`parse`、数据库 schema 及消费者合同继续严格执行，输出可被线上旧 Runtime 直接读取。提示词补充按重要性排序、关键局限优先以及输出前检查数量；裁剪前后条数进入安全日志，正文不输出。共享源码两端同步，本轮只发布 Job。

Service 两项回归先复现旧逻辑 `FAILED/ARRAY_COUNT`，修复后相关 53 项通过；全量 Job 1045 项（2 项条件跳过）、Runtime 2775 项（30 项条件跳过），均 0 失败/错误。覆盖超量保序、尾项非法仍拒绝、原严格合同读取、单次计费、日志脱敏。测试日志：`/private/tmp/wefolio-summary-count-red.log`、`/private/tmp/wefolio-summary-count-focused.log`、`/private/tmp/wefolio-summary-count-job-full.log`、`/private/tmp/wefolio-summary-normalize-runtime-full-approved.log`。

按用户要求调用现有 Job `deploy.sh` 构建、上传、排空及重启；包装在替换前补做实际音频工具检查、SHA 校验、独立备份和安全排空，保留现有 `.env`。构建时间 `2026-10-14 22:07:49`、Git 版本 `96a0ef9`，JAR SHA-256 `fb429712b718330cefa81bc7c6586cebfc58381d67d0c5d055dfa0868639c2d7`；新 PID `27641`，启动时间 `22:08:10`。旧 JAR 备份为 `/root/java/job/wefolio-java-job.jar.backup-summary-count-20261014-2204`，SHA-256 `3a013ca390e36ec76b8c98399c33c67cfb13f290a701d13d607f2229246391d7`。发布失败保护只回滚 JAR；本次成功，未执行回退。

`22:08:37` 复查 Job 本机/公网健康 UP、版本和构建时间一致、安装 SHA 与本地一致，规整类已进入制品；音频与摘要均开启，实际 `.env` 内容未变且权限为 `0600`。启动后 ERROR/FATAL、Snapshot 清理失败与 ClassCastException 均为 0，公网 Runtime 健康 UP。未新增数据库迁移、未重启 Runtime、未提交 Git或触发额外视频补跑。发布证据：`/private/tmp/wefolio-count-deploy.log`、`/private/tmp/wefolio-count-release-result.json`、`/private/tmp/wefolio-count-postdeploy.json`。

### 4.29 视频批处理单件隔离与十进制上传大小修正（2026-10-14，本地完成，待发布）

生产日志确认全量重建于 `21:28:21` 在作品 153 返回 `NOT_SUPPORTED` 后以 `PREFLIGHT_REFUSED` 停止，游标为 119；154～156 未执行。四件源文件均为约 101～103 MB，超过摘要默认的 100,000,000 字节，但低于原小程序上传及音频独立资源上限 104,857,600 字节。已有关键帧来自 10 月 12 日，拒绝发生在清空和正式转写前。

本地 Job 改为单件预检失败保留当前旧数据、记录失败并推进游标，继续后续作品；预算耗尽、全局执行锁失效和线程中断仍停止本轮。摘要规格不支持只跳过摘要模型，继续重建关键帧和音频；仓储在原事务及源、版本、清单 CAS 保护下保留本次无法重建的旧原视频摘要，原值为空则继续为空。整体仍报告部分失败，音频和摘要分别统计；不新增隐式模型重试。音频保留自己的源文件、音轨时长、WAV 和临时资源限制，未收紧到摘要的十进制百兆。

小程序正式视频作品上传上限改为包含边界的 100,000,000 字节；超过此值进入现有最多三轮自动压缩，用 `statSync` 的实际成品大小验收，仍超限则清理临时成品并阻止上传。视频大小显示同步为十进制，图片、动图、音频维持既有显示。后端上传契约保持兼容旧小程序；此规则须发布新小程序后生效，历史 COS 视频不会自动压缩。

验证：单件隔离回归先出现 4 项失败，修正后相关 113 项通过；仓储保留摘要先红后绿，H2 25 项通过；Job 全量 1052 项、0 失败/错误、2 项条件跳过；小程序全量 2671 项通过。补充了历史视频 102,090,765 字节仍可通过音频 HEAD 准入，以及 100,000,001 字节压缩结果被拒绝的验证。日志：`/private/tmp/wefolio-video-isolation-red.log`、`/private/tmp/wefolio-video-isolation-focused.log`、`/private/tmp/wefolio-video-isolation-job-full.log`、`/private/tmp/wefolio-upload-decimal-full.log`。未发布 Job 或小程序、未修改 Runtime、未提交 Git、未执行生产补跑或收费模型调用；手机原生编码效果尚未实机验证。

### 4.30 高级作品集媒体摘要失败诊断日志（2026-10-15，Job 已发布）

生产任务 `18` 于 `13:14:25.011` 创建，`13:14:40.943` 以 `FAILED/INVALID_MEDIA_SUMMARY` 结束，固定提示为“媒体摘要条目不合法”。只读核对确认唯一新模型调用对应图片作品 `157`；HTTP 200、`finishReason=stop`，输入 4401、输出 480 token。两个视频 `154`、`153` 的冻结摘要使用已部署校验器复核均通过。失败响应未写缓存或任务结果，原异常中的字段、类型和数量诊断在应用异常转换时丢失，因此无法从既有库表还原本次具体违规字段或条数。

本轮只在 `PortfolioAiMediaExecutionService` 的模型响应、缓存、冻结摘要校验失败处新增 WARN，日志标签为“高级 AI 媒体摘要校验失败”。记录 `taskId/attempt/workId/sourceNo/stage/callNo/errorCode/diagnostic`；阶段为 `MODEL/CACHE/FROZEN`，仅模型阶段有真实调用号。诊断包含可信字段路径、规则、实际类型、条数或码点长度及上限；未知字段只计数，正文、资源 URL、联系方式和异常堆栈不输出。校验接受范围、错误码、提示文案、计费顺序与重试行为保持不变。

6 项新增回归先在旧实现因日志缺失失败；修正后定向 41 项通过。从 HEAD `f7a9c1f` 隔离快照仅叠加本轮源码与测试，全量 Job 1051 项、0 失败/错误、2 项条件跳过；首次沙箱禁止本机监听和 FFmpeg 系统探测，取得本机测试权限后全量重跑通过。打包退出码 0，候选与生产基线 JAR 逐条比对仅变更本服务主类、内部类和 `version.properties`；§4.29 尚未发布的补帧业务改动未带入。

按用户“补充日志 并发布”授权，先核验并排空旧实例、确认运行任务数为 0、独立备份后原子换包及重启。新构建版本 `f7a9c1f`、构建时间 `2026-10-15 13:39:48`、JAR SHA-256 `ed2ee0d481608980c7b3684305cd1e685a4a3bb7f79ec298502b1ba846836243`；PID `13121`，启动时间 `13:40:57`。旧包为 `/root/java/job/wefolio-java-job.jar.backup-media-summary-logging-20261015-1332`，SHA-256 `fb429712b718330cefa81bc7c6586cebfc58381d67d0c5d055dfa0868639c2d7`。本机与正式 HTTPS Job 健康 UP、版本及制品一致，正式 Runtime 健康 UP；新进程 ERROR/FATAL 为 0，音频/原视频摘要开关和工具路径、`.env` 内容及权限均未变。未执行数据库迁移、未发布 Runtime 或小程序、未重跑收费模型、未提交 Git；日志增强不构成任务 18 生成问题已修复或真实生成已验收。

证据：`/private/tmp/wefolio-summary-logging-red.log`、`/private/tmp/wefolio-media-summary-logging-focused.log`、`/private/tmp/wefolio-media-summary-logging-job-full-approved.log`、`/private/tmp/wefolio-media-summary-logging-package.log`、`/private/tmp/wefolio-media-summary-logging-artifact-check.json`、`/private/tmp/wefolio-media-summary-logging-deploy.log`、`/private/tmp/wefolio-media-summary-logging-release-result.json`。

### 4.31 高级 AI 参考附件（2026-10-16，生产已启用）

初始实现完成了代码、自动化验证与隔离 MySQL 验证。2026-10-16 生产核查确认两台 Runtime 与单台 Job 已包含附件协议，V66/V67 已成功执行，列和 owner 索引均与预期一致；此前附件入口不可用的直接原因是生产未设置附件开关。按用户确认，本轮补齐依赖并启用生产能力，未重新构建或替换应用 JAR，未再次执行数据库迁移。真实 TokenHub 生成和微信 iOS/Android 全链路验收尚未执行。

| 配置 | 服务 | 代码默认及要求 |
| --- | --- | --- |
| `WEFOLIO_AI_REFERENCE_ENABLED` | Runtime / Job | `false`；Runtime 控制新附件接单，Job 控制未领取附件任务的领取；已有清单维护与清理仍可用 |
| `WEFOLIO_AI_REFERENCE_MAX_ACTIVE_TASKS` | Runtime / Job | `10`；Runtime 准入最多为全局活动上限减一，Job 实例附件并发最多为工作线程数减一 |
| `WEFOLIO_AI_REFERENCE_LOCK_POOL_SIZE` | Runtime / Job | Runtime 默认 `4`、Job 默认 `2`，必须大于零；命名锁使用独立池，空闲连接下限为零，借连接最多等待 1 秒 |
| `WEFOLIO_AI_REFERENCE_TOKEN_SECRET` | Runtime | 默认空；启用时至少 32 字节，所有 Runtime 一致，独立于模型/COS 凭据，不写入本台账 |
| `portfolio-ai.reference-cleanup-cron` | Job | 默认 `0 35 * * * ?`，沿既有 AI 调度器每小时执行 |

当前参考附件采用客户端识别信息，Runtime 不下载原件、不创建媒体临时文件、不运行 ImageIO、`ffprobe` 或 `ffmpeg` 校验。格式、编码、大小、尺寸和时长由小程序识别并随确认请求提交，服务端只验证协议字段和产品上限，以及 COS HEAD、对象版本、签名和维护权限。此前安装的媒体工具保留供既有媒体业务使用；本节下方的解码记录属于此次调整前的历史验证。

附件 COS 操作使用独立 HTTPS 客户端，每个请求最多 5 秒、不执行 SDK 自动重试；未改变其它业务 COS 默认超时。上传确认是同步接口，小程序仅对此请求显式使用 150 秒超时，附件上传使用 180 秒；反向代理对 `POST /api/mine/portfolio-ai/reference-attachments/complete` 的读取超时须至少 150 秒，不顺带放宽其它接口。真机验收须确认实际微信版本、代理与服务端完整超时链。

迁移为 `V66__add_portfolio_ai_reference_attachments.sql`（作品集和任务各一列可空 JSON）及 `V67__index_ai_reference_owner_lookup.sql`（任务表 `owner_type, owner_id` 索引），均已同步 `full-schema.sql`。发布顺序：数据库新增列与索引 → 全部 Job 支持新协议 → Runtime → 小程序 → 验收通过后启用。旧 Job 未排空时不得接收新附件任务。关闭 Runtime 接单不影响已接受任务执行；关闭 Job 附件领取会暂停等待中的附件任务，已领取任务继续收尾，普通任务继续领取。Job 关闭时发现待领取附件会告警。继续保留签名密钥与清理任务；回执仍在使用时不要直接轮换单一密钥。

存储路径固定为 `{ownerUniqueCode}/protfolio/ai-reference/{uuid}.{jpg|png|mp4}`，团队取团队唯一码。桶版本控制必须为 `Off`；源站匿名请求与 CDN 均须无法读取这些对象。保留条件为写入未满 24 小时、当前有效作品集引用、活动任务引用或任务终态未满 24 小时。清理每轮最多 1,000 个对象、200 次 COS 调用、60 秒，未知 owner/坏 JSON/403 等异常保守跳过并记录。未保存上传依靠 COS 前缀枚举发现，不依赖业务表中的上传台账。

生产启用记录：两台 Runtime 增加 `/root/java/portfolio-ai-reference.env`，通过 `wefolio.service.d/90-portfolio-ai-reference.conf` 引入；Job 增加 `/root/java/job/portfolio-ai-reference.env`，通过对应 Job drop-in 引入。附件开关均为 `true`，Runtime 使用同一独立强随机签名密钥（只保存在服务器，运行进程一致性已验证），并在 `PATH` 加入 `/opt/wefolio/ffmpeg/5.1.10/bin`。文件权限为 `0600`；两服务实际共用的基础 `/root/java/.env` 内容及原 JAR 摘要均保持不变。Job 于 17:13 排空至活动任务 0 后重启；Runtime 于 17:14 依次切流重启，随后恢复 Nginx 双节点轮询。Runtime 构建时间仍为 `2026-10-16 15:37:35`，Job 为 `15:39:38`，版本均为 `cd7eb1a`；三进程健康 UP，Job 已出现新的正常周期调度日志，启动后未发现 ERROR/FATAL。Nginx 仅新增附件确认接口的精确 location，`proxy_read_timeout 150s`，原 `/api/` 配置保持原超时。

存储核验发现：尽管桶和对象均非公开 ACL、源站匿名访问为 403，原 CDN/EdgeOne 回源授权仍允许匿名用户通过 CDN 读取私有测试对象。已备份完整桶策略，保留原两条授权，新增 `DenyAiReferenceCdnOriginRead`，仅拒绝原策略中 `service/edge_one`、`service/cdn` 两个主体对 `*/protfolio/ai-reference/*` 的 `GetObject` 与 `HeadObject`；读取回来的策略与候选语义一致。两台 Runtime 使用各自实际凭据、两个独立合成 owner 的新对象复测：源站、`cos.we-folio.dingchenyong.top`、`cdn2.we-folio.dingchenyong.top` 匿名 HEAD/GET 均为 403；SDK 与源站签名 GET 内容匹配，临时对象均精确删除并确认不存在。之前可读的测试 URL 复核也为 403，目录外公开 demo 头像仍为 200。桶版本控制持续为 `Off`。

原 FFmpeg 5.1.10 为音频专用精简构建，虽然能够启动，但缺少附件视频完整解码组件。复用 §4.26 已验证签名的官方源码包（SHA-256 `392306d6fc45dab0e9e0ea55381e071842e83a2fb31d320aeda40477a7766293`），保留原编译参数，追加 H.264 decoder、wrapped_avframe encoder、null muxer/filter 和 pipe protocol；低优先级独立构建后，H.264 完整解码、ffprobe 格式检测及既有 MP4/AAC → 16kHz 单声道 PCM16 WAV 回归通过，才在两节点备份并原子替换工具。两节点 FFmpeg SHA-256 为 `acd62f33843105582dea3e3674aeec420aa6eaf1ecae909b42b328247aa683b3`，FFprobe 为 `bdce69232dc440787e86668fd2757c3176cc3f18593707b0ba4613b640f1fcab`。配置、Nginx、COS 策略与旧二进制的回退资料均位于服务器 `/root/java/reference-enable-20261016/`；关闭接单时保留签名密钥和目录私有策略，并让 Job 处理已接收任务。

两台 Runtime 均使用已部署 JAR 的真实 `PortfolioAiReferenceStorage` 与 `PortfolioAiReferenceMediaProbe.inspect` 独立验证：本次合成 16×16 PNG、32×32/1 秒 H.264 MP4 上传后，实测 MIME、尺寸与时长正确，两个探测器均成功，所有测试对象精确删除。此验证未启动额外 Spring 实例、未写数据库、未调用模型；不等同于微信真机端到端上传、最长视频压力验证或真实 AI 生成验收。最终节点检查记录在 `/tmp/wefolio-reference-enabled-old.json`、`/tmp/wefolio-reference-enabled-new.json`。

小程序附件入口已改为能力不可用时点击重新查询并给出明确提示；个人与团队页面同步，保留当前编辑状态。对应完整 Node 测试 2,763 项通过，证据 `/tmp/wefolio-reference-retry-full.tap`。本轮未提交 Git，也未发布新的小程序版本。

**同日 HEVC 支持补充（17:55 完成线上核验）：** 用户提供的 iPhone 录屏为 MP4/HEVC，原服务端只接受 H.264，因而格式校验拒绝。Runtime 编码白名单扩展为 `h264`、`hevc`，保留 MP4 品牌、单视频流、100 MB、300 秒及全片解码限制；MOV、VP9、AV1 未纳入支持。个人与团队小程序同步接受 H.264/AVC、H.265/HEVC 及 `avc1`、`hvc1`、`hev1` 别名，合规的小体积 MP4 不再仅因 HEVC 编码触发压缩；未知编码仍交服务端实测。小程序代码未上传或发布，完整真机流程须使用最新预览或后续发布版本验收。

两台 Runtime 从原生产 SHA-256 `65e29ae7075445e55864b3f1c1a21041a112e2de773c9677e27eb2a183366a6d` 制作最小补丁，仅替换 `PortfolioAiReferenceMediaProbe.class` 与 `version.properties`，未混入工作区其它改动。新版本 `cd7eb1a-hevc`，构建时间 `2026-10-16 17:45:44`，JAR SHA-256 `f163042629f76de71cc3d62bc9cdca990aaa07508a8c640bba7ba624cbf6407d`。滚动切流、备份、原子替换并重启后，旧/新节点 PID 分别为 `27666`、`870`，最终恢复 Nginx 双节点轮询；两节点及正式 HTTPS 入口健康 UP，版本匹配，Runtime 启动后 ERROR/FATAL 与附件告警均为 0，150 秒附件确认超时保留。

媒体工具沿用上述已验证官方源码与当前构建参数，仅追加 HEVC decoder；两节点原子替换后的 FFmpeg SHA-256 为 `ade34c455d4101985f0d71882289538a2a71a64a2ba10e3a8a0cce669482d324`，FFprobe 为 `a90f47af7a0c6858dad2142db87439fe7e275e4fe639d586e525933c4ab5ed7c`。HEVC 完整解码与既有 MP4/AAC → PCM16 音频提取回归通过。两节点旧 JAR、旧工具与摘要清单均保存在 `/root/java/reference-hevc-20261016/`（`runtime.before.jar`、`ffmpeg.before`、`ffprobe.before`、`runtime-manifest.json`、`media-tools-manifest.json`）；如回滚，先切流并恢复旧 JAR，再按兼容需求恢复工具，逐节点检查健康后恢复轮询。

验证：HEVC 8 位、10 位原生视频测试先在旧实现失败，修改后连同 H.264 接受、VP9/MOV 拒绝共 5 项通过且无跳过。Runtime 全量 2,888 项、0 失败/错误、35 项条件跳过；小程序附件及分包边界 82 项、完整 Node 2,786 项通过。用户实际录屏只在本机使用候选 JAR 的真实视频探测方法校验，通过结果为 MP4、888×1920、41,030 ms，耗时约 1.8 秒；未上传该用户录屏至生产或模型。两台生产节点分别用已部署存储与探测类校验合成 PNG 和 1 秒 HEVC，结果正确，4 个私有临时对象均精确删除并确认不存在，未写业务数据库或调用模型。

另以合成蓝色视频进行有限的真实 TokenHub `glm-5.3-flash` 兼容验证：最初 64×64/1 秒 HEVC 样本返回 200，但未确认画面颜色，结果不足以证明识别成功；改为相同内容的 320×320/3 秒 H.264 与 HEVC 对照后，两者均返回可读取且识别为蓝色，各计 525 输入、14 输出 token。只产生少量供应商调用计量，未创建业务任务或扣用户积分；对应合成 COS 对象均已精确清理。该验证不等同于用户视频质量、所有设备或真实微信上传至生成的全链路验收。

本次 HEVC 发布未迁移数据库、未替换或重启 Job、未修改基础 `/root/java/.env` 或附件签名密钥、未提交 Git。Job 仍为 PID `7848`、版本 `cd7eb1a`，健康 UP，正常周期任务持续运行；最终检查中的 3 条附件 WARN 均发生于本次滚动发布前的 17:35:04，类别为“无法确定根目录 owner，跳过”，无 ERROR/FATAL。证据：`/tmp/wefolio-hevc-runtime-full.log`、`/tmp/wefolio-hevc-runtime-red.log`、`/tmp/wefolio-hevc-artifact.json`、`/tmp/wefolio-hevc-smoke-old.json`、`/tmp/wefolio-hevc-smoke-new.json`、`/tmp/wefolio-hevc-enabled-old.json`、`/tmp/wefolio-hevc-enabled-new.json`、`/tmp/wefolio-hevc-job-warnings.json`。

**同日改为客户端识别（19:07 完成生产核验）：** 按用户“信任前端识别的格式、编码、大小和时长，后端不下载校验”“修改后发布服务”和“不兼容处理”的明确要求，`complete` 必须携带完整 `mediaInfo`：`mimeType`、`codec`、`sizeBytes`、`width`、`height`、`durationMs`。图片时长为 `null`，微信未提供编码时 `codec` 使用空字符串；字段缺失、空对象、非法类型、与签票或 HEAD 大小不一致均拒绝。新上传票据的签名载荷必须包含 `sizeBytes`，不接受旧票据缺省；不生成宽高或 300 秒时长占位。既有已确认附件、持久 JSON 与 Job 合同保持不变。

Runtime 删除 `PortfolioAiReferenceMediaProbe` 与 `PortfolioAiReferenceStorage.download`，以纯内存 `PortfolioAiReferenceMediaInfo` 接收前端识别值。保留附件归属、当前维护权限、签名、防覆盖、对象大小和 ETag/修改时间的一致性检查。个人和团队小程序的首次确认、上传结果未知重试、确认失败重试均提交最终上传成品的信息；压缩后重新读取成品尺寸、编码、时长和大小。小程序代码未上传或发布；旧客户端新上传请求会被明确拒绝，需使用本轮更新后的预览或发布版本。

从 HEVC 生产基线仅新增 `PortfolioAiReferenceMediaInfo.class`，替换 `PortfolioAiReferenceService.class`、其 `ReferenceSnapshot` 内部类及 `PortfolioAiReferenceStorage.class`，删除旧 Probe class，并更新版本文件。最终版本 `cd7eb1a-clientmeta`、构建时间 `2026-10-16 19:04:24`、JAR SHA-256 `fe49bc0ff54b05881a01524155a9a71ae367b31bf95b8c46f9ff7fad4c6c47f6`。首次候选因版本文件键名错误显示 `unknown`，核验发现后仅修正 `build.version`/`build.time` 并再次滚动更新，业务 class 字节未变。两节点切流并排空连接后备份、原子替换、重启；最终旧/新节点 PID 为 `12994`/`17827`，健康 UP，正式 HTTPS 版本正确，Nginx 已恢复双节点轮询，启动后 ERROR/FATAL 与附件告警均为 0。

严格版 Runtime 定向 49 项全部通过；全量 2,900 项、0 失败/错误、35 项既有 MySQL/字体环境条件跳过。小程序附件和分包边界 87 项、全量 2,791 项通过。候选包和两台实际部署 class 的纯内存验收均确认前端 HEVC/图片字段原样保留、缺失媒体信息拒绝、下载方法和 Probe class 不存在；验收没有 COS、数据库或模型调用。未进行真实用户微信上传至生成的端到端验收，未上传用户录屏。Job 进程及 JAR、基础 `.env`、附件配置与签名密钥均未变，无数据库迁移、无媒体工具变更、无 Git 提交。

变更前 HEVC JAR 备份在两节点 `/root/java/reference-client-metadata-20261016/runtime.before.jar`，版本信息修正前的中间制品另存于 `/root/java/reference-client-metadata-version-20261016/runtime.before.jar`，各目录保留对应清单。回滚应使用前者，并与前端确认请求合同协调，避免将新客户端请求发往不支持 `mediaInfo` 的旧后端。证据：`/tmp/wefolio-reference-client-media-strict-focused.log`、`/tmp/wefolio-reference-client-media-strict-full.log`、`/tmp/wefolio-reference-mediainfo-full-tests.log`、`/tmp/wefolio-reference-client-metadata-artifact.json`、`/tmp/wefolio-reference-client-metadata-smoke-{old,new}.json`、`/tmp/wefolio-reference-client-metadata-release-{old,new}.json`、`/tmp/wefolio-reference-client-metadata-startup-{old,new}.json`。

### 4.32 关键帧 CDN 读取 Runtime 发布（2026-10-18）

按用户“先发布 runtime”授权，从当前已验证工作区执行标准 `deploy.sh`，两台 Runtime 滚动发布完成，退出码 0，耗时 98.739 秒。版本字段为 HEAD `129f273`，包含未提交的关键帧 CDN 修正，实际制品以构建时间与 SHA-256 识别。本次未发布 Job 或小程序、未提交 Git、未新增数据库迁移或更改微信后台合法域名。

- 关键帧查询复用 `CosService.publicUrl` 返回免签 CDN 地址，保留作品归属、源身份与帧路径校验；响应字段和原封面上传保存合同不变。当前 Runtime 还包含此前已提交的不可用账号转让校验和团队 AI 提示词说明修正。
- 构建时间 `2026-10-18 13:38:55`（上海），JAR SHA-256 `b18070f981dd82fa861666a2f6214086783e48cb5f11be8b1aa74fe94ecf9838`。旧/新节点 PID 为 `16473` / `31501`，启动时间 `13:39:37` / `13:40:14`；两个远端包与本地摘要一致，服务均 active/running、健康 UP，正式 HTTPS 健康与版本入口核验通过。
- Nginx active、语法检查通过，Runtime upstream 已恢复两节点轮询，无 down/backup 标记。两台 Runtime 本次 PID 启动后的 journal ERROR/FATAL 计数均为 0，字体 enabled/ready 均为 true。
- 两节点旧包均保留在 `/root/java/wefolio-java-runtime.jar.backup`，SHA-256 `f678f1bb98b927cc54ea623c415827bf13627d86529dd04f541140bb9a028b49`，与发布前一致。需要回退时按节点摘流、恢复此包、重启、健康检查后恢复轮询；不涉及数据库回退。
- 发布前 Runtime 全量 2,906 项、0 失败/错误、35 跳过；小程序全量 2,828 项通过。发布后在已登录微信开发者工具重新打开此前失败的作品，选择同一 01:00 关键帧并点击“使用此帧”，成功回到“待保存 · 01:00”状态；随后取消验证草稿，没有点击完成、上传封面或保存真实作品。此结果验证真实图片下载及本地草稿，不等同于手机真机或最终封面上传保存验收。

证据：`/private/tmp/wefolio-keyframe-cdn-runtime-deploy-20261018.log`、`/private/tmp/wefolio-keyframe-cdn-runtime-postdeploy-20261018.json`（`verified=true`）；界面证据仅保留本地 `design/video-keyframe-cover-cdn-verified.png`。

### 4.33 AI 字段说明与用户唯一码日志 Job 发布（2026-10-19）

按用户“提交git 并发布job”授权，提交本次 22 个 AI 文件为 `380de7e`（`fix(ai): 完善字段说明并补充用户唯一码日志`），随后单独发布 Job。两工程组件契约同步补充 40 处 description，字段类型、必填性、合法值域与协议版本未变，兼容当前线上 Runtime；补充媒体/原视频摘要的三字段说明和合法示例。Job 通过 `requested_by → wf_user.unique_code` 获取发起用户唯一码，仅作为任务内存字段，执行、模型请求响应、摘要校验、费用、重试及终态日志记录 `requestedByUniqueCode`；团队任务仍记录发起用户，不使用团队或素材所有者的唯一码。

- 发布前 Runtime 全量 2,906 项、0 失败/错误、35 项条件跳过；Job 全量 1,146 项、0 失败/错误、5 项条件跳过。Job 首轮受沙箱本地端口和 FFmpeg 探测权限影响，在允许这些本地测试能力后全量重跑通过。构建退出码 0，制品与生产旧包仅有本次源码对应 class/内部类、四份提示词/契约资源和版本元数据差异。
- 新构建版本 `380de7e`，构建时间 `2026-10-19 14:55:16`，JAR SHA-256 `2ad4db01f2ad26bd32572ede20afe7fb96bba85c89b30794f64f1e222e72fa2f`。先校验上传包并备份旧包，再调用现有停用接口确认 `DISABLED / activeTaskCount=0`，随后原子换包和重启；新 PID `14158`，启动时间 `14:57:52`。
- 发布后独立验收 `verified=true`：本机及正式 HTTPS `/job-api/health` 均 UP，`/job-api/version` 均匹配新版本与构建时间，实际 JAR 摘要一致，服务 active/running，调度活动恢复。观察至 `14:58:45` 的 journal/application ERROR/FATAL 均为 0；新增日志字段和三字段摘要提示词已在实际 JAR 核验。观察窗口尚未出现新的 AI 身份日志，没有发起收费模型任务，不据此声称真实生成成功率已完成验收。
- 基础 `.env` 内容、权限及音频、原视频摘要、AI 媒体、参考附件有效开关保持不变；音频工具预检通过。本次没有数据库迁移，没有发布 Runtime 或小程序，用户并行的小程序改动未纳入本次提交。
- 旧包保留在 `/root/java/job/wefolio-java-job.jar.backup-ai-fields-user-code-20261019-380de7e`，版本 `4e25e0f`、构建时间 `2026-10-18 09:54:56`，SHA-256 `2ea565f5a709bb3e47217a7c183adfcf093baaf77163988fc8268eb403c2de66`。需回退时先排空当前实例，再恢复该包并重启，核验旧版本和健康；不回退数据库。

证据：`/private/tmp/wefolio-ai-fields-runtime-full.log`、`/private/tmp/wefolio-ai-fields-job-full-approved.log`、`/private/tmp/wefolio-ai-fields-job-package.log`、`/private/tmp/wefolio-ai-fields-job-release-result.json`、`/private/tmp/wefolio-ai-fields-job-postdeploy.json`。服务器保留 `/root/java/job/release-ai-fields-user-code-20261019-380de7e.json` 发布状态记录。

### 4.34 视频默认作品推荐名称 Job 发布（2026-10-19）

按用户“提交git并发布job”授权，提交 `851f811`（`feat(job): 为视频默认作品名称补充文艺推荐名`）并单独发布 Job。原视频摘要请求统一返回文艺风格的 `suggestedTitle`，最多 10 个 Unicode 码点；摘要成功提交后再读取最新名称，仅替换“视频＋合法日期时间＋规范序号”的占位名称。名称缺失、非法、并发更新未命中或命名异常均不改变摘要成功结果；原摘要保存 SQL、事务、共享三字段持久合同及版本条件保持不变。

- 发布前 Job 全量测试 1,202 项，0 失败、0 错误，5 项既有腾讯云/MySQL 环境条件跳过；定向测试 102 项全部通过。构建退出码 0。候选制品与生产旧包仅有两个修改源码对应的 class/内部类、原视频摘要提示词和版本元数据差异。
- 版本 `851f811`，构建时间 `2026-10-19 20:50:50`（上海），JAR SHA-256 `f46ac15722cbae142baea06a67b68ac7302f482080558358a763ada10e6409be`。核验上传包并独立备份后，停用旧实例调度确认 `DISABLED / activeTaskCount=0`，原子换包并重启；新 PID `30426`，启动时间 `20:53:10`。
- 本机及正式 HTTPS `/job-api/health` 均 UP，`/job-api/version` 均匹配上述版本和构建时间，实际 JAR 摘要及包内 `suggestedTitle` 提示词校验一致。服务 active/running；截至 `20:54:21`，新实例已完成一轮作品审核调度，无停用回调，启动后 journal/application 的 ERROR/FATAL 均为 0。
- 基础 `.env` 内容、权限及有效音频、摘要、媒体、附件开关保持不变，FFmpeg/FFprobe 预检通过。未发布 Runtime 或小程序，无数据库变更，未主动创建收费模型任务或修改业务数据；发布验证不等于真实视频取名质量验收。
- 旧包保留在 `/root/java/job/wefolio-java-job.jar.backup-video-title-20261019-851f811`，版本 `380de7e`、构建时间 `2026-10-19 14:55:16`、SHA-256 `2ad4db01f2ad26bd32572ede20afe7fb96bba85c89b30794f64f1e222e72fa2f`。回退应先排空当前实例，再恢复旧包、重启并核验健康及版本，不回退数据库。

证据：`/tmp/wefolio-video-title-full.log`、`/tmp/wefolio-video-title-targeted.log`、`/tmp/wefolio-video-title-job-package.log`、`/tmp/wefolio-video-title-job-artifact.json`、`/tmp/wefolio-video-title-job-package-diff.json`、`/tmp/wefolio-video-title-job-release-verify.json`、`/tmp/wefolio-video-title-job-public.json`、`/tmp/wefolio-video-title-job-postdeploy.json`。服务器发布记录：`/root/java/job/release-video-title-20261019-851f811.json`。

### 4.35 作品统一排序 Runtime 发布（2026-10-19）

按用户“发布并提交git”及“只发布后端”授权，提交 `869cefd`（`fix(works): 统一作品排序并保持标签仅筛选`），使用标准 `deploy.sh` 完成 Runtime 双节点滚动发布，退出码 0，耗时 100.024 秒。作品列表统一使用 `wf_work.sort_order ASC, id DESC`，标签只缩小结果集合，响应排序值也统一来自作品全局排序。旧客户端 `scope=TAG` 的路由、字段和归属校验保留；保存时在事务内锁定全局有效作品，将提交作品重新排列到原有位置，其余作品保位，再写入全局排序。

- 发布前 Runtime 全量测试 2,913 项，0 失败、0 错误、35 项既有条件跳过；排序服务、Controller 与历史迁移定向 120 项全部通过。小程序全量 2,830 项通过，排序入口统一调整全部作品的代码已随上述提交入库，但本次未上传、提审或发布小程序。
- 构建版本 `869cefd`，构建时间 `2026-10-19 21:41:19`（上海），JAR SHA-256 `b14205112386eb7630e2bf88dcb39e362aac2bb51ac7dde7ee5a5927b9f30551`。旧/新节点 PID 为 `9482` / `3451`，启动时间 `21:42:01` / `21:42:40`；两节点实际 JAR、版本及构建时间一致，服务 active/running、健康 UP，字体 enabled/ready 均为 true。正式 HTTPS 健康与版本入口核验通过。
- 截至 `21:44:36`，两节点本次启动后的 journal/application ERROR/FATAL 均为 0。Nginx 语法检查通过，upstream 已恢复双节点轮询，无 down/backup 标记。Job 未发布或重启，仍为版本 `851f811`、PID `30426`，健康 UP。
- 两节点旧包保留在 `/root/java/wefolio-java-runtime.jar.backup`，SHA-256 均为 `b18070f981dd82fa861666a2f6214086783e48cb5f11be8b1aa74fe94ecf9838`。未新增数据库迁移或修改环境配置；回退时逐节点摘流、恢复旧包、重启并核验健康后恢复轮询。

证据：`/tmp/wefolio-unified-order-runtime-full.log`、`/tmp/wefolio-unified-order-miniapp-tests.log`、`/tmp/wefolio-unified-order-runtime-deploy.log`、`/tmp/wefolio-unified-order-postdeploy.json`。本轮未通过生产账号执行真实排序写入。

### 4.36 作品集状态筛选 Runtime 发布（2026-10-22）

按用户“runtime发布下”授权，使用标准 `deploy.sh` 完成 Runtime 双节点滚动发布，退出码 0，耗时 99.688 秒。个人、团队作品集列表增加可选 `publicationStatus` 筛选，不传参数保持原行为，无数据库迁移或环境配置变更。

- 构建版本标识 `4185740`，包含发布时尚未提交的筛选改动；构建时间 `2026-10-22 21:35:21`（上海）。JAR SHA-256 为 `861bef96acead8362c8ce2c0aaa1912db212bbe55bacfc98ccea6cf6af858c46`，双节点与本地一致。
- 老/新节点 PID 为 `26750` / `14805`，启动时间 `21:36:02` / `21:36:40`。截至 `21:37:26`，两节点 active/running、健康 UP、字体 enabled/ready；正式 HTTPS 健康与版本核验通过，启动后 journal ERROR/FATAL 均为 0。
- Nginx 语法检查通过，upstream 恢复双节点轮询。两节点旧包均保留于 `/root/java/wefolio-java-runtime.jar.backup`，SHA-256 为 `b14205112386eb7630e2bf88dcb39e362aac2bb51ac7dde7ee5a5927b9f30551`；回退时逐节点摘流、恢复旧包并验收。
- 发布前已完成 runtime 全量测试：2925 项，0 失败、0 错误、35 项跳过。生产环境未使用真实用户身份执行筛选业务验证。Job 未发布或重启，PID 仍为 `6474`；小程序 1.5.9 尚未发布，本次部署过程未执行 Git 提交。

证据：`/tmp/wefolio-runtime-release-20261022.log`、`/tmp/wefolio-filter-java-full.log`、`docs/portfolio-runtime-release-verification-20261022.json`。

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

### 6.4 历史视频帧手动重建（2026-10-14，代码入口）

`POST /job-api/video-frames/rebuild` 沿用 `X-Admin-Point-Secret` 运维头，只能显式触发。可选 JSON 字段为 `maxFrames`（默认 `VIDEO_FRAME_BACKFILL_MAX_FRAMES`，缺省配置 2400，请求范围 8～配置上限）、`afterWorkId`（排他下界，默认 0）和 `throughWorkId`（包含上界，省略时扫描冻结当前水位）。仅处理未删除、ACTIVE/PASSED 且作者 ACTIVE 的 VIDEO。202 表示接受，200 表示本机已有同种重建且未重复提交；与补帧、清扫或跨实例锁冲突返回 503。

每件先检查帧预算和旧清单归属；开启音频转写后，还须下载、探测真实音轨及时长并预留音频额度。预检通过才 CAS 同时清空帧、音频文本及 AI 摘要，严格删除旧 COS 帧、重截完整 8～24 帧并按最长边 960px/每张 200KiB 上限存储，附可选视觉特征后 CAS 发布，最后复用预检文件重新转写。预算不足不清下一件；旧清单异常或删除失败不继续收费截图。重复执行已结束的范围会重新产生截图及已开启的 ASR 费用；`maxFrames` 始终只限制帧，音频有独立预算。

`POST /job-api/video-frames/backfill` 共用上述处理模块：合法同源帧且文字非 NULL（包括空字符串）才整件跳过；已有帧但文字为 NULL 时只补转写。新帧发布使旧文字和摘要同时失效，帧成功而转写失败则保留帧、文字仍为 NULL，后续 backfill 只补文字。音频开关关闭时维持原帧处理能力。

清空后失败没有自动精确恢复；可能短暂或持续缺帧，已冻结旧清单的在途作品集可能失败。结束日志 `nextAfterWorkId` 跨过已失败行，接续不等于补跑失败；失败件按单件范围重建或在清单为 NULL 时使用 backfill。残留旧帧按超过 24 小时的对象年龄及引用保护由既有清扫补偿，不承诺立即删净。详细参数、日志、补跑及影响见本地[历史视频帧手动重建操作说明](design/advanced-portfolios/2026-10-14-video-frame-rebuild.md)。本节为代码操作说明，不代表已部署或已执行生产重建。

## 7. 大模型服务

| 环境 | 服务商 | 用途 | 模型/能力 | Base URL | API Key 标识 | Secret 存放位置 | 额度/计费 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 本地技术验证（2026-10-10） | 腾讯云 TokenHub | 高级作品集生成、整体修改 | `glm-5.3-flash` | `https://tokenhub.tencentmaas.com/v1` | `TOKENHUB_API_KEY`（只记变量名） | `projects/java/wefolio-java-job/.env` | 实测11次调用共133435 token；费用按账户实际计量核对 | 连通性、8/8公开mock场景首轮通过，独立图片/视频主要画面人工核查一致；未发布，生产历史状态见 §4.10/4.11 |

TokenHub 调用为 `POST /chat/completions`。Runtime 只冻结供应商、模型及端点配置，API Key 仅由 Job 使用；不得将旧 `DEEPSEEK_API_KEY` 作为新服务凭据。启用前 Runtime 与 Job 的 `WEFOLIO_AI_BASE_URL`、`WEFOLIO_AI_MODEL` 必须一致，未配置时使用上表默认值。当前迁移保持本地未提交状态，验证方案见本地 `docs/design/advanced-portfolios/2026-10-10-tokenhub-api.md`。

### 视频作品音频转写（2026-10-14，已部署，未启用）

V64 仅新增 `wf_work.video_audio_text` 一个可空 MEDIUMTEXT 字段：NULL 为待生成或失败，空字符串为已处理无文字，非空为完整转写。CHECK 禁止非 VIDEO 保存非 NULL 值。转写全文不出现在普通作品查询或公开 DTO，也不随作品标题、封面编辑写回。Job 自动审核的 AVERAGE/INTERVAL 视频 PASS 事务提交后执行转写；ASR 失败不改变审核结论。人工审核通过而未走此回调的作品通过显式 backfill 补齐。

ASR 固定使用 `POST https://tokenhub.tencentmaas.com/v1/wand/asrproxy/sync_transcribe`、模型 `hy-asr-3.0-preview`，读取既有 `TOKENHUB_API_KEY`。受控 COS 下载后，ffprobe 检查真实时长，ffmpeg 取第一音轨转为 16kHz/单声道/PCM16 WAV，以纯 Base64 提交。请求仅含 `model`、`data`、`voice_encode_format`；校验 `completed` 后只保存 `output.text`，不下载字幕、不保存时间戳、不额外调用 GLM。无音轨直接保存空字符串，无 ASR 费用；超过十分钟拒绝处理，不截断。

| 配置 | 所属 | 默认值与用途 |
| --- | --- | --- |
| `WEFOLIO_VIDEO_AUDIO_ENABLED` | Job | `false`；迁移、媒体工具及账户权限就绪后开启 |
| `TOKENHUB_API_KEY` | Job | 沿用作品集 TokenHub 密钥，勿记录值 |
| `WEFOLIO_VIDEO_AUDIO_FFMPEG_PATH` | Job | `/usr/bin/ffmpeg`；管理员安装的绝对可执行路径 |
| `WEFOLIO_VIDEO_AUDIO_FFPROBE_PATH` | Job | `/usr/bin/ffprobe`；管理员安装的绝对可执行路径 |
| `WEFOLIO_VIDEO_AUDIO_TOTAL_TIMEOUT_SECONDS` | Job | `180`；音频处理总期限 |
| `WEFOLIO_VIDEO_AUDIO_CONCURRENCY` | Job | `1`；每实例同时处理量，非跨实例全局额度 |
| `WEFOLIO_VIDEO_AUDIO_MAX_REQUESTS_PER_RUN` | Job | `100`；单次历史执行或审核扫描的请求预算 |
| `WEFOLIO_VIDEO_AUDIO_MAX_SECONDS_PER_RUN` | Job | `3600`；同一执行的音频秒数预算 |
| `WEFOLIO_AI_MEDIA_AUDIO_TEXT_ENABLED` | Runtime | `false`；全部 Job 升级后开启 v2 音频上下文冻结 |

启用时校验工具路径、可执行性、WAV 编码及探测能力和资源配置。Job 部署脚本在替换制品前检查已开启实例的媒体工具。生产主机须安装带 PCM16/WAV 能力的 ffmpeg/ffprobe；临时目录使用 JVM `java.io.tmpdir`，每件预留 160MiB 可用空间，按实例并发评估磁盘容量。单视频上限 100MiB、WAV 25MiB、Base64 40MiB、响应 2MiB；全文最多 65,536 个 Unicode 码点/256KiB，超限失败而不保存节选。下载/探测/提取/HTTP 上限分别 60/10/30/120 秒，均受音频总期限及历史单件剩余总期限限制；历史单件默认 180 秒帧期限 + 180 秒音频期限，重建预检包含在内。

跨实例同作品使用 MySQL 会话命名锁。付费前及写回前检查作者、作品、审核轮次、源键/SHA、COS ETag/大小及帧清单源身份；全文与摘要失效同事务 CAS 写入。预检预算按向上取整时长再保守加一秒（最多 600 秒）预留，覆盖 AAC 解码尾部填充；提取后重新探测 WAV，超出十分钟或已预留时长则拒绝付费调用。所有临时文件和子进程在执行结束时回收，日志只保留作品 ID、结果、请求编号、长度、usage 与公开价估算，不打印全文、密钥、Base64 或签名 URL。401/402 停止本轮后续 ASR 发送；超时/429/5xx 不隐藏重试，保持 NULL 待显式补齐。预算不足停止扫描且不跨过当前游标，普通失败行仍沿用 §6.4 的游标规则。进程在供应商收费后、写回前退出可能导致后续重复费用；当前没有持久收费账本。

`usage.total_tokens` 优先，兼容 `usage.total_token`；缺失或非法计量明确为未知，不按免费处理。公开价格为 22 tokens/秒、10 元/百万 tokens，以 BigDecimal 保留估算精度。十分钟约 13,200 tokens/0.132 元；单轮 3,600 秒约 0.792 元，均不含下载、截图及后续 GLM 费用。此前获授权样本实测 84.608 秒、1,861 tokens、公开价约 0.01861 元；本次代码测试使用合成媒体及本地模拟响应，没有新增收费调用。官方依据：[ASR 接口](https://cloud.tencent.com/document/product/1823/135791)、[公开价格](https://cloud.tencent.com/document/product/1823/130055#voice-generation-price-section)。

账户 401/402 后，审核结论与 backfill 后续补帧照常完成；rebuild 后续作品因音频预检失败保留原帧、文字和摘要，本轮不再清空。已有完整文字仍按跳过处理。请求预算耗尽但尚未探测音轨的作品可能仍需下载/探测，以区分无音轨的免费空结果；批量吞吐需同时考虑下载、媒体工具及 ASR 耗时。

高级作品集 v2 在接单时一次读取源、帧和文字，冻结清理后的单作品最多 6,000/全任务最多 24,000 码点；数据库保留全文。已有 FRAMES 和短视频 VIDEO 路线均可使用文字，NULL/空串省略。单作品摘要和最终页面生成均发送冻结文字并计入预算，将口播作为不可信作品内容，区分画面事实与音频说法。冻结后新增文字不会插入旧任务；文字变化使缓存身份变化，旧任务不得覆盖新文字缓存。Job 同时接受 v1/v2，v1 维持无转写上下文。

发布顺序：先执行 V64（可随关闭 v2 的 Runtime 发布），升级全部 Job 消费者并准备媒体工具，再开启 ASR 与 Runtime v2。回退时先关闭 Runtime v2，排空已接收 v2 任务后才可退回旧 Job；关闭 ASR 不清已有文字，保留新增可空列。当前原视频对象不可替换；若将来换源或人工同键覆盖 COS，必须同步清除文字、帧和摘要。代码和 V64 已按 §4.24 发布；随后按 §4.26 安装 FFmpeg/FFprobe 5.1.10，在实际 `/root/java/.env` 开启生产 ASR。当前 Runtime v3 已直接携带音频文字，不依赖 v2 开关；本轮未重新执行历史批处理。

本地真实结果见 `docs/design/advanced-portfolios/2026-10-10-tokenhub-validation.md`。提示词为 `portfolio-ai-2026-10-10.2`，启用思考、`temperature=1.0`、`top_p=0.95`、非流式及JSON输出；单HTTP60秒、总180秒、最多3次。本轮最慢作品集HTTP为58.808秒；23.13秒公开视频直接媒体探针输入62143 token。生产当前仍仅在文本JSON提供授权作品的COS链接，独立媒体探针不代表生产已发送视频媒体项。视频大小分流与数据万象取帧为后续独立方案，尚未实现或部署。

### 视频作品原视频摘要（2026-10-14，已部署并启用）

V65 只新增 `wf_work.video_summary_json JSON NULL`，仅 VIDEO 允许非空。保存原视频身份、输入模式、模型/提示词版本和 `summary / observations / limitations` 正文，无 TTL，不包含临时链接或原视频副本。普通作品查询、编辑和 DTO 不读取或覆盖新字段。现有 `video_audio_text` 继续保存独立 ASR 全文，原有七天 `ai_media_summary_json` 缓存仍服务图片及帧摘要。

Job 自动审核 AVERAGE/INTERVAL 的 VIDEO PASS 提交后、历史 backfill 和 rebuild 均调用原视频摘要服务。原 COS 对象经 HEAD 核验后，以单个 `video_url` 发往既有 TokenHub `glm-5.3-flash`，不先压缩、裁剪或换成关键帧，不附加转写。关键帧/ASR 普通失败不禁止独立摘要；源过期、竞争、预算不足和总期限不足不得继续。摘要失败不回退审核结果，也不清已成功的帧和文字。后补同源关键帧/转写不清原视频摘要；源改变则不得复用。

| 配置 | 所属 | 默认值与用途 |
| --- | --- | --- |
| `WEFOLIO_VIDEO_SUMMARY_ENABLED` | Job | `false`；原视频前置摘要开关，与高级作品集和 ASR 开关独立 |
| `TOKENHUB_API_KEY` | Job | 复用现有凭据，不另建或记录密钥 |
| `WEFOLIO_VIDEO_SUMMARY_MAX_VIDEO_BYTES` | Job | `100000000`；十进制 100 MB，配置硬上限 `104857600`（100 MiB），不表示供应商保证接受此整个区间 |
| `WEFOLIO_VIDEO_SUMMARY_MAX_DURATION_SECONDS` | Job | `600`；按当前作品时长做应用准入，缺失/非法时长拒绝；不是实际音轨探测或模型能力保证 |
| `WEFOLIO_VIDEO_SUMMARY_TOTAL_TIMEOUT_SECONDS` | Job | `180`；单件摘要预检及调用期限，受历史整件剩余期限收紧 |
| `WEFOLIO_VIDEO_SUMMARY_CONCURRENCY` | Job | `1`；实例内并发，另复用按作品 ID 的 MySQL 会话锁 |
| `WEFOLIO_VIDEO_SUMMARY_MAX_REQUESTS_PER_RUN` | Job | `100`；审核轮/一次历史执行的独立预留请求上限 |
| `WEFOLIO_VIDEO_SUMMARY_MAX_COST_FEN_PER_RUN` | Job | `500`；后台摘要预留费用上限，单位分，不扣用户积分 |
| `WEFOLIO_AI_MEDIA_STORED_VIDEO_SUMMARY_ENABLED` | Runtime | `false`；开启后冻结内部 `portfolio-media-v3`，命中直读，缺失走帧＋音频 |

原视频输入无法从 MB 或单个样本可靠换算 token，故按一百万级上下文的保守上界 1,048,576 输入 tokens 加最多 16,384 输出 tokens 预留，默认轻度思考 `low`。按既有广州公开价格每件向上取整预留 89 分，批次默认上限保持 500 分；可信 usage 返回后把该件预留结算为实际估算费用、释放差额，因此不再固定五次即停止。已知失败响应（包括截断）也结算计量；未知响应或未发送凭证保留完整预留，次数不返还，单件费用超过自身预留立即停止后续发送。记录真实 usage、缓存量和费用估算，思考 tokens 已包含在输出中；前置摘要不用作品集任务账本，无隐藏模型重试；401/402 等账户错误停止本轮后续摘要。详见 §4.26。

模型请求从剩余 180 秒摘要期限扣除一次 COS HEAD 的配置硬超时和 5 秒保存余量，预检耗时也计入摘要阶段；每次 HEAD 前须有足够时间完成固定超时请求，返回后再检查绝对期限。防止模型占满阶段时间导致最终源校验无法启动。

backfill 逐项检查帧、音频和原视频摘要，只缺摘要时只执行原视频调用。超过配置大小/时长上限时，保留既有资料，原视频摘要不生成。rebuild 在清空之前完成帧、ASR、摘要全部必要准入，规格或额度不满足则保留旧内容并停止在本件；准入后同一 CAS 清四类派生字段，再重建，失败不恢复旧摘要。摘要开关关闭时不阻断旧帧/音频重建，但显式 rebuild 仍清旧摘要。音频预检所持的作品锁必须先释放，摘要再领取，避免跨 JDBC 会话嵌套同名锁。历史单件缺省总期限为 540 秒（帧180＋音频180＋摘要180），包含预检；既有部署排空许可覆盖全部阶段。

v3 入队时一次读取并冻结源、摘要、帧和音频：合法持久摘要走 `STORED_VIDEO_SUMMARY`，不要求当前帧存在，也不签帧 URL/查缓存/再次请求摘要；缺失、损坏或源身份不匹配则固定走 `FRAMES`，用关键帧和音频文本生成摘要，复用现有七天缓存。兜底结果只写旧缓存，不写代表原视频理解的新字段。音频单件 6,000、整任务 24,000 码点并继续参与最终生成。无音频允许视觉摘要并说明局限；无摘要且无合法帧仍为 `MEDIA_NOT_PREPARED`，不在作品集中临时截图或 ASR。已冻结任务不因后来补摘要/文字而切换路线，源、权限或审核失效仍阻止发布。公开接口和小程序协议不变。

本地原视频实测：84.613秒、102,381,193字节的用户样本，一次请求 60.826秒，输入229,508、输出622（含157思考）、合计230,130 tokens，公开价估算0.185348元。该样本超过默认十进制100MB保护线，生产要直传此样本须显式配置大小上限104857600；成功样本不证明所有十分钟视频或100MiB边界均可处理。模型根据画面与字幕归纳，未验证原声识别能力。证据仅本地保存在 `docs/video-summary-results/2026-10-14-original-video/`；本轮业务代码测试不发送新的付费请求。

发布顺序：先由 Runtime 执行 V65（Job 不维护 Flyway），升级所有支持 v1/v2/v3 的 Job，再开启原视频摘要，最后开启 Runtime v3；历史 backfill 单独按授权执行。所有开关的代码默认值仍关闭。回退先停止创建 v3、排空在途任务，再回退消费者，保留新增可空列和数据。代码和 V65 已按 §4.24 发布，并按用户确认通过 systemd drop-in 开启摘要及 Runtime v3，服务器 `.env` 未修改。

2026-10-14 本地最终验证：Runtime 全量 2,765 项（30 项条件跳过），Job 全量 1,011 项（2 项条件跳过），均 0 失败、0 错误；小程序全量 2,666 项通过。覆盖原视频请求形态、预算及超时准入、源/审核轮次/CAS、三个入口、rebuild 清空前拒绝与锁释放、同源帧/音频保留摘要，以及 v3 直读和帧兜底。随后完成 §4.24 的生产发布与迁移，未新增真实模型调用。

### 7.1 高级作品集超时调整（2026-10-13，已部署）

生产任务 `6` 于 `2026-10-12 18:18:46.460` 提交，两个视频均读取作品已有的 `video_frames_json`，分别分析 9 张、10 张持久帧，模型调用耗时 28.331 秒和 34.910 秒。媒体分析成功后，最终页面生成未取得结果并重试，任务于当日 `18:21:46.475` 达到原 180 秒总期限，转为 `FAILED/TIMEOUT`。上述时间均为上海时间，排查仅读取日志与任务元数据。

生产默认总期限统一调整为 Runtime / Job 600 秒，Job 单次 HTTP 180 秒、中断恢复窗口 240 秒；连接仍为 5 秒，最多仍为 3 次尝试。媒体临时读取链接有效期按 `max(180 秒, 单次调用期限 + 60 秒)` 设置，为模型读取留出余量。前端继续异步轮询，无独立 180 秒总限制，不需要同步发布小程序。

总期限包含排队、媒体分析、页面生成和全部重试，不能通过重试重新计时。单次调用期限必须小于中断恢复窗口；两个 Runtime 和 Job 已于 2026-10-13 同步发布，新默认值已在线上生效，独立核验确认三服务无显式期限覆盖，详情见 §4.13。已失败任务不恢复执行，需要重新提交生成。

本地验证：Runtime 全量 2580 项，零失败/错误，30 项跳过；Job 全量 743 项，零失败/错误，2 项远端 COS 集成测试跳过；小程序高级 AI 页面及协议测试 53 项全部通过。回归覆盖超过旧 180 秒期限的继续执行、600 秒收口、HTTP 期限按剩余时间收紧及媒体链接有效期余量。已部署，未新增数据库迁移、未提交 Git；尚未验证新的真实 AI 生成结果。

### 7.2 高级作品集输出截断诊断与修复（2026-10-13，已发布）

重新生成的生产任务 `7` 于 `2026-10-13 08:28:15.489` 提交，`08:30:35.572` 以 `FAILED/OUTPUT_TRUNCATED` 结束，执行次数为 1。原描述仅 75 个 Unicode 码点；最终页面模型调用输入 8294 token、输出 8192 token，耗时 138741 毫秒，HTTP 200 但 `finish_reason=length`。该次失败由请求输出预算用尽造成，任务未触及已上线的 600 秒总期限，描述字数不能直接解释截断原因。

腾讯云官方文档将 `glm-5.3-flash` 最大输出列为 128K tokens，8192 是应用此前自行设置的请求上限。`completion_tokens` 包含思考 token；GLM-5.3-Flash 不支持关闭思考，未传 `reasoning_effort` 时默认为 `max`。官方 `thinking.budget_tokens` 仅为期望上限而非硬截断，不能依靠该字段保证给正文留下固定输出预算。来源：[GLM 调用指南](https://cloud.tencent.com/document/product/1823/132061)、[Chat Completions 公共字段](https://cloud.tencent.com/document/product/1823/135872)，核查说明保存在本地 `docs/design/advanced-portfolios/2026-10-13-tokenhub-output-limits.md`。

本轮在用户授权下进行了两次真实模型复测，均复用原任务的两个已缓存媒体摘要，没有重新取帧或重复分析视频：

| 复测请求 | 最终页面调用结果 | 协议验证结果 |
| --- | --- | --- |
| `reasoning_effort=high`、`max_tokens=8192`，沿原请求 | 40108 毫秒，HTTP 200，`finish_reason=stop`；输入 8294、输出 2509 token，其中思考 1723 token | 5 个组件，AI 中间协议校验通过，`verified=true`；未验证完整普通配置编译、保存或发布 |
| 最终配置复测：`reasoning_effort=high`、`max_tokens=16384` | 43773 毫秒，HTTP 200，`finish_reason=stop`；输入 8306、输出 2539 token，其中思考 1751 token | `AiProtocolException`，`verified=false`；未保存被拒输出原文或异常 code/message，不能据此声称完整候选通过 |

第二次复测证明当前模型接受 `high/16384` 并完整结束输出，但不构成完整业务候选验收。本轮真实调用止于以上两次，没有继续发起收费模型请求。

已发布修复将 Runtime 默认及允许的最大 `maxTokens` 调为 16384，并新增默认 `reasoningEffort=high`。新任务冻结可选的 `modelConfig.reasoningEffort`，Job 按冻结值发送；历史任务保留原 8192 预算，缺少该字段时不补传 `high`，继续沿供应商原默认行为。费用预留的页面输出预算回退值同步为 16384，媒体摘要调用预算保持 4096。模型日志增加安全的 token 细分及正文计数，用于区分思考消耗与最终内容长度，不打印描述、模型思考或被拒正文原文。

本地全量验证：Runtime 2589 项、跳过 30 项，Job 763 项、跳过 2 项，均零失败/错误；小程序相关测试 60 项全部通过。Runtime 和 Job 制品均已构建，打包命令退出码 0；现有测试与构建结果不能替代第二次真实复测尚未通过的协议验收。

本次修复已获用户明确批准并完成 Job 与 Runtime 双节点发布，详情见 §4.14。生产新任务默认页面 `max_tokens=16384`、`reasoning_effort=high`，总期限仍为 600 秒；三服务有效配置及制品核验均通过。自动审批最初以先前部署授权仅覆盖超时调整为由拒绝，取得本轮明确批准后完成发布。本次未新增数据库迁移、未提交 Git、未发布小程序；发布后未发起新的真实 AI 任务，不能据此声称完整候选、保存或发布链已验收。

脱敏证据：`/private/tmp/wefolio-task7-inspection.json`、`/private/tmp/wefolio-truncation-production-meta.json`、`/private/tmp/wefolio-task7-replay-8192-high.json`、`/private/tmp/wefolio-task7-replay-16384-high.json`、`/private/tmp/wefolio-output-ready-artifacts.json`。两次诊断完成后已清理本轮在服务器创建的私有临时目录 `/tmp/wefolio-truncation-20261013`，保留上述本地脱敏证据。

### 7.3 高级作品集行高规范化误判诊断与修复（2026-10-13，已部署）

用户重新生成的任务 `8` 于 `2026-10-13 09:16:33.633` 创建，Job 在 `09:17:53.728` 写入 `SUCCEEDED`，执行次数为 2，使用已发布的 `max_tokens=16384`、`reasoning_effort=high`。两次模型调用均完整结束，没有再次发生输出截断：

| 调用 | 请求时间 / 响应时间 | 供应商返回 |
| --- | --- | --- |
| 第一次 | `09:16:35.631` / `09:17:05.020` | `finish_reason=stop`，输入 8306、输出 1768 token，其中思考 1093 token |
| 第二次 | `09:17:13.785` / `09:17:52.770` | `finish_reason=stop`，输入 8456、输出 2629 token，其中思考 1940 token |

Runtime 于 `09:17:54.451` 在 `stage=CONFIG` 拒绝该已保存结果，错误为 `NORMALIZATION_VALUE_CHANGED`。对原结果只读隔离回放，AI 中间协议真实编译通过，共 4 个组件。原候选中只有一个显式行高，为整数 `2`；单独使用普通行高规则规范化为同值 `2.0`，旧保护拒绝该数字表示差异。该回放复现了行高误判，未对原结果运行完整普通配置规范化或渲染。

本次最小修复仅修改 `PortfolioAiConfigService`：只有字段路径以 `.lineHeight` 结尾、前后均为数字且 `BigDecimal.compareTo(...) == 0` 时，接受该数值等价的规范化。其它字段类型变化、真实数值变化和数组结构变化继续沿原保护规则拒绝，不将普通配置规范化视为任意修正许可。发布前使用原已保存结果与修复 class 的隔离叠加回放 `accepted=true`、`verified=true`；发布后使用真正已部署的 Runtime JAR、不叠加 class，再次编译原保存结果，共 4 个组件，单独重放其显式行高的普通行高规范化与保护校验，数值等价变化获接受，`verified=true`。两次回放均未修改原保存中间结果、未发起新模型请求；验证范围仅为对应行高保护，不构成完整渲染、采用、保存或发布业务验收。

新增 13 项回归测试在旧实现运行时有 9 项按预期失败，复现行高误判；修复后定向测试 112 项通过。Runtime 全量 2602 项、零失败/错误、跳过 30 项，`clean package` 退出码 0；小程序相关测试 43 项通过，未修改前端。首次全量运行的 8 个错误由沙箱拒绝本机测试 HTTP 服务的 Socket 绑定导致，解除该本机绑定限制后完整重跑通过。

现有自动修正边界：Job 对 `INVALID_OUTPUT` 可将安全错误摘要交给模型重新生成，最多 3 次执行；Runtime 在 Job 终态后进行的普通配置校验失败没有返回模型的反馈通道。本次修复消除已确认的行高误判，尚未实现覆盖 Runtime 终态普通配置失败的完整自动修复协议。

Runtime 双节点已完成发布，服务、制品、有效配置和正式入口核验通过，详见 §4.15。本次未部署 Job、未新增数据库迁移、未提交 Git、未发布小程序；诊断与原结果回放均未发起新模型请求。§4.14 与 §7.2 保留为此前输出预算修复的历史记录。

本轮服务器私有临时目录 `/tmp/wefolio-normalization-20261013` 已移除，原任务数据库结果未修改。本地脱敏证据保留：`/private/tmp/wefolio-task8-inspection.json`、`/private/tmp/wefolio-task8-normalization-replay.json`、`/private/tmp/wefolio-task8-normalization-replay-fixed.json`、`/private/tmp/wefolio-task8-normalization-replay-deployed.json`、`/private/tmp/wefolio-task8-postdeploy-verification.json`、`/private/tmp/wefolio-task8-backup-cleanup-verification.json`。

### 7.4 高级作品集输出预算扩大至 32K（2026-10-13，已部署）

用户在核对当前限制后明确要求先扩大到 32768 token。本次将 Runtime 默认 `maxTokens` 与配置允许上限、Job 冻结任务参数校验上限及缺省输出预算同步调整为 32768；先部署兼容 32K 的 Job，再发布两台 Runtime，详情见 §4.16。

`max_tokens` 包含模型思考与最终作品集 JSON，共用 32768 输出额度；默认思考深度仍为 `high`。媒体摘要每次输出预算仍为 4096，总任务期限仍为 600 秒、单次 HTTP 180 秒、恢复窗口 240 秒，最多仍为 3 次尝试。提高 token 上限不会延长时间限制，也不保证模型耗尽额度或必然通过协议及普通配置校验；任务仍可能先达到期限。

新任务创建时冻结 32768，Job 按冻结值调用和预留费用；历史任务保留原 8192 或 16384 等已冻结值，缺少思考深度字段的任务仍不补传 `high`。最终页面费用预留的缺省输出预算同步为 32768，显式冻结预算继续优先；按实际供应商 usage 结算，不能将请求上限直接当作已发生费用。任务费用、请求人每日费用及调用次数限制沿原配置保留，本轮未独立核验这些限制的线上覆盖值。

发布前 Runtime 全量 2603 项、跳过 30 项，Job 全量 765 项、跳过 2 项，均零失败/错误；相关 Runtime 98 项、Job 186 项通过。回归覆盖 32768 配置及请求边界、32769 越界拒绝、历史 16384 冻结值和费用预留缺省值。实际制品及线上有效配置核验通过，但本轮没有新的模型调用或真实 AI 任务验收，不据此声称完整业务生成链已验证。

若需恢复旧的 16K 制品，应先滚动回退 Runtime，停止新建 32K 任务，再等待已冻结 32K 的排队与执行任务全部完成或排空，最后回退 Job；不得先让只允许 16384 的旧 Job 消费仍含 32768 冻结参数的任务。回退应用前仍须核对当前任务及既有表结构兼容性，不自动回退数据库。

本次没有扩大 2 MiB 响应体/任务 JSON、256 KiB 普通作品集配置、128 KiB 上下文及深度等独立大小限制，也没有新增 Runtime 终态配置错误返回模型的完整自动修复链路；现有修复边界继续见 §7.3。未新增数据库迁移、未发布小程序、未提交 Git。此前 §7.2 的 16K 诊断记录保留为历史证据，当前输出默认与上限以本节为准。

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
| `TOKENHUB_API_KEY` | 本地 Job 技术验证 | 腾讯云 TokenHub API Key | 不记录值 | `projects/java/wefolio-java-job/.env` | 是 | 用户已在本地填写；仅 Job 和独立验证脚本读取 |
| `WEFOLIO_AI_BASE_URL` | Runtime / Job | 高级作品集 AI 官方兼容端点 | `https://tokenhub.tencentmaas.com/v1` | `application.yml` 默认值或各服务环境配置 | 否 | 启用时仅接受 TokenHub 官方端点；清除旧 DeepSeek 覆盖值 |
| `WEFOLIO_AI_MODEL` | Runtime / Job | 高级作品集 AI 模型 | `glm-5.3-flash` | `application.yml` 默认值或各服务环境配置 | 否 | 启用时固定此模型；已部署，完整业务端到端验收待新任务验证，当前修复核验见 §4.14 与 §7.2 |
| `WEFOLIO_AI_MAX_TOKENS` | Runtime 双节点 | 新建高级作品集任务的页面输出预算（含思考） | `32768` | Runtime `application.yml` 默认值或环境配置 | 否 | 2026-10-13 生产生效默认值及配置上限，两节点无显式覆盖；创建时冻结，Job 按历史任务原值执行，媒体摘要预算仍为 4096，当前发布见 §4.16 / §7.4 |
| `WEFOLIO_AI_REASONING_EFFORT` | Runtime 双节点 | 新建高级作品集任务的 GLM 思考深度 | `high` | Runtime `application.yml` 默认值或环境配置 | 否 | 2026-10-13 生产生效默认值，两节点无显式覆盖；新任务冻结可选字段，历史缺字段任务继续省略（供应商默认 max）；不得设置 thinking.type=disabled |
| `WEFOLIO_AI_TOTAL_TIMEOUT_SECONDS` | Runtime 双节点 / Job | 从提交起包含排队与全部尝试的总期限 | `600` | `application.yml` 默认值或各服务环境配置 | 否 | 2026-10-13 生产生效默认值，三服务均无显式覆盖；三服务必须一致，修改后重启生效 |
| `WEFOLIO_AI_CALL_TIMEOUT_SECONDS` | Job | 每次模型 HTTP 请求绝对期限 | `180` | `application.yml` 默认值或 Job 环境配置 | 否 | 2026-10-13 生产生效默认值，无显式覆盖；实际调用取该值与任务剩余时间的较小值 |
| `WEFOLIO_AI_RECOVERY_SECONDS` | Job | 进程中断及失去媒体心跳后的恢复窗口 | `240` | `application.yml` 默认值或 Job 环境配置 | 否 | 2026-10-13 生产生效默认值，无显式覆盖；必须大于单次调用期限，正常媒体任务每至多 10 秒续约 |
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

| 2026-10-09 | Codex | 按授权依次发布高级作品集 Runtime、执行服务器检查、发布 Job；V61 已执行，三服务健康且 AI 开关已按用户环境配置启用 | Runtime 双节点、Job 单节点、Nginx 与 V61 数据库结构；小程序未发布 | 保留 Runtime 固定旧包及 Job 带时间戳备份；恢复前核查高级数据与 V61 兼容性，不自动回退数据库 |

| 2026-10-10 | Codex | 按授权依次发布高级作品集 AI 模式清理后的 Runtime、Job 并检查服务器；双节点制品与本地一致，三服务 active/UP、字体 enabled/ready，Nginx 恢复双节点轮询，发布后未检出新的错误匹配 | Runtime 双节点、Job 单节点及 Nginx 切流；未改服务器环境或新增迁移，V61 未修改；小程序未发布 | Runtime 按既有脚本保留旧包；Job 另存 `wefolio-java-job.jar.backup-20261010-before-release`；恢复前核查高级数据与 V61 兼容性，不自动回退数据库 |

| 2026-10-12 | Codex | 按授权发布八款字体选用上下文的 Job 服务，制品与提示词摘要一致，本机和正式 HTTPS 健康及版本验证通过；发现发布前已存在的历史截图清理网络警告，新实例无 ERROR/FATAL | Job 单节点；未发布 Runtime 或小程序，未修改数据库与环境配置 | Job 旧包保留带时间戳的 `backup-font-guidance-20261012180956` 备份；恢复前核查当前任务与既有表结构兼容性 |

| 2026-10-13 | Codex | 按授权发布高级作品集超时调整：总期限 600 秒、Job 单次 HTTP 180 秒、恢复窗口 240 秒；三服务制品与有效配置独立核验通过，active/UP，本次启动后无 ERROR/FATAL，Nginx 恢复双节点轮询 | Runtime 双节点、Job 单节点及 Nginx 切流；未改服务器环境、未新增迁移，小程序未发布 | Runtime 两台固定 `.backup` 保留旧包，Job 另存 `backup-timeout-20261013-before-release`；已失败任务需重新生成，恢复前核查当前任务与既有表结构兼容性 |

| 2026-10-14 | Codex | 按授权发布 Runtime 双节点、Job 单节点并执行 V64/V65；按用户确认通过 systemd drop-in 开启原视频摘要及 Runtime v3，三服务 active/UP、Nginx 轮询正常、新启动无 ERROR/FATAL | 两个可空数据库字段及两服务摘要配置；独立 ASR 仍关闭，未手动补历史 | 先关 Runtime v3 并排空新合同任务，再回退 Job；保留新列与发布前 JAR 备份，详见 §4.24 |
| 2026-10-14 | Codex | 修复 Snapshot 日期格式、摘要截断与费用预留结算并发布 Job；安装官方签名校验的 FFmpeg 5.1.10，发布前修改实际 `.env` 开启 ASR，真实摘要和 ASR 探针通过 | Job 单节点、独立媒体工具及三项非敏感音频配置；Runtime 沿用现有 v3，未重跑历史批次 | 旧 JAR 与 `.env` 均有独立备份，回退须恢复并验证旧版本健康；详见 §4.26 |
| 2026-10-19 | Codex | 提交 `380de7e` 并发布 AI 字段说明与发起用户唯一码日志；Job 已排空换包，健康与版本核验通过，调度恢复，启动后无 ERROR/FATAL | Job 单节点；两工程契约仅更新说明，Runtime 和小程序未发布，无数据库或环境变更 | 旧包独立保留为 `backup-ai-fields-user-code-20261019-380de7e`，回退先排空再恢复并验收；详见 §4.33 |
| 2026-10-19 | Codex | 提交 `851f811` 并发布视频默认作品推荐名称；Job 排空后换包，版本、健康、制品、提示词及调度恢复核验通过，启动后无 ERROR/FATAL | Job 单节点；名称更新隔离于已提交摘要，Runtime 和小程序未发布，无数据库或环境变更 | 旧包独立保留为 `backup-video-title-20261019-851f811`，回退先排空再恢复并验收；详见 §4.34 |
| 2026-10-19 | Codex | 提交 `869cefd` 并发布作品统一排序；Runtime 双节点滚动发布完成，版本、健康、制品、备份及 Nginx 轮询核验通过，启动后无 ERROR/FATAL | Runtime 双节点；兼容旧客户端 TAG 排序，小程序仅提交代码未发布，Job 未重启，无数据库或环境变更 | 两节点固定 `.backup` 保留旧包，回退须逐节点摘流并验收；详见 §4.35 |
| 2026-10-22 | Codex | 按授权发布作品集状态筛选 Runtime；双节点制品、健康、版本、备份及 Nginx 轮询核验通过，启动后无 ERROR/FATAL | Runtime 双节点；无数据库或环境变更，Job 与小程序未发布，发布时使用工作区代码 | 两节点固定 `.backup` 保留旧包，回退须逐节点摘流并验收；详见 §4.36 |
