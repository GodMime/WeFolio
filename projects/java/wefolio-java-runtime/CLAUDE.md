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

**配置**: `application.yml` 中通过环境变量注入数据库连接信息，服务端口 8090。MyBatis-Plus 开启了 SQL 日志和自动驼峰映射。

## 包约定

```
com.jxc.wefolio
├── annotation/    # 四类访问控制注解（@LoginAccess / @SystemAccess / @MaintainerAccess / @VisitorAccess）
├── common/        # 通用响应封装 Response<T>、认证上下文 AuthContextHolder
│   └── auth/      # 认证上下文（AuthContext、AuthContextHolder）
├── config/        # 配置类（MyBatisPlus、COS、Properties、AuthAspect 认证切面）
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

> **⚠️ Flyway 铁律**：已提交到 Git 的 migration 文件绝对不可修改。Flyway 通过 checksum 校验已执行的脚本，任何改动都会导致启动失败。所有数据库变更必须通过新增 V4、V5… 文件实现。

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

- ✅ 允许使用 `// TODO` 注释标记待完成事项
- ❌ 不允许出现英文注释（Javadoc/字段/方法注释统一使用中文）
- ❌ 不允许出现无注释的类、字段、方法（含测试类）
- ❌ 不允许在业务逻辑中使用硬编码的状态/类型字符串（如 `"ACTIVE"`、`"DISABLED"`），必须使用对应枚举字典
- ❌ 不允许在代码中使用完全限定类名（如 `com.jxc.wefolio.entity.WorkEntity`），必须 import 后使用短名
- ❌ 不允许在业务代码中硬编码具有固定语义的字符串字面量（如前缀 `"WF"`/`"TM"`、配置键、类型标识等），必须提取为 `public static final` 常量并引用

### 状态字段与枚举字典

**所有表示状态、类型、渠道等固定值域的字段，必须使用 `com.jxc.wefolio.dict` 包下的枚举字典类**，禁止在业务代码中硬编码 magic string。

枚举字典类规范：

```java
/**
 * 用户状态字典 — ACTIVE 正常 / DISABLED 停用
 */
public class UserStatusDict {

    /** 正常 */
    public static final DictValue ACTIVE = new DictValue("ACTIVE", "正常");

    /** 停用 */
    public static final DictValue DISABLED = new DictValue("DISABLED", "停用");

    /** 从 code 反查，未匹配时返回空 */
    public static Optional<DictValue> fromCode(String code) { ... }

    /** 字典值 */
    public record DictValue(String code, String displayName) {
        public String getCode() { return code; }
        public String getDisplayName() { return displayName; }
    }
}
```

**实体字段注释必须包含 `@see` 指向对应枚举字典**，方便 IDE 一键跳转：

```java
/**
 * 账号状态。
 *
 * @see UserStatusDict
 */
private String status;

/**
 * 作品媒体类型。
 *
 * @see MediaTypeDict
 */
private String mediaType;
```

`@see` 也可与 `{@link}` 内联配合使用，增强可读性：

```java
/** 账号状态，取值见 {@link UserStatusDict} */
private String status;
```

**业务代码中比较状态必须使用枚举常量**，禁止硬编码字符串：

```java
// ✅ 正确 — 使用枚举字典
if (UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) { ... }

// ❌ 错误 — 硬编码字符串
if ("ACTIVE".equals(user.getStatus())) { ... }
```

**MyBatis-Plus 查询条件中同样适用此规则**——LambdaQuery 的 `.eq()` 也是业务逻辑，不可豁免：

```java
// ✅ 正确 — LambdaQuery 中使用枚举字典
.eq(UserEntity::getStatus, UserStatusDict.ACTIVE.getCode())
.eq(WorkEntity::getStatus, WorkStatusDict.ACTIVE.getCode())

// ❌ 错误 — 查询条件中硬编码字符串
.eq(UserEntity::getStatus, "ACTIVE")
.eq(WorkEntity::getStatus, "ACTIVE")
```

> **为什么**：如果某天 ACTIVE 的 code 改为 `"ENABLED"`，枚举常量只需改一处，而硬编码字符串需要全项目搜索替换，极易遗漏导致线上故障。

### 注解与 AOP 切面

#### 四类访问控制注解

**所有 Controller 接口方法（或其所在类）必须显式标记以下四类注解之一**，不允许存在未标记的接口。启动时 `ControllerAnnotationValidator` 会扫描校验，未标记将阻止启动。

| 注解 | 行为 | 适用场景 |
|------|------|----------|
| `@LoginAccess` | 跳过认证，直接放行 | 微信登录、登录态校验等 |
| `@SystemAccess` | 跳过认证，直接放行 | 健康检查、版本号等 |
| `@MaintainerAccess` | 要求有效 Authorization 令牌 | 所有维护者后台接口 |
| `@VisitorAccess` | 当前跳过认证（后续实现独立访客认证） | 作品集展示页等访客接口 |

```java
/**
 * 登录类接口标记 — 标注在登录相关接口上。
 * 该类接口不要求 Authorization 请求头。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginAccess {
}
// @SystemAccess、@MaintainerAccess、@VisitorAccess 结构相同，仅语义不同
```

使用示例：

```java
// 整个 Controller 统一标记（如健康检查、维护者后台）
@SystemAccess
@RestController
public class VersionController { ... }

@MaintainerAccess
@RestController
public class MineController { ... }

// 同一 Controller 内不同方法标记不同注解
@LoginAccess
@PostMapping("/maintainer/wechat-login")
public Response<MaintainerWechatLoginResponse> maintainerWechatLogin(...) { ... }

@MaintainerAccess
@PostMapping("/avatar")
public Response<FileUploadResponse> uploadAvatar(...) { ... }
```

> **类级 vs 方法级优先级**：方法级注解优先于类级注解。若类标记了 `@MaintainerAccess` 但某方法标记了 `@LoginAccess`，该方法按 `@LoginAccess` 处理。

#### `AuthAspect` 认证切面

位于 `com.jxc.wefolio.config.AuthAspect`，拦截 `com.jxc.wefolio.controller..*` 包下所有 Controller 方法，根据访问控制注解执行对应认证逻辑。

**执行流程**：
1. 检查方法/类是否有 `@LoginAccess` / `@SystemAccess` / `@VisitorAccess` → 有则直接放行
2. 检查方法/类是否有 `@MaintainerAccess` → 进入维护者认证流程
3. 从 `HttpServletRequest` 获取 `Authorization` 请求头
4. 调用 `AuthTokenService.resolveAuthenticatedUserId()` 解析用户身份（带 10 分钟缓存）
5. 认证通过 → 写入 `AuthContextHolder`（ThreadLocal），执行 Controller 方法
6. `finally` 块中清理 `AuthContextHolder`

**新增 Controller 须知**：
- **所有接口必须显式标记**四类注解之一，启动时会自动校验
- **不要在 Controller 包下放置非 Controller 的 `@Component`/`@Service` 类**，切面会拦截其方法调用

#### `ControllerAnnotationValidator` 启动校验器

位于 `com.jxc.wefolio.config.ControllerAnnotationValidator`，在 `ApplicationReadyEvent` 时扫描所有 `@RestController` Bean 的方法，确保每个 HTTP 映射方法（`@GetMapping` / `@PostMapping` 等）都标记了四类访问控制注解之一。未标记的方法会以 `IllegalStateException` 阻止启动，并在日志中列出具体方法名。

#### `AuthContextHolder` 认证上下文

基于 `ThreadLocal` 的当前请求认证信息持有器，Service 层通过它获取当前登录用户：

```java
// 必须已登录时使用（会抛出 BusinessException("用户未登录")）
Long userId = AuthContextHolder.requireUserId();

// 可选登录时使用
Optional<Long> userId = AuthContextHolder.getUserId();
```

**线程安全约定**：
- `AuthContextHolder.set()` 仅在 `AuthAspect` 切面中调用，业务代码只需读取
- 单元测试中需在 `@BeforeEach` 设置上下文、`@AfterEach` 清理：

```java
@BeforeEach
void setUp() {
    AuthContextHolder.set(new AuthContext(7L, "test-token"));
}

@AfterEach
void tearDown() {
    AuthContextHolder.clear();
}
```

#### `AuthTokenService` 令牌认证服务

负责解析 `Authorization` 请求头并校验用户状态，带 10 分钟本地缓存。**service 层不应直接调用此服务**（认证统一由切面处理），仅在 `AuthAspect` 和特殊端点（如 `session()` 需同时支持登录/未登录）中使用。

### `@Transactional` 自调用限制

**Spring 的 `@Transactional` 依赖 AOP 代理**，同一个类内部的方法调用不会经过代理，导致 `@Transactional` 失效。这是 Spring 的经典陷阱。

```java
// ❌ 错误 — 自调用绕过代理，事务不生效
public void publicMethod() {
    this.transactionalMethod();  // 直接调用，无事务
}

@Transactional
public void transactionalMethod() { ... }
```

**解决方案：将需要事务保护的方法提取到独立的 Service 中**，通过注入调用，确保经过 Spring 代理：

```java
// ✅ 正确 — 提取到独立 Service
@Service
@RequiredArgsConstructor
public class UserRegistrationService {

    private final UserEntityMapper userEntityMapper;

    @Transactional(rollbackFor = Exception.class)
    public UserEntity createWechatUser(...) {
        // 此方法的事务由 Spring AOP 代理管理
    }
}

// 调用方注入独立 Service
@Service
@RequiredArgsConstructor
public class MiniappAuthService {

    private final UserRegistrationService userRegistrationService;

    public void loginMaintainerByWechat(...) {
        // ✅ 通过注入的代理调用，事务生效
        user = userRegistrationService.createWechatUser(...);
    }
}
```

> **为什么不用 `@Autowired @Lazy self` 自注入？** 自注入模式虽然能工作，但会让代码难以测试（测试中需要手动 `service.self = service`），且新人容易误用 `this.xxx()` 导致 bug。提取独立 Service 是最干净的解法。

### 注释增强规范

#### `@see` 与 `{@link}` 交叉引用

**`@see` 标注在字段上**，指向对应的枚举字典类，IDE 可一键跳转：

```java
/**
 * 账号状态。
 *
 * @see UserStatusDict
 */
private String status;

/**
 * 作品媒体类型。
 *
 * @see MediaTypeDict
 */
private String mediaType;

/**
 * 关联用户 ID。
 *
 * @see UserEntity
 */
private Long userId;
```

**`{@link}` 用于内联**，在注释文字中直接嵌入类引用：

```java
/** 账号状态，取值见 {@link UserStatusDict} */
private String status;
```

**`@see` 用在类注释上**，标注关联的服务或工具类：

```java
/**
 * 基础信息服务 — 负责维护者个人资料读取与保存。
 *
 * @see MineDashboardService 我的首页服务
 * @see UserStatusDict       用户状态枚举
 */
@Service
public class MineProfileService { ... }
```

#### 测试类注释

测试类、Mock 字段、测试方法均需添加中文注释（与生产代码同等要求）：

```java
/**
 * 基础信息服务单元测试 — 覆盖资料读取、保存、标签校验、JSON 异常处理。
 */
@ExtendWith(MockitoExtension.class)
class MineProfileServiceTest {

    /** 用户资料 Mapper 模拟 */
    @Mock
    private UserEntityMapper userEntityMapper;

    /** 在每个测试前注入模拟的登录上下文 */
    @BeforeEach
    void setUp() { ... }

    /**
     * 获取基础信息页资料 — 返回所有可编辑字段及解析后的标签列表。
     */
    @Test
    void profileContainsEditableBasicInformation() { ... }
}
```

此模块是 WeFolio 多项目仓库的子目录 `projects/java/wefolio-java-runtime/`。仓库级架构、设计文档和编码规范见根目录 `CLAUDE.md`。
