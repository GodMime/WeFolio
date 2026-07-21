# 后端核心链路近期性能优化设计

## 1. 背景

WeFolio 当前后端性能排查发现，访客打开作品集的核心链路存在以下高优先级风险：

- 团队作品集打开方法使用外层事务，作品集查询后继续执行微信登录换码、访问记录写入和作品集渲染，可能在远端调用期间持续占用数据库连接。
- 微信小程序 HTTP 客户端没有显式连接超时和读取超时，远端异常时请求可能长时间阻塞。
- 维护者本人访问识别通过 `wf_user_auth.open_id` 查询，但该字段缺少匹配查询条件的索引。
- `LocalCacheService` 使用无界 `ConcurrentHashMap`，过期条目仅在相同 key 再次读取时删除。访客资料短期 token 使用随机 key，容易形成长期滞留。
- 核心打开链路缺少统一的分阶段耗时日志，难以上线前后量化效果或定位剩余瓶颈。

产品需求要求作品集首屏加载时间小于 2 秒。本轮以近期可上线、低风险、不破坏现有接口契约为前提，先处理上述直接影响核心链路稳定性的问题。

## 2. 目标与非目标

### 2.1 目标

- 缩短团队作品集打开链路的数据库事务和连接占用时间。
- 为所有微信小程序 HTTP 请求设置明确、可配置的超时上限。
- 消除维护者本人识别查询的潜在全表扫描。
- 将本地缓存改为有 TTL、有容量上限的进程内缓存，避免条目无限增长。
- 建立个人、团队作品集打开链路的低开销分段耗时观测。
- 保持接口路径、HTTP 方法、请求参数、状态码、响应结构、提示文案和小程序调用行为兼容。

### 2.2 非目标

- 不引入 Redis、消息队列或新的外部中间件。
- 不重构作品集渲染查询，不处理组件级查询放大问题。
- 不改写访问统计聚合 SQL。
- 不调整 runtime 或 job 的定时任务线程池。
- 不调整数据库连接池容量、Web 容器线程数等全局运行参数。
- 不引入完整的 Actuator、Prometheus 或分布式追踪体系。

## 3. 方案选择

本轮采用“最小止血 + 稳定性加固”的合并方案：

1. 缩短团队作品集打开事务。
2. 增加微信 HTTP 超时。
3. 增加 `open_id` 联合索引。
4. 将无界本地缓存替换为有界进程内缓存。
5. 增加核心链路耗时日志和回归测试。

不采用本轮直接重构渲染、统计和调度的方案，原因是改动面更大、契约回归风险更高，且缺少生产分段耗时数据支撑优先级判断。

## 4. 详细设计

### 4.1 团队作品集打开事务拆分

保持 Controller 和接口契约不变，调整 `VisitorTeamPortfolioService.openPortfolio` 的应用流程：

1. 在事务外查询并校验已发布团队作品集。
2. 在事务外检查团队拥有者积分门禁。
3. 在事务外使用微信登录 code 换取微信会话，再通过独立短事务创建或更新访客身份。
4. 在事务外完成团队作品集基础渲染，此时尚不填充请求级的访问记录 ID。
5. 调用 `TeamPortfolioVisitService.recordOpen`，仅在该服务现有的短事务内写入访问汇总和打开事件。
6. 使用已经持久化的访问记录回填渲染对象的请求级上下文，包括 `renderData.visitRecordId`。
7. 组装响应，并同时设置顶层 `response.visitRecordId`，两处值必须一致。
8. 签发访客登录 token，并按既有条件创建访客资料 token。

移除 `VisitorTeamPortfolioService.openPortfolio` 的外层 `@Transactional`。`TeamPortfolioVisitService.recordOpen` 保留现有事务，继续保证访问汇总和打开事件写入的一致性及幂等性。

渲染提前到访问写入之前执行，以保持“渲染失败时不产生访问记录”的既有回滚语义。这里采用“先构建、后回填”的方式消除顺序依赖：`TeamPortfolioRenderService.render` 先返回可变的 `TeamPortfolioRenderDto`；访问记录成功写入后，再调用 `fillRenderContext` 把 `visitRecord.getId()` 回填到该对象。最后 `buildOpenResponse` 把同一个 ID 设置到响应顶层。这样小程序既可以从 `payload.visitRecordId` 获取，也可以从 `renderData.visitRecordId` 获取，现有兼容回退逻辑不变，后续作品浏览、视频播放和联系表单事件仍能关联正确的访问记录。

#### 4.1.1 访客身份事务拆分与部分提交语义

`VisitorService.resolveByLoginCode` 当前整体带有 `@Transactional`，微信换码发生在同一事务方法内。仅移除 `openPortfolio` 的外层事务还不足以保证远端调用期间不占用数据库连接，因此需要同时拆分该方法：

1. `VisitorService.resolveByLoginCode` 改为非事务应用入口，负责校验 login code、调用微信换码并校验返回的 openid。
2. 新增职责单一的访客身份持久化服务，例如 `VisitorIdentityPersistenceService`。
3. `VisitorService` 在微信调用成功后，把规范化的 openid、unionid 交给持久化服务。
4. 持久化服务的创建或更新方法使用 `@Transactional`，只包裹 `wf_visitor` 查询及写入。
5. 不使用同类内部方法自调用承载事务，确保 Spring 事务代理实际生效。

事务拆分后，访客身份可能先成功提交，而后续渲染或访问记录写入失败。这种部分提交是可接受的：访客是跨作品集复用的全局身份，由 `(openid, deleted)` 唯一约束保证身份唯一，后续访问可以复用已经创建的访客；访问记录仍只在作品集成功渲染后写入。该语义与当前个人作品集打开链路一致，不尝试用长事务把微信换码、访客身份和访问记录绑定成一个原子操作。

`wf_visitor` 已在 `V23__visitor_profile.sql` 中定义唯一索引 `uk_visitor_openid (openid, deleted)`。持久化服务必须处理“两个请求同时首次使用相同 openid”产生的插入竞争：

1. 第一个短事务先按 openid 查询现有未删除访客；命中时按既有规则刷新并返回。
2. 未命中时构造新访客，通过自定义 Mapper 执行单行 `INSERT IGNORE`。
3. Mapper 返回 `1` 表示创建成功，返回 `0` 表示插入被忽略；其他影响行数视为持久化异常。
4. 返回 `0` 时，第一个短事务只返回“需要竞争恢复”的内部结果，不在同一事务中再次查询。
5. 第一个短事务正常结束后，非事务入口再调用第二个短事务，使用普通 `SELECT` 按 openid 查询并发胜出的记录。
6. 如果查到胜出记录，则刷新 `lastSeenAt` 及缺失的 unionid，并以 `newVisitor=false` 返回。
7. 如果仍未查到相同 openid，说明被忽略的原因可能是极低概率的 `visitor_key` 唯一索引碰撞或其他数据问题，不得错误复用无关记录，应抛出持久化异常。

本方案不修改数据库或方法的事务隔离级别，也不使用 `SELECT ... FOR UPDATE`。不能在第一个事务中执行“首次普通查询 → `INSERT IGNORE` 返回 0 → 再次普通查询”，因为 MySQL 默认可重复读下第二次查询可能继续使用首次查询建立的旧快照。通过先结束第一个事务，再在第二个短事务中普通查询，可以读取已经提交的胜出记录，同时避免异常回滚和显式锁定读。

`INSERT IGNORE` 可能把重复键以外的部分数据错误降级为警告，因此必须在执行前严格校验 openid、unionid 的非空与数据库字段长度，且把“影响行数为 0 后按 openid 查到胜出记录”作为成功恢复的必要后置条件。项目已有 `PointBillingWindowEntityMapper.insertIgnore` 的 0/1 影响行数处理模式，本实现保持相同约定。

当前生产代码中 `resolveByLoginCode` 只有以下两个真实调用方：

| 调用方 | 拆分前事务行为 | 拆分后兼容性结论 |
|---|---|---|
| `VisitorPortfolioService.openPortfolio` | 外层无事务，`resolveByLoginCode` 独立提交访客身份 | 访客身份仍独立提交；仅把微信远端调用移到事务外，后续个人访问写入和渲染顺序不变 |
| `VisitorTeamPortfolioService.openPortfolio` | 加入团队打开方法的外层事务，后续失败会回滚访客身份 | 改为访客身份独立短事务；接受“访客已创建但本次无访问记录”的部分提交语义 |

除上述两处外，没有其他生产调用方。`VisitorServiceTest` 对该方法的直接调用属于服务单元测试，需要随事务职责拆分调整测试边界。

个人作品集打开链路当前没有同类外层大事务，本轮不调整其作品集查询、访问写入和渲染顺序；它会复用拆分后的 `VisitorService.resolveByLoginCode`，因此微信换码同样位于访客身份持久化事务之外，并接入统一超时和耗时观测。

### 4.2 微信 HTTP 超时

在 `WechatMiniappProperties` 中新增：

- `connectTimeout`：默认 1 秒。
- `readTimeout`：默认 3 秒。

在 `application.yml` 中提供对应环境变量覆盖入口。构建 `JdkClientHttpRequestFactory` 时设置连接超时和读取超时，使登录换码、手机号验证、access token 获取和其他共用 `RestWechatMiniappClient` 的微信请求统一受控。

本轮不增加自动重试。核心打开链路同步重试会进一步拉长首屏耗时，并可能在微信服务异常时放大请求量。超时继续经过现有异常日志和全局响应映射，避免额外改变错误语义。

### 4.3 `open_id` 查询索引

新增 Flyway migration：

```text
V44__add_user_auth_open_id_index.sql
```

为 `wf_user_auth` 增加联合索引：

```sql
KEY idx_wf_user_auth_open_id_deleted (open_id, deleted)
```

该索引匹配 `OwnerSelfVisitService` 按 `open_id` 查询且受 MyBatis-Plus 逻辑删除条件约束的访问模式。不得修改已经提交或执行的 `V30__add_user_auth_open_id.sql`。

上线前后使用真实 MySQL 8.0 执行 `EXPLAIN`，确认查询使用新索引。DDL 在低流量时段执行，避免元数据锁等待影响在线请求。

### 4.4 有界进程内缓存

保留 `CacheService` 接口和现有缓存 key 规则，使用 Caffeine 替换 `LocalCacheService` 内部的无界 `ConcurrentHashMap`。Caffeine 是应用进程内 Java 依赖，不需要部署或维护外部服务，符合“不引入新的中间件”的约束。

在 `wefolio-java-runtime/pom.xml` 新增直接依赖：

```xml
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

项目使用的 Spring Boot 3.5.3 依赖管理已经锁定 Caffeine 3.2.1，因此不在模块 POM 中重复声明版本。该依赖为兼容 Java 21 的 Caffeine 3.x 版本。

本轮不启用 Spring Cache 抽象：不增加 `@EnableCaching`，不引入 `spring-boot-starter-cache`，也不声明 `CacheManager`。Caffeine 仅作为 `LocalCacheService` 的内部数据结构使用；`LocalCacheService` 继续保留 `@Service`、Bean 名称和 `CacheService` 接口，因此现有注入点不变化。当前工程本身也不存在 Spring Cache Bean，不会与该实现发生候选 Bean 冲突。

变量 TTL 采用单结构方案：内部使用 `Cache<String, CacheEntry>`，`CacheEntry` 同时保存缓存值和该次写入指定的 TTL；通过 Caffeine `Expiry<String, CacheEntry>` 为每个条目返回独立过期时长。不得额外维护 key 到过期时间的第二张 Map，避免双结构一致性和额外内存问题。

缓存设计要求：

- 继续支持每条记录在 `put` 时指定独立 TTL。
- 设置可配置的最大条目数，默认 50,000 条。
- 达到上限时按 Caffeine 淘汰策略回收条目，避免堆内存无限增长。
- 保持 `get` 的类型校验、空值校验和 key 校验语义。
- 保持 `evict` 的显式失效能力。
- 记录低频、聚合的淘汰信息，不为每次缓存访问打印日志。

缓存条目包括维护者 token 解析结果、访客 token 解析结果、访客资料短期 token 和微信 access token。容量设置需要优先避免有效资料 token 被提前淘汰；默认值上线后根据淘汰计数和 JVM 堆使用调整。缓存淘汰不得导致安全绕过：解析缓存未命中时仍走现有校验流程，资料 token 未命中时仍按现有无效 token 处理。

### 4.5 核心链路耗时日志

耗时采集和汇总日志均放在 Service 层：个人链路接入 `VisitorPortfolioService.openPortfolio`，团队链路接入 `VisitorTeamPortfolioService.openPortfolio`。Controller 保持纯 HTTP 适配，不增加计时或结果分类逻辑。两个 Service 入口使用 `try-finally` 保证成功返回、维护模式提前返回和异常退出都只记录一条汇总日志。

汇总日志至少包含：

- `portfolioType`
- `portfolioLookupMs`
- `pointGateMs`
- `wechatLoginMs`
- `visitorPersistMs`
- `renderMs`
- `visitWriteMs`
- `totalMs`
- `outcome`

日志不得包含分享码、微信登录 code、token、openid、手机号等敏感数据。失败请求或总耗时超过 2 秒的请求打印 `WARN`；未超时的正常请求按配置采样打印 `INFO`，未被采样的正常请求不打印汇总日志，避免高流量下日志本身成为性能问题。每个请求最多打印一条汇总日志。

未执行的阶段耗时记为 `null`，不记为 `0`，从而与真实但不足 1 毫秒的阶段区分。例如维护模式包含作品集查询、积分门禁和维护页渲染，`wechatLoginMs=null`、`visitWriteMs=null`。

`outcome` 使用内部固定枚举，至少包含：

| outcome | 含义 |
|---|---|
| `SUCCESS` | 正常打开并完成访问写入 |
| `OWNER_SELF` | 维护者本人打开个人作品集，按既有规则不写访客访问记录 |
| `MAINTENANCE` | 积分门禁未通过，返回维护遮罩 |
| `PORTFOLIO_UNAVAILABLE` | 作品集不存在、未发布或不可访问 |
| `WECHAT_TIMEOUT` | 微信调用连接或读取超时 |
| `WECHAT_FAILED` | 微信非超时失败或返回无效会话 |
| `VISITOR_PERSIST_FAILED` | 访客身份短事务失败 |
| `RENDER_FAILED` | 作品集渲染失败 |
| `VISIT_WRITE_FAILED` | 访问汇总或打开事件写入失败 |
| `UNEXPECTED_FAILED` | 未归类的异常 |

耗时统一通过单调时钟计算。当前执行阶段由 Service 内部记录，异常继续沿用现有抛出和响应映射，不为日志分类吞掉或转换异常。

### 4.6 配置项

`application.yml` 使用以下固定配置名和环境变量入口：

```yaml
wechat:
  miniapp:
    connect-timeout: ${WECHAT_MINIAPP_CONNECT_TIMEOUT:1s}
    read-timeout: ${WECHAT_MINIAPP_READ_TIMEOUT:3s}

cache:
  local:
    max-size: ${LOCAL_CACHE_MAX_SIZE:50000}

wefolio:
  performance:
    portfolio-open:
      slow-threshold: ${PORTFOLIO_OPEN_SLOW_THRESHOLD:2s}
      normal-sample-rate: ${PORTFOLIO_OPEN_NORMAL_SAMPLE_RATE:0.01}
```

对应配置属性必须校验：两个微信超时均大于 0，本地缓存容量大于 0，正常日志采样率位于 0 到 1 之间。配置类、日志和测试统一使用上述名称，不再引入同义配置键。

## 5. 测试设计

### 5.1 事务与流程测试

- 团队作品集正常打开时，验证调用顺序为微信身份解析、访客身份短事务持久化、基础渲染、访问短事务写入、访问 ID 回填和响应组装。
- 渲染失败时，验证不调用访问写入服务。
- 访问写入失败时，验证不返回部分成功响应。
- 验证渲染成功后写入访问记录，再将相同的访问记录 ID 回填到 `renderData.visitRecordId` 和顶层 `response.visitRecordId`。
- 验证微信客户端调用发生在访客身份持久化事务之外，访客查询和写入发生在独立短事务内。
- 验证访客身份已经提交但访问写入失败时，后续相同 openid 可以复用既有访客。
- 并发模拟相同 openid 首次创建，验证 `INSERT IGNORE` 返回 0 后先结束首次事务，再由第二个短事务普通查询并返回胜出访客，且最终只有一条未删除访客记录。
- 模拟非 openid 原因的唯一约束冲突，验证不会错误复用无关访客。
- 验证全流程保持项目默认事务隔离级别，不使用锁定读。
- 维护模式、无效访客、幂等 key 等既有分支响应保持不变。
- 验证 `TeamPortfolioVisitService.recordOpen` 仍以事务方式原子写入访问汇总和打开事件。

### 5.2 微信超时测试

- 验证默认超时和环境变量覆盖能正确绑定。
- 使用可控的延迟 HTTP 服务验证连接/读取超时生效。
- 验证超时日志经过现有脱敏逻辑，不输出敏感 URL 查询参数。
- 验证非超时的成功、HTTP 错误和微信业务错误响应语义不变。

### 5.3 缓存测试

- 不同 TTL 的条目分别按各自过期时间失效。
- 超过最大条目数后，缓存规模保持在上限内。
- `get`、`put`、`evict` 及类型不匹配行为与现有接口一致。
- 覆盖并发读写和过期清理场景。
- 验证 Caffeine `Expiry` 按每个 `CacheEntry` 的独立 TTL 过期，不维护第二份过期映射。
- 验证 Spring 上下文中只有一个 `CacheService` 候选 Bean，且不存在自动配置的 `CacheManager` 依赖。
- 验证维护者 token、访客 token、资料 token 和微信 access token 的缓存未命中分支保持安全、可恢复。

### 5.4 数据库与契约测试

- 在 MySQL 8.0 上用 `EXPLAIN` 验证 `open_id` 查询命中新索引。
- 运行 runtime 全量 Maven 测试。
- 运行作品集访客 Controller/Service 契约测试。
- 检索并核对 `projects/miniapp/` 中个人和团队作品集打开接口的真实调用，确认响应字段和错误处理无需修改。
- 运行小程序团队作品集规范化测试，验证顶层 `visitRecordId` 优先、`renderData.visitRecordId` 兼容回退的行为不变。

### 5.5 耗时日志测试

- 正常个人和团队打开记录 `SUCCESS`，各已执行阶段具有非负耗时。
- 维护模式记录 `MAINTENANCE`，微信和访问写入阶段为 `null`，维护页渲染耗时存在。
- 微信超时、渲染失败和访问写入失败分别记录对应 outcome，且原异常继续向上抛出。
- 每次打开请求最多产生一条汇总日志，敏感字段不进入日志参数。
- 慢请求使用 `WARN`；正常请求按配置采样，采样率为 0 和 1 的边界行为可验证。

## 6. 验收标准

- 接口路径、请求和响应契约保持兼容，小程序无需同步发布。
- 模拟微信接口延迟时，团队打开链路不在等待远端响应期间持续占用数据库连接。
- 微信请求超过配置阈值后及时结束，不出现无期限等待。
- `open_id` 查询使用新增联合索引。
- 大量写入随机短期 token 并等待过期后，缓存条目数不超过配置上限，堆使用不随历史请求数无限增长。
- 在微信正常响应和具有代表性的请求集下，作品集打开目标为 P95 小于 2 秒。
- 如果测试环境不足以稳定复现生产负载，相同请求集相对优化前基线不得退化，并能从分段耗时日志识别主要耗时。
- 所有新增及既有相关测试通过后才允许发布。

## 7. 发布与观察

1. 在低流量时段启动带有 `V44` migration 的新版本。
2. 优先单实例或小流量发布。
3. 观察微信超时数量、作品集慢请求、数据库连接池活跃连接、JVM 堆使用和缓存淘汰数量。
4. 确认错误率和接口耗时无异常后再完成全量发布。
5. 保留上线前后的相同请求集数据和结构化日志作为效果对比依据。

本轮不同时调整连接池、Web 线程池或调度线程池参数，避免多个变量叠加导致问题难以归因。

## 8. 回滚方案

- 应用代码、微信超时配置和缓存实现可以回滚到上一版本。
- 新增索引是向后兼容变更；应用回滚时保留索引，不修改或回滚已执行的 Flyway migration。
- 如果默认超时过紧，优先通过环境变量放宽，无需重新发版。
- 如果缓存淘汰过多，优先通过环境变量提高最大条目数。
- 回滚后继续观察数据库连接、微信请求和 JVM 堆，确认指标恢复稳定。

## 9. 风险与控制

| 风险 | 控制措施 |
|---|---|
| 调整调用顺序导致渲染数据缺少访问记录 ID | 访问写入后回填同一渲染对象，并断言顶层与渲染层 ID 一致 |
| 访客身份提交后访问写入失败 | 接受全局访客独立提交语义，验证相同 openid 后续可复用且失败请求不写访问记录 |
| 相同 openid 并发首次创建触发唯一约束 | 使用 `INSERT IGNORE` 返回内部竞争结果；首个短事务结束后，由第二个短事务普通查询胜出记录 |
| `INSERT IGNORE` 降级非重复键数据错误 | 写入前校验字段长度；影响行数为 0 后必须按 openid 查到胜出记录，否则失败 |
| 微信超时过紧导致失败率上升 | 使用环境变量配置，先小流量发布，可即时放宽 |
| 缓存达到容量后提前淘汰有效资料 token | 使用较大默认容量、观察淘汰计数，并保留快速调大配置能力 |
| 新索引 DDL 等待元数据锁 | 低流量执行，提前检查长事务和锁等待 |
| 耗时日志增加 I/O | 单条汇总、慢请求优先、正常请求采样且严格脱敏 |
| 无生产基线导致效果不可量化 | 上线前保存相同请求集基线，上线后使用相同条件对比 |
