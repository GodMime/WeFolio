# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# 切换到 Java 21（系统默认是 17）
jdk21

# 编译
mvn clean package -DskipTests

# 执行测试
mvn test

# 运行可执行 JAR
java -jar target/wefolio-java-runtime-0.0.1-SNAPSHOT.jar

# 指定 profile 运行
java -jar target/wefolio-java-runtime-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

> **注意**：`mvnw` wrapper 脚本尚未生成，需用系统 `mvn`。运行 Spring Boot 官方文档建议执行 `mvn wrapper:wrapper` 生成 wrapper。

## Architecture

Spring Boot 3.5.3 单体应用，Java 21，Maven 构建。

| 层 | 技术选型 |
|---|---|
| Web | spring-boot-starter-web |
| ORM | MyBatis-Plus 3.5.7（分页插件已配置，Mapper 扫描路径 `com.jxc.wefolio.mapper`） |
| DB | MySQL，通过 Flyway 管理 migration（`src/main/resources/db/migration/`） |
| 简化 | Lombok |

**启动入口**：`com.jxc.wefolio.WefolioJavaRuntimeApplication`，标准 `@SpringBootApplication`。

**配置**: `application.yml` 中硬编码了开发数据库连接信息（腾讯云 MySQL `wefolio_dev`），服务端口 8080。MyBatis-Plus 开启了 SQL 日志和自动驼峰映射。

**包约定**（参考 MyBatis-Plus 配置）：
- Entity: `com.jxc.wefolio.entity`
- Mapper: `com.jxc.wefolio.mapper`
- Mapper XML: `classpath:mapper/**/*.xml`

**数据库迁移**：Flyway 在启动时自动执行 `db/migration/` 下的 SQL 文件（命名格式 `V{version}__{description}.sql`）。已开启 `baseline-on-migrate`，允许已有表时首次接入。

## Repo Context

此模块是 WeFolio 多项目仓库的子目录 `projects/java/wefolio-java-runtime/`，对应 GitHub 仓库 `GodMime/WeFolio`。主分支 `main`，当前工作分支 `dev`。
