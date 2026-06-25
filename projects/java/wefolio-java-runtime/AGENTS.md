# AGENTS.md

本文件适用于 `projects/java/wefolio-java-runtime/` 及其子目录，是 Codex 在 WeFolio Java 后端模块内工作的目录级指南。仓库根目录还有 `AGENTS.md`，如两者冲突，以用户最新指令优先；本文件只补充和细化后端模块规则。

## 模块概览

`wefolio-java-runtime` 是 WeFolio 的 Spring Boot 3.5.3 后端单体应用，使用 Java 21、Maven、MyBatis-Plus、Flyway、MySQL 8.0 和腾讯云 COS，对微信小程序提供 REST API。

启动入口：

```text
com.jxc.wefolio.WefolioJavaRuntimeApplication
```

默认服务端口为 `8090`，配置主要位于 `src/main/resources/application.yml`，敏感配置通过环境变量或本目录 `.env` 注入。

## 常用命令

在执行后端命令前先进入模块目录：

```bash
cd projects/java/wefolio-java-runtime
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
java -jar target/wefolio-java-runtime.jar
java -jar target/wefolio-java-runtime.jar --spring.profiles.active=prod
```

当前项目未提交 Maven Wrapper，使用系统 `mvn`。

## 代码结构

主包名为 `com.jxc.wefolio`：

```text
annotation/          四类访问控制注解
common/              通用响应封装、认证上下文、缓存抽象
common/auth/         AuthContext、AuthContextHolder、AuthorizationHeaderUtils
common/cache/        CacheService、本地缓存实现
config/              Spring 配置、MyBatis-Plus、COS、认证切面、Controller 校验器
controller/          REST Controller
dict/                状态、类型、渠道等枚举字典
dto/                 请求和响应 DTO
entity/              MyBatis-Plus 实体，统一继承 BaseEntity
exception/           业务异常、认证异常、全局异常处理
mapper/              MyBatis-Plus Mapper
service/             业务服务、远端客户端封装
```

资源目录：

```text
src/main/resources/
├── application.yml
├── logback-spring.xml
├── mapper/
└── db/migration/
```

## 数据库与 Flyway

数据库表统一使用 `wf_` 前缀，MySQL 8.0、InnoDB、`utf8mb4`、`ROW_FORMAT=DYNAMIC`。

表级约定：

- 所有表统一包含 `id`、`created_at`、`updated_at`、`deleted`、`version`。
- 主键使用 `BIGINT UNSIGNED AUTO_INCREMENT`，Java 使用 `Long` 和 `@TableId(type = IdType.AUTO)`。
- `deleted` 为逻辑删除字段，唯一索引必须包含 `deleted`，以允许删除后重建。
- 时间字段映射：`DATETIME(3)` -> `LocalDateTime`，`DATE` -> `LocalDate`，`TIME` -> `LocalTime`。
- 枚举字段使用 `VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin`，Java 侧用 `String` 搭配 `dict` 包字典类。
- JSON 字段使用 MySQL `JSON`，Java 侧用 `String`。
- 金额以分为单位，积分为整数，不使用浮点数。

当前 migration 位于 `src/main/resources/db/migration/`：

```text
V1__init_database.sql
V2__create_all_tables.sql
V3__add_user_isolation_fields.sql
V4__add_wechat_registration_profile.sql
V5__fix_base_entity_columns.sql
```

Flyway 规则：

- 已提交或已执行的 migration 绝对不要修改。
- 数据库变更必须新增 `V{version}__{description}.sql`。
- 新增唯一索引时确认是否需要包含 `deleted`。
- 新增枚举值域时同步 SQL 约束、`dict` 字典类、实体字段注释和测试。

## 实体与字典

所有实体继承 `BaseEntity`，公共字段由基类统一管理：

```text
id          Long           @TableId(type = IdType.AUTO)
createdAt   LocalDateTime
updatedAt   LocalDateTime
deleted     Integer        @TableLogic
version     Integer        @Version
```

固定值域必须使用 `com.jxc.wefolio.dict` 包下的字典类，禁止在业务逻辑、查询条件或测试断言中散落硬编码状态字符串。

正确写法：

```java
if (UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
    // ...
}

query.eq(UserEntity::getStatus, UserStatusDict.ACTIVE.getCode());
```

实体字段注释需要通过 `@see` 或 `{@link}` 指向对应字典，便于 IDE 跳转：

```java
/**
 * 账号状态。
 *
 * @see UserStatusDict
 */
private String status;
```

## 访问控制与认证

所有 Controller 接口方法或所在类必须显式标记以下四类访问控制注解之一，未标记会被 `ControllerAnnotationValidator` 在启动时拦截：

| 注解 | 行为 | 适用场景 |
|---|---|---|
| `@LoginAccess` | 跳过认证 | 微信登录、登录态校验等 |
| `@SystemAccess` | 跳过认证 | 健康检查、版本号等系统接口 |
| `@MaintainerAccess` | 要求有效维护者令牌 | 小程序维护者后台接口 |
| `@VisitorAccess` | 当前跳过认证 | 作品集展示页等访客接口 |

方法级注解优先于类级注解。若同一 Controller 内有不同认证要求，在方法上明确标注。

认证由 `AuthAspect` 统一处理：

- `@LoginAccess`、`@SystemAccess`、`@VisitorAccess` 直接放行。
- `@MaintainerAccess` 从 `Authorization` 请求头解析维护者身份。
- 认证通过后写入 `AuthContextHolder`。
- `finally` 中清理 `AuthContextHolder`。

Service 层获取当前用户应读取认证上下文：

```java
Long userId = AuthContextHolder.requireUserId();
Optional<Long> optionalUserId = AuthContextHolder.getUserId();
```

`AuthTokenService` 原则上只由认证切面和少量特殊端点调用，普通业务服务不要直接解析令牌。

不要在 `controller` 包下放置非 Controller 的 `@Component` 或 `@Service`，避免被切面误拦截。

## 事务约定

Spring `@Transactional` 依赖 AOP 代理，同类自调用不会触发事务。需要事务保护的方法不要通过 `this.xxx()` 调用。

推荐做法：

- 将事务方法提取到独立 Service。
- 调用方通过构造器注入该 Service。
- 使用 `@Transactional(rollbackFor = Exception.class)` 明确回滚策略。

不优先采用 `@Autowired @Lazy self` 自注入模式，因为它降低可测试性，也容易被后续维护误用。

## COS 存储约定

用户注册时按个人唯一码初始化固定目录：

```text
{uniqueCode}/
├── work/
│   ├── image/
│   └── video/
├── protfolio/
└── others/
```

上传路径约定：

- 图片作品：`{uniqueCode}/work/image/{uuid}.{ext}`
- 视频作品：`{uniqueCode}/work/video/{uuid}.{ext}`
- 头像：`{uniqueCode}/others/{uuid}.{ext}`
- 作品集素材：`{uniqueCode}/protfolio/{uuid}.{ext}`

目录通过 0 字节空对象模拟，`Content-Type` 为 `application/x-directory`。注册时 `MiniappAuthService` 调用 `CosService.initUserStorage(uniqueCode)` 初始化，失败应阻断注册流程。

## 编码规范

- Java 代码注释必须使用中文。
- 类、字段、方法需要补齐有意义的 Javadoc 或行内注释；测试类、Mock 字段和测试方法也适用。
- 字段注释涉及实体关联或枚举值域时，使用 `@see` 或 `{@link}` 指向对应类。
- 使用 Lombok 日志时统一使用 `@Slf4j`。
- 远端接口调用需要记录关键原始入参和出参，便于线上排查。
- 日志涉及 `secret`、`access_token`、手机号、openid、Authorization 等敏感信息时，必须脱敏或降低日志级别，不要提交真实密钥。
- 不要新增 `// TODO`；需要记录后续事项时，写入任务说明、issue 或用户可见文档。
- 不要在业务逻辑中硬编码状态、类型、渠道字符串，使用 `dict` 字典类。
- 不要在代码中使用完全限定类名，优先 import 后使用短名。
- 新增 DTO、实体、服务或 Controller 时，沿用现有命名、包结构和响应封装。

## 测试与验证

后端改动优先运行：

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn test
```

测试约定：

- Controller 新增接口时补充认证注解覆盖和必要的 Web 层测试。
- Service 改动优先补充单元测试，涉及认证上下文时在 `@BeforeEach` 设置、`@AfterEach` 清理。
- 数据结构、字典和事务约定已有结构性测试，新增规则时同步扩展对应测试。
- 远端微信/COS 调用在测试中使用 mock，不访问真实外部服务。

在说明“已完成”“已修复”“测试通过”前，必须先运行能证明结论的命令，并确认退出码和测试汇总。

## 安全与工作区

- 本目录 `.env` 含数据库、COS、微信 AppSecret 等敏感配置，禁止提交、打印或复制到回复中。
- 不要修改、回滚或覆盖用户未要求处理的改动。
- 数据库 migration、部署脚本和认证逻辑改动风险较高，提交前需要重点复查。
- 编辑范围保持小，优先遵循现有结构、命名和测试风格。
