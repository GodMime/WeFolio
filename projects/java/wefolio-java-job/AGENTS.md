# AGENTS.md

本文件适用于 `projects/java/wefolio-java-job/` 及其子目录，是 Codex 在 WeFolio Java 后台任务模块内工作的目录级指南。仓库根目录还有 `AGENTS.md`，如两者冲突，以用户最新指令优先；本文件只补充和细化后台任务模块规则。

## 模块概览

`wefolio-java-job` 是 WeFolio 的独立后台任务 Spring Boot 应用，使用 Java 21、Spring Boot 3.5.3、Maven、MyBatis-Plus、MySQL 和 Lombok。

该工程用于承载后续后台定时任务。由于任务实例数量和 `wefolio-java-runtime` 的 REST API 实例数量可能不同，本工程必须独立部署、独立扩缩容。

启动入口：

```text
com.jxc.wefolio.job.WefolioJavaJobApplication
```

默认服务端口为 `8091`，配置主要位于 `src/main/resources/application.yml`，敏感配置通过环境变量注入。

数据库和 COS 参数参考 `wefolio-java-runtime`：数据库使用 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`，COS 使用 `COS_SECRET_ID`、`COS_SECRET_KEY`、`COS_REGION`、`COS_BUCKET_NAME`、`COS_UPLOAD_BASE_URL`、`COS_PUBLIC_BASE_URL`。本工程不配置 Flyway。

Web 接口通过 `server.servlet.context-path=/job-api` 统一增加前缀，便于 nginx 和 runtime 工程的 `/api` 路由隔离。job 工程新增 Controller 时不要再使用 `/api` 前缀。

## 常用命令

在执行命令前先进入模块目录：

```bash
cd projects/java/wefolio-java-job
```

项目需要 JDK 21。本机如果没有 `jdk21` 别名，优先使用显式 `JAVA_HOME`：

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn test
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn clean package -DskipTests
```

如果环境中已有 JDK 21：

```bash
mvn test
mvn clean package -DskipTests
java -jar target/wefolio-java-job.jar
java -jar target/wefolio-java-job.jar --spring.profiles.active=prod
```

## 代码结构

主包名为 `com.jxc.wefolio.job`：

```text
common/        通用响应封装
config/        Spring 与 MyBatis-Plus 配置
controller/    系统接口控制器
entity/        后续后台任务需要直接访问的数据实体
mapper/        MyBatis-Plus Mapper
service/       后续后台任务业务服务
task/          后续定时任务入口
```

资源目录：

```text
src/main/resources/
├── application.yml
└── logback-spring.xml
```

## Flyway 约束

`wefolio-java-job` **不引入 Flyway**，也不维护 `db/migration/`。

所有 SQL 变更都必须走 `projects/java/wefolio-java-runtime/` 工程的 Flyway SQL 脚本，包括建表、改表、索引、约束、初始化数据和数据修正 SQL。

后台任务工程只消费已经存在的表结构；如需调整数据库，必须在 runtime 工程新增 `V{version}__{description}.sql` migration。

**禁止在 migration 中写入"重置/清理"类破坏性数据操作**（如 `DELETE` 全表、`UPDATE` 清零余额/流水）；数据重置与清理只能在对应环境手工执行并留痕，不得以 Flyway migration 形式进入迁移链。

## Web 接口

本工程作为 Web 进程启动，用于部署探活和版本确认：

```text
GET /job-api/health
GET /job-api/version
```

`/job-api` 来自全局 servlet context path，Controller 方法只维护自身业务路径。

除系统探活类接口外，新增业务接口前需要先确认是否真的应由后台任务服务暴露。

## 编码规范

- Java 代码注释必须使用中文。
- 类、字段、方法需要补齐有意义的 Javadoc 或行内注释。
- 使用 Lombok 日志时统一使用 `@Slf4j`。
- 固定语义的字符串字面量必须提取为静态常量，禁止硬编码。
- 不要新增 `// TODO`。
- 后台任务访问远端接口时，应打印关键原始入参和出参，敏感信息必须脱敏或降低日志级别。
- 后台任务涉及数据修正、批处理或补偿逻辑时，必须优先考虑幂等性、批量大小、失败重试和日志可追踪性。

## 测试与验证

后台任务工程改动优先运行：

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn test
```

测试约定：

- 新增定时任务时，优先抽出可单测的 Service，任务入口只负责调度和日志。
- 涉及数据库读写时，优先使用 H2 或 mock 验证核心逻辑，不访问真实线上数据库。
- 在说明“已完成”“测试通过”前，必须先运行能证明结论的命令，并确认退出码和测试汇总。

## 安全与工作区

- 不要提交真实数据库、COS、微信或其它第三方密钥。
- 不要修改、回滚或覆盖用户未要求处理的改动。
- 编辑范围保持小，优先遵循 runtime 工程已有结构、命名和测试风格。
