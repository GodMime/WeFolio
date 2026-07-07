# CLAUDE.md

本文件用于指导 Claude Code（claude.ai/code）在 `wefolio-java-job` 模块内工作。

## 模块概览

`wefolio-java-job` 是 WeFolio 的独立后台任务 Spring Boot 应用。该工程从 `wefolio-java-runtime` 拆出，是因为定时任务实例数量可能不同于 REST API 实例数量，需要独立部署、独立扩缩容。

技术栈：

| 层 | 技术选型 |
|---|---|
| 运行时 | Java 21 |
| 框架 | Spring Boot 3.5.3 |
| Web | spring-boot-starter-web |
| ORM | MyBatis-Plus 3.5.7 |
| 数据库 | MySQL 8.0 |
| 测试数据库 | H2 |
| 简化工具 | Lombok |

启动类：

```text
com.jxc.wefolio.job.WefolioJavaJobApplication
```

默认端口：`8091`。

数据库和 COS 参数参考 `wefolio-java-runtime`：数据库使用 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`，COS 使用 `COS_SECRET_ID`、`COS_SECRET_KEY`、`COS_REGION`、`COS_BUCKET_NAME`、`COS_UPLOAD_BASE_URL`、`COS_PUBLIC_BASE_URL`。本工程不配置 Flyway。

Web 接口统一通过 `server.servlet.context-path=/job-api` 增加前缀，便于 nginx 与 runtime 工程的 `/api` 路由隔离。新增 Controller 时不要使用 `/api` 前缀。

## 构建与运行

```bash
cd projects/java/wefolio-java-job

# 如果本机默认 JDK 版本较低，显式指定 JDK 21。
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn test
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn clean package -DskipTests

java -jar target/wefolio-java-job.jar
java -jar target/wefolio-java-job.jar --spring.profiles.active=prod
```

## 包结构

```text
com.jxc.wefolio.job
├── common/        # 通用响应模型
├── config/        # Spring 与 MyBatis-Plus 配置
├── controller/    # 健康检查和版本接口
├── entity/        # 后续后台任务需要直接访问的数据实体
├── mapper/        # 后续 MyBatis-Plus Mapper
├── service/       # 后续后台任务业务服务
└── task/          # 后续定时任务入口
```

## Flyway 规则

本模块禁止引入 Flyway 依赖，也禁止维护 `db/migration` 脚本。

所有 SQL 变更都必须走 `projects/java/wefolio-java-runtime/` 工程的 Flyway SQL 脚本，包括建表、改表、索引、约束、初始化数据和数据修正 SQL。

如果后台任务需要数据库结构或数据脚本变更，必须在 runtime 工程新增 `V{version}__{description}.sql` migration，并保持 job 工程只消费已经存在的表结构。

## 系统接口

```text
GET /job-api/health
GET /job-api/version
```

这些接口用于部署探活和构建版本确认。`/job-api` 来自全局 servlet context path，Controller 方法只维护自身业务路径。

## 编码规范

- Java 注释和 Javadoc 必须使用中文。
- 类、字段、方法需要补齐有意义的 Javadoc 或行内注释。
- 使用 Lombok 日志时统一使用 `@Slf4j`。
- 具有固定语义的字符串字面量必须提取为静态常量。
- 不要新增 `// TODO`。
- 后台任务必须考虑幂等性、批量大小上限、日志可观测性和安全重试。

## 验证

说明完成前运行：

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn test
```
