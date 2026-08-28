# AGENTS.md

本文件为 Codex 在 WeFolio 仓库内工作的项目指南。仓库中已有 `CLAUDE.md`，如两者冲突，以用户最新指令为准，并优先遵守更具体目录下的说明。

## 项目概览

WeFolio（映期Folio）是面向婚庆、演艺从业者的 SaaS 微信小程序，用于维护作品、档期、作品集展示页，并追踪客户访问记录。

仓库为多项目结构：

| 目录 | 类型 | 说明 |
|---|---|---|
| `projects/java/wefolio-java-runtime/` | Spring Boot 3.5.3 后端 | REST API 服务 |
| `projects/java/wefolio-java-job/` | Spring Boot 3.5.3 后台任务 | 独立部署的定时任务服务，不引入 Flyway |
| `projects/miniapp/` | 微信小程序 | Skyline 渲染引擎 + glass-easel 组件框架 |
| `projects/ai/` | 预留 | AI 能力模块 |
| `docs/` | 文档 | PRD、数据库模型设计、技术台账 |
| `design/` | 设计稿 | 线框原型与部署脚本 |

整体架构：

```text
微信小程序
  -> Spring Boot REST API (:8090)
    -> MySQL 8.0
    -> 腾讯云 COS

Spring Boot Job (:8091)
  -> MySQL 8.0
```

`wefolio-java-job` 的 Web 接口统一使用 `/job-api` 前缀，不使用 runtime 工程的 `/api` 前缀。当前探活和版本接口为 `GET /job-api/health`、`GET /job-api/version`。

## 常用命令

Java 后端：

```bash
cd projects/java/wefolio-java-runtime

# 本机默认可能是 JDK 17；项目需要 JDK 21
mvn test
mvn clean package -DskipTests
java -jar target/wefolio-java-runtime.jar
```

Java 后台任务：

```bash
cd projects/java/wefolio-java-job

# 本机默认可能是 JDK 17；项目需要 JDK 21
mvn test
mvn clean package -DskipTests
java -jar target/wefolio-java-job.jar
```

如果 `jdk21` 别名不可用，可显式设置：

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn test
```

微信小程序：

```text
用微信开发者工具打开 projects/miniapp/
AppID: wxa214c25850cdf268
```

部署脚本：

```bash
cd projects/java/wefolio-java-runtime
bash deploy.sh
```

## 后端约定

后端模块位于 `projects/java/wefolio-java-runtime/`，包名为 `com.jxc.wefolio`。

主要分层：

```text
common/        通用响应封装
config/        配置类
controller/    控制器
dict/          枚举字典
entity/        MyBatis-Plus 实体
exception/     全局异常处理
mapper/        MyBatis-Plus Mapper
service/       业务服务
```

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

编码规范：

- Java 代码注释必须使用中文。
- 类、字段、方法需要补齐有意义的 Javadoc 或行内注释。
- 使用 Lombok 日志时统一使用 `@Slf4j`。
- 调用远端接口时，应打印关键原始入参和出参，便于排查线上问题。
- 远端日志中如果包含 `secret`、`access_token`、手机号、openid 等敏感值，排查结束后应考虑脱敏或降低日志级别。
- 不要新增 `// TODO`。
- 具有固定语义的字符串字面量（如前缀、配置键、类型标识等）必须提取为静态常量，禁止硬编码。

## COS 文件夹结构

用户注册时在 COS 存储桶下按个人唯一码创建目录，路径固定不可变：

```text
{uniqueCode}/                  ← 例：WFA3B1E7A2
├── work/
│   ├── image/                 ← 图片类作品（.jpg/.png 等）
│   └── video/                 ← 视频类作品（.mp4/.mov 等）
├── protfolio/                 ← 作品集素材（封面/背景/布局快照）
└── others/                    ← 头像、微信二维码、其它杂项
```

**上传路径约定：**
- 图片作品 → `{uniqueCode}/work/image/{uuid}.{ext}`
- 视频作品 → `{uniqueCode}/work/video/{uuid}.{ext}`
- 头像 → `{uniqueCode}/others/{uuid}.{ext}`
- 作品集素材 → `{uniqueCode}/protfolio/{uuid}.{ext}`

**实现细节：**
- 文件夹通过 0 字节空对象（Content-Type: application/x-directory）模拟
- 注册时 `MiniappAuthService` → `CosService.initUserStorage(uniqueCode)` 一次性创建全部文件夹
- 初始化失败会阻断注册，`MiniappAuthService` 会抛出业务异常并终止注册流程
- `CosService.upload(file, folderPrefix)` 用于上传到指定目录

## 数据库约定

- 表前缀为 `wf_`。
- 所有表统一包含 `id`、`created_at`、`updated_at`、`deleted`、`version`。
- 逻辑删除字段为 `deleted`，唯一索引必须包含 `deleted`，以允许删除后重建。
- 枚举字段使用 `VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin`，Java 侧用 `String` 对接 `XxxDict`。
- 金额以分为单位，积分为整数，不使用浮点数。
- 敏感字段如手机号、微信 openid 应按设计做加密或摘要，不应明文随意扩散。

Flyway 规则：

- migration 位于 `projects/java/wefolio-java-runtime/src/main/resources/db/migration/`。
- `projects/java/wefolio-java-job/` 不引入 Flyway，不维护 migration。
- 所有 SQL 变更都必须走 `projects/java/wefolio-java-runtime/` 工程的 Flyway SQL 脚本，包括建表、改表、索引、约束、初始化数据和数据修正 SQL。
- 已提交或已执行的 migration 绝对不要修改。
- 数据库变更必须新增 `V{version}__{description}.sql`。
- **禁止在 migration 中写入"重置/清理"类破坏性数据操作**（如 `DELETE` 全表、`UPDATE` 清零余额/流水）；数据重置与清理只能在对应环境手工执行并留痕，不得以 Flyway migration 形式进入迁移链。

## 小程序约定

- 小程序源码位于 `projects/miniapp/`。
- 登录页相关文件位于 `projects/miniapp/pages/login/`。
- 网络请求封装位于 `projects/miniapp/utils/request.js`。
- 登录态封装位于 `projects/miniapp/utils/session.js`。
- 保持与现有 Skyline/glass-easel 写法一致。

### JavaScript 包边界约束

- `projects/miniapp/utils/` 属于主包，只允许存放能够从 `app.js`、主包页面或主包组件的真实生产代码到达的 JavaScript；测试文件引用不算主包使用。
- 仅供单一分包使用的 JavaScript 必须放在该分包根目录内，工具模块统一优先放到 `{subPackage.root}/utils/`，禁止为了复用而把分包专用文件放进主包。
- 同一逻辑仅被多个分包使用、主包没有真实调用方时，各分包保留本地实现，并像现有分包工具一样增加一致性测试；分包不得跨目录引用另一个分包的源码。
- 新增或移动小程序 JavaScript 前，必须根据 `app.json` 的 `pages`、`subPackages` 和真实 `require()` 调用确认文件归属。禁止添加无业务意义的主包 `require()`、空调用或修改 `ignoreDevUnusedFiles`、`ignoreUploadUnusedFiles` 来绕过质量检查。
- 完成小程序 JavaScript 改动前，至少运行 `cd projects/miniapp && node --test tests/business-subpackages.test.js`；任务完成前仍须按测试约定运行相关测试和完整 `node --test tests/*.test.js`。

## Mock 体验版约定

- mock 体验版必须完全独立于正式维护者端页面和组件；除登录页的“体验”入口和跳转外，不要改动既有正式页面或正式组件来承载 mock 行为。
- mock 页面统一放在 `projects/miniapp/pages/mock/`，mock 专用组件统一放在 `projects/miniapp/components/mock/`，mock 数据、作品集 JSON 和本地草稿逻辑统一放在 `projects/miniapp/utils/mock-experience.js` 或同级 mock 专用文件。
- mock 页面禁止引入 `projects/miniapp/utils/request.js`、`projects/miniapp/utils/session.js`、上传工具或访客会话工具；禁止调用 `wx.request`、`wx.uploadFile`、`/api/`、维护者 token/session 或任何后端接口。
- mock 的档期、作品、素材库、作品集、编辑、预览、“我的”等数据只使用本地 mock 数据或本地草稿态；新增、保存草稿、发布、档位操作等需要后端身份的动作统一提示 `请去“我的”页面注册登录`。
- mock 页面可以复用不需要改动的展示组件和样式；如果复用会导致修改正式页面或正式组件，则新建 mock 专用组件。
- mock 中用到头像的地方统一使用 `https://cdn2.we-folio.dingchenyong.top/demo/demo-avatar.png`。

## 测试与验证

后端改动优先运行：

```bash
cd projects/java/wefolio-java-runtime
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn test

cd projects/java/wefolio-java-job
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn test
```

小程序工具类或布局改动可运行对应 Node 测试：

```bash
cd projects/miniapp
node --test tests/*.test.js
```

在说明“已修复”“已完成”“测试通过”前，必须先运行能证明该结论的命令，并阅读退出码与测试汇总。

## 安全与工作区

- 未经用户在当前任务中明确指示，禁止执行 `git commit`；完成代码修改或验证不等于获得提交授权，不得因流程、技能或“完成任务”自动创建提交。
- `projects/java/wefolio-java-runtime/.env` 和 `projects/java/wefolio-java-job/.env` 含敏感配置，禁止提交。
- 不要打印、复制或提交真实密钥，除非用户明确要求用于排查并确认风险。
- 工作区可能已有用户未提交改动；不要回滚或覆盖与当前任务无关的改动。
- 编辑文件时保持改动范围小，优先遵循现有结构和命名风格。
