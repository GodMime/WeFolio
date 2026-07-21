# CLAUDE.md

本文件用于指导 Claude Code（claude.ai/code）在本仓库内工作。

## 仓库概览

WeFolio（映期Folio）— 面向婚庆/演艺从业者的 SaaS 微信小程序。帮助个人与团队维护作品、档期、作品集展示页，追踪客户访问记录。

GitHub: `GodMime/WeFolio`，主分支 `main`，工作分支 `dev`。

**多项目仓结构：**

| 目录 | 类型 | 说明 |
|------|------|------|
| `projects/java/wefolio-java-runtime/` | Spring Boot 3.5.3 后端 | REST API 服务，详见该目录下的 `CLAUDE.md` |
| `projects/java/wefolio-java-job/` | Spring Boot 3.5.3 后台任务 | 独立部署的定时任务服务，不引入 Flyway，详见该目录下的 `CLAUDE.md` |
| `projects/miniapp/` | 微信小程序 | Skyline 渲染引擎 + glass-easel 组件框架 |
| `projects/ai/` | 预留 | AI 能力模块（当前为空） |
| `docs/` | 文档 | PRD、数据库模型设计、技术台账 |
| `design/` | 设计稿 | 27 页线框原型 + 可视化测试 |

## 构建与运行

### Java 后端

```bash
cd projects/java/wefolio-java-runtime

# 先切 JDK 21（系统默认 17）
jdk21

# 编译（跳过测试）
mvn clean package -DskipTests

# 运行测试
mvn test

# 本地运行（需要 .env 中配置数据库和 COS 凭证）
# .env 通过 IDE 或手动 source 注入环境变量
java -jar target/wefolio-java-runtime.jar
```

`.env` 文件位于 `projects/java/wefolio-java-runtime/.env`，包含 MySQL 和腾讯云 COS 凭证。**注意：此文件含敏感信息，切勿提交到 Git。**

### Java 后台任务

```bash
cd projects/java/wefolio-java-job

# 先切 JDK 21（系统默认 17）
jdk21

# 编译（跳过测试）
mvn clean package -DskipTests

# 运行测试
mvn test

# 本地运行（需要数据库环境变量）
java -jar target/wefolio-java-job.jar
```

`wefolio-java-job` 用于后台定时任务，默认端口 `8091`，Web 接口统一使用 `/job-api` 前缀，健康检查和版本接口为 `/job-api/health`、`/job-api/version`。

### 微信小程序

用微信开发者工具打开 `projects/miniapp/` 目录。AppID: `wxa214c25850cdf268`。

## Mock 体验版规则

- mock 体验版必须完全独立于正式维护者端页面和组件；除登录页的“体验”入口和跳转外，不要改动既有正式页面或正式组件来承载 mock 行为。
- mock 页面统一放在 `projects/miniapp/pages/mock/`，mock 专用组件统一放在 `projects/miniapp/components/mock/`，mock 数据、作品集 JSON 和本地草稿逻辑统一放在 `projects/miniapp/utils/mock-experience.js` 或同级 mock 专用文件。
- mock 页面禁止引入 `projects/miniapp/utils/request.js`、`projects/miniapp/utils/session.js`、上传工具或访客会话工具；禁止调用 `wx.request`、`wx.uploadFile`、`/api/`、维护者 token/session 或任何后端接口。
- mock 的档期、作品、素材库、作品集、编辑、预览、“我的”等数据只使用本地 mock 数据或本地草稿态；新增、保存草稿、发布、档位操作等需要后端身份的动作统一提示 `请去“我的”页面注册登录`。
- mock 页面可以复用不需要改动的展示组件和样式；如果复用会导致修改正式页面或正式组件，则新建 mock 专用组件。
- mock 中用到头像的地方统一使用 `https://cdn2.we-folio.dingchenyong.top/demo/demo-avatar.png`。

### 设计稿部署

```bash
cd design
bash deploy.sh   # 上传 prototype.html 到 marry.dingchenyong.top
```

### Java 后端部署

```bash
cd projects/java/wefolio-java-runtime
bash deploy.sh    # Maven 构建 + SCP 上传 + systemctl 重启
```

目标服务器: `49.235.146.161`，服务名 `wefolio.service`。

## 架构

```
微信小程序 (Skyline/glass-easel)
        │
        ▼
Spring Boot REST API (:8090)
        │
     ┌──┴──┐
     ▼     ▼
  MySQL  腾讯云 COS
  (8.0)  (对象存储)

Spring Boot 后台任务 (:8091)
        │
        ▼
      MySQL
      (8.0)
```

**runtime 技术栈：** Spring Boot 3.5.3 / Java 21 / MyBatis-Plus 3.5.7 / Flyway / MySQL 8.0 / 腾讯云 COS

**job 技术栈：** Spring Boot 3.5.3 / Java 21 / MyBatis-Plus 3.5.7 / MySQL 8.0 / Lombok；不引入 Flyway。

**包结构：** `com.jxc.wefolio` — controller / service / entity / mapper / dict / config / common / exception

详情见 `projects/java/wefolio-java-runtime/CLAUDE.md`，包含完整的 24 张表清单、实体映射、枚举字典规范。

### Controller 分层约束

本约束同时适用于 `wefolio-java-runtime` 和 `wefolio-java-job`：

- Controller 只负责 HTTP 适配：声明路由与访问控制注解、绑定请求参数、读取当前身份、调用 Service 层入口，以及把应用结果映射为 `Response`、`ResponseEntity`、HTTP 状态码或响应头。
- Controller 可以记录请求与响应日志，但不得在日志代码中夹带业务判断；敏感信息必须按现有规则脱敏。
- Controller 禁止包含业务校验、业务规则判断、状态流转、数据解析或规范化、金额或日期计算、时间与随机值生成、文件对象键生成、重试、幂等、事务和多服务编排。
- Controller 禁止直接依赖 Mapper、Entity、`CosService`、远端 Client 或其它基础设施组件，不得直接读写数据库、COS 或调用远端接口。
- Controller 禁止捕获异常后转换业务文案；业务异常与应用结果由 Service 层产生，Controller 仅负责映射为 HTTP 表达。
- 单一业务能力优先下沉到现有 Service；一个接口涉及多个步骤或多个 Service 编排时，可新增 `XxxApplicationService`。ApplicationService 负责用例编排，但不得依赖 `Response`、`ResponseEntity`、`HttpStatus` 等 HTTP 响应类型。
- 为保持既有响应语义，Service 层可以返回明确的应用结果类型，Controller 只根据结果类型映射成功响应、失败响应和 HTTP 状态，不得再次推导业务结果。
- 从 Controller 下沉逻辑属于结构重构，不得改变接口路径、HTTP 方法、请求参数、状态码、响应结构、提示文案、校验顺序、异常语义和日志行为。
- 新增或修改 Controller 时，必须检查其依赖字段和私有方法；如果出现业务组件直连、数据转换、分支编排或业务辅助方法，应继续下沉到 Service 层后再结束任务。
- 业务分支与边界条件必须在 Service 或 ApplicationService 测试中覆盖；Controller 测试只验证参数委派、访问控制注解和 HTTP 响应映射。

### 前后端兼容性约束

- 后端发布不得依赖小程序同步发布。默认必须兼容线上已发布的小程序版本，不能以“前端稍后适配”作为后端破坏性修改的前提。
- 未经用户在当前任务中明确授权破坏性升级，禁止删除或重命名既有接口、修改 HTTP 方法、访问控制、请求字段名称或类型、字段必填性、默认值、HTTP 状态码、`Response` 包装结构、响应字段名称或既有业务语义。
- 新增请求字段必须可选或具有兼容旧客户端的服务端默认值；新增响应字段不得影响旧客户端解析。需要替换字段时必须保留旧字段和旧行为，经过明确的版本迁移窗口后才能移除。
- 错误码、关键提示文案、空值语义、列表分页结构及时间、金额等字段格式可能已被前端判断或展示，修改前必须检索小程序调用方和现有测试，不得仅凭后端实现推断其可以改变。
- 纯后端重构必须保证旧请求仍可使用、旧响应仍可解析、原业务流程无需前端改版即可继续运行；重构期间不得顺带清理看似多余但可能被旧版本依赖的兼容逻辑。
- 完成接口改动前，必须检索 `projects/miniapp/` 中的真实调用，补充或运行对应的后端契约测试与小程序测试。无法确认兼容性时，应保留原契约并向用户说明风险，不得自行采用破坏性方案。

## COS 文件夹结构

用户注册时在 COS 存储桶下按个人唯一码创建目录，用于分类存储文件：

```
{unique_code}/                  ← 用户根目录（例：WFA3B1E7A2）
├── work/
│   ├── image/                  ← 图片类作品
│   └── video/                  ← 视频类作品
├── protfolio/                  ← 作品集额外素材（封面/背景等）
└── others/                     ← 头像、微信二维码等其它素材
```

- 文件夹通过 0 字节空对象（Content-Type: application/x-directory）模拟
- 初始化逻辑：`CosService.initUserStorage(uniqueCode)`，在 `MiniappAuthService.createWechatUser()` 中触发
- 失败不影响注册主流程
- 上传接口：`CosService.upload(MultipartFile, String folderPrefix)` 传入前缀路径

## 数据库约定

- 表前缀 `wf_`，InnoDB，utf8mb4，ROW_FORMAT=DYNAMIC
- 枚举字段：`VARCHAR(32) ascii_bin` + `CHECK` 约束，Java 用 `String` 对接 `XxxDict` 枚举字典
- 金额以分为单位（`amount_fen`），积分为整数
- 全部表统一 `id` / `created_at` / `updated_at` / `deleted` / `version`，由 `BaseEntity` 管理
- 逻辑删除：`deleted` TINYINT（0/1），唯一索引必须包含 `deleted` 列以允许删除后重建
- 乐观锁：`version` INT，MyBatis-Plus `@Version` 自动 +1
- 不使用外键，通过服务层事务 + 唯一索引 + 巡检保证一致性
- 敏感字段（手机号、微信 openid）应用层信封加密，等值查询用 HMAC-SHA256 摘要
- Flyway migration：已提交 Git 的脚本**绝对不可修改**（checksum 校验），所有变更必须追加新 V 版本文件
- 所有 SQL 变更都必须走 `projects/java/wefolio-java-runtime/` 工程的 Flyway SQL 脚本，包括建表、改表、索引、约束、初始化数据和数据修正 SQL。
- `wefolio-java-job` 不维护 migration；如后台任务需要数据库变更，仍在 `wefolio-java-runtime` 新增 Flyway migration。

## 编码规范

- **所有注释使用中文**（类/字段/方法 Javadoc 及行内注释）
- 类注释格式：`/** 表名 — 中文描述 — 补充说明 */`
- 枚举字典格式：`VALUE("VALUE", "中文显示名称")`
- 具有固定语义的字符串字面量（如前缀、配置键、类型标识等）必须提取为静态常量，禁止硬编码
- 禁止：英文注释、无注释的类/字段/方法

## Git 操作约束

- 未经用户在当前任务中明确指示，禁止执行 `git commit`；完成代码修改或验证不等于获得提交授权，不得因流程、技能或“完成任务”自动创建提交。

## 设计文档

| 文档 | 说明 |
|------|------|
| `docs/PRD.md` (v1.3) | 产品需求文档，角色定义、核心流程、业务规则 |
| `docs/database-model-design.md` (v1.4) | 数据库模型设计，24 张表完整 DDL + 索引 + 数据规则 |
| `docs/TECHNICAL_RUNBOOK.md` | 服务器/域名/数据库/缓存/监控等技术配置台账 |
| `design/prototype.html` | 27 页交互线框原型 |
