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
| ORM | MyBatis-Plus 3.5.7（分页、乐观锁、逻辑删除插件已配置） |
| DB | MySQL 8.0，通过 Flyway 管理 migration（`src/main/resources/db/migration/`） |
| 简化 | Lombok |
| 对象存储 | 腾讯云 COS |

**启动入口**：`com.jxc.wefolio.WefolioJavaRuntimeApplication`，标准 `@SpringBootApplication`。

**配置**: `application.yml` 中通过环境变量注入数据库连接信息，服务端口 8080。MyBatis-Plus 开启了 SQL 日志和自动驼峰映射。

## 包约定

```
com.jxc.wefolio
├── common/        # 通用响应封装 Response<T>
├── config/        # 配置类（MyBatisPlus、COS、Properties）
├── controller/    # 控制器
├── dict/          # 枚举字典类（XxxDict），含 code + displayName + fromCode()
├── entity/        # MyBatis-Plus 实体类（XxxEntity），全部继承 BaseEntity
├── exception/     # 全局异常处理
├── mapper/        # MyBatis-Plus Mapper 接口（XxxEntityMapper），继承 BaseMapper<XxxEntity>
└── service/       # 业务服务

resources/
├── application.yml
├── logback-spring.xml
└── db/migration/
    ├── V1__init_database.sql              # Flyway 初始化标记
    ├── V2__create_all_tables.sql          # 全量 24 张表 DDL
    └── V3__add_user_isolation_fields.sql  # 用户隔离字段 + 唯一索引修正
```

## 数据库

所有表前缀 `wf_`，MySQL 8.0，InnoDB，utf8mb4，ROW_FORMAT=DYNAMIC。

### 全量 24 张表

| 表名 | 实体类 | 说明 |
|---|---|---|
| wf_user | UserEntity | 用户资料与账号状态 |
| wf_user_auth | UserAuthEntity | 微信/手机验证码/密码登录身份 |
| wf_referral_relation | ReferralRelationEntity | 首次注册推荐关系 |
| wf_work | WorkEntity | 图片和视频作品主数据 |
| wf_tag | WfTagEntity | 用户自定义作品标签 |
| wf_work_tag | WorkTagEntity | 作品与标签多对多关联 |
| wf_slot_definition | SlotDefinitionEntity | 可复用档位定义（中午档/晚间档等） |
| wf_schedule | ScheduleEntity | 具体日期档期的预约状态 |
| wf_team | TeamEntity | 团队主数据与当前拥有者 |
| wf_team_member | TeamMemberEntity | 成员角色/邀请状态/内容引用权限 |
| wf_portfolio | PortfolioEntity | 作品集当前生效配置 |
| wf_portfolio_history | PortfolioHistoryEntity | 每次保存的只读历史快照 |
| wf_portfolio_reference | PortfolioReferenceEntity | 当前配置中的资源引用明细 |
| wf_ai_generation_task | AiGenerationTaskEntity | 高级作品集 AI 生成任务 |
| wf_portfolio_share_record | PortfolioShareRecordEntity | 分享行为记录 |
| wf_visit_record | VisitRecordEntity | 访客对作品集的访问汇总 |
| wf_visit_event | VisitEventEntity | 每次访问行为的明细事件 |
| wf_contact_lead | ContactLeadEntity | 访客预留联系线索 |
| wf_point_account | PointAccountEntity | 用户积分余额与累计值 |
| wf_point_rule | PointRuleEntity | 可配置/按时间生效的积分规则 |
| wf_point_meter | PointMeterEntity | 阶梯计费的累计计量器 |
| wf_point_transaction | PointTransactionEntity | 不可变积分流水账本 |
| wf_recharge_package | RechargePackageEntity | 可配置充值档位 |
| wf_recharge_order | RechargeOrderEntity | 微信支付充值订单 |

### 表级约定

| 约定 | 说明 |
|---|---|
| 主键 | `BIGINT UNSIGNED AUTO_INCREMENT`，`Long` + `@TableId(type = IdType.AUTO)` |
| 时间字段 | `DATETIME(3)` → `LocalDateTime`，`DATE` → `LocalDate`，`TIME` → `LocalTime` |
| 枚举字段 | `VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin` + `CHECK` 约束，Java 用 `String` |
| 枚举字典 | `com.jxc.wefolio.dict.XxxDict`，含 `code`、`displayName`、`fromCode()` |
| JSON 字段 | MySQL `JSON` 类型，Java 用 `String` |
| 金额 | 以分为单位整数 `amount_fen` |
| 积分 | 整数，不使用浮点数 |
| 全部表共有 | `id`, `created_at`, `updated_at`, `deleted`, `version` — 由 `BaseEntity` 统一管理 |

### BaseEntity 公共基类

所有实体继承 `BaseEntity`，包含 5 个公共字段：

| 字段 | 类型 | 注解 | 说明 |
|---|---|---|---|
| id | Long | `@TableId(type = IdType.AUTO)` | 自增主键 |
| createdAt | LocalDateTime | — | 创建时间 |
| updatedAt | LocalDateTime | — | 更新时间 |
| deleted | Integer | `@TableLogic` | 逻辑删除（0 未删除 / 1 已删除） |
| version | Integer | `@Version` | 乐观锁版本号 |

### MyBatis Plus 插件

```java
// MyBatisPlusConfig — 注册顺序：
// 1. OptimisticLockerInnerInterceptor  — @Version 自动 version+1
// 2. PaginationInnerInterceptor        — 分页自动拦截
```

## 数据迁移

```bash
# Flyway 在启动时自动执行 db/migration/ 下的 SQL
# 命名格式：V{version}__{description}.sql
# 已开启 baseline-on-migrate

# 当前 migration：
V1__init_database.sql              # 初始化标记表
V2__create_all_tables.sql          # 全量 24 张业务表
V3__add_user_isolation_fields.sql  # wf_work_tag/wf_visit_event/wf_portfolio_share_record 追加用户隔离字段，6 个唯一索引补 deleted
```

## 编码规范

### 注释

**⚠️ 所有代码必须包含完善的中文注释**，具体格式：

- **类注释**：`/** 表名 — 中文描述 — 补充说明 */`
- **字段注释**：`/** 中文含义 + 枚举值说明（如有） */`
- **方法注释**：`/** 功能描述，参数/返回值说明 */`
- **枚举值**：`VALUE("VALUE", "中文显示名称")`

示例：

```java
/**
 * wf_tag — 作品标签表 — 用户自定义标签，支持颜色标记
 */
@Data
@TableName("wf_tag")
public class WfTagEntity extends BaseEntity {

    /** 标签所属用户 ID */
    private Long userId;

    /** 状态：ACTIVE 启用 / DISABLED 停用 */
    private String status;
}
```

### 禁止事项

- ❌ 不允许出现 `// TODO` 注释
- ❌ 不允许出现英文注释（Javadoc/字段/方法注释统一使用中文）
- ❌ 不允许出现无注释的类、字段、方法

此模块是 WeFolio 多项目仓库的子目录 `projects/java/wefolio-java-runtime/`。仓库级架构、设计文档和编码规范见根目录 `CLAUDE.md`。
