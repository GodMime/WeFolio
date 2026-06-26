# AGENTS.md

本文件为 Codex 在 WeFolio 仓库内工作的项目指南。仓库中已有 `CLAUDE.md`，如两者冲突，以用户最新指令为准，并优先遵守更具体目录下的说明。

## 项目概览

WeFolio（映期Folio）是面向婚庆、演艺从业者的 SaaS 微信小程序，用于维护作品、档期、作品集展示页，并追踪客户访问记录。

仓库为多项目结构：

| 目录 | 类型 | 说明 |
|---|---|---|
| `projects/java/wefolio-java-runtime/` | Spring Boot 3.5.3 后端 | REST API 服务 |
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
```

## 常用命令

Java 后端：

```bash
cd projects/java/wefolio-java-runtime

# 本机默认可能是 JDK 17；项目需要 JDK 21
mvn test
mvn clean package -DskipTests
java -jar target/wefolio-java-runtime.jar
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
- 已提交或已执行的 migration 绝对不要修改。
- 数据库变更必须新增 `V{version}__{description}.sql`。

## 小程序约定

- 小程序源码位于 `projects/miniapp/`。
- 登录页相关文件位于 `projects/miniapp/pages/login/`。
- 网络请求封装位于 `projects/miniapp/utils/request.js`。
- 登录态封装位于 `projects/miniapp/utils/session.js`。
- 保持与现有 Skyline/glass-easel 写法一致。

## 测试与验证

后端改动优先运行：

```bash
cd projects/java/wefolio-java-runtime
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn test
```

小程序工具类或布局改动可运行对应 Node 测试：

```bash
cd projects/miniapp
node --test tests/*.test.js
```

在说明“已修复”“已完成”“测试通过”前，必须先运行能证明该结论的命令，并阅读退出码与测试汇总。

## 安全与工作区

- `projects/java/wefolio-java-runtime/.env` 含数据库、COS、微信 AppSecret 等敏感配置，禁止提交。
- 不要打印、复制或提交真实密钥，除非用户明确要求用于排查并确认风险。
- 工作区可能已有用户未提交改动；不要回滚或覆盖与当前任务无关的改动。
- 编辑文件时保持改动范围小，优先遵循现有结构和命名风格。
