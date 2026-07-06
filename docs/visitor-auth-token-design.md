# 访客登录态与 `@VisitorAccess` 认证方案

日期：2026-07-06

## 背景

当前访客页通过 `POST /api/visitor/portfolios/{shareCode}/open` 调用 `wx.login()` 生成的 `loginCode`，后端据此创建或复用全局访客，并返回 `visitorKey`。后续访客端接口仍主要依赖请求体中的 `visitorKey` 串联事件、档期查询和线索提交。

目标是把 `/open` 调整为访客登录入口：该接口使用 `@LoginAccess` 放行，返回加密访客登录态和时间信息；其它 `@VisitorAccess` 接口统一由切面校验访客登录态，并通过上下文提供服务端可信的访客 key。

## 目标

- `POST /api/visitor/portfolios/{shareCode}/open` 使用方法级 `@LoginAccess`，不要求访客 token。
- `/open` 返回类似维护端登录响应的访客登录信息，包括 token 类型、加密 token、有效期。
- 所有 `@VisitorAccess` 接口由切面统一校验访客 token。
- 切面校验使用统一 `CacheService` 缓存解析结果，避免每次请求都解密和查库。
- 后端业务代码通过类似 `AuthContextHolder.requireUserId()` 的方式获取访客身份，例如 `VisitorContextHolder.requireVisitorKey()`。
- 访客端接口不再信任请求体里的 `visitorKey`，以服务端认证上下文为准。
- 维护端预览页不受影响。

## 非目标

- 不改变维护者登录接口对外字段语义。
- 不要求预览页获取访客 token。
- 不新增数据库表。
- 不移除现有 DTO 中的 `visitorKey` 字段，第一阶段可保留兼容，但服务端忽略或覆盖。

## 现状确认

维护端登录链路：

- `MiniappAuthController#maintainerWechatLogin` 使用 `@LoginAccess`。
- 返回 `tokenType`、`token`、`userId`、`expiresInSeconds`。
- 维护者 token 前缀为 `wf-maintainer-v1.`。
- token 使用 AES-GCM 加密，payload 为 `userId:issuedAtEpochSeconds`。
- `AuthTokenService` 使用 `CacheService` 缓存解析结果，TTL 为 token 剩余有效期与 10 分钟中的较小值。
- `AuthAspect` 对 `@MaintainerAccess` 校验 `Authorization`，成功后写入 `AuthContextHolder`。

访客端现状：

- `VisitorPortfolioController` 类级别为 `@VisitorAccess`。
- `/open` 目前也继承类级别 `@VisitorAccess`，需要改为方法级 `@LoginAccess`。
- 访客页先调用 `/open`，再调用事件、档期、线索、头像昵称接口。
- 预览页走维护端 `/api/mine/portfolios/...` 接口，不走 `/api/visitor/...`。

## 总体设计

采用“维护者 token 与访客 token 同机制、不同前缀、不同上下文”的方案。

维护者链路保持：

```text
@MaintainerAccess
  -> AuthTokenService
  -> AuthContextHolder
  -> AuthContextHolder.requireUserId()
```

新增访客链路：

```text
@VisitorAccess
  -> VisitorAuthTokenService
  -> VisitorContextHolder
  -> VisitorContextHolder.requireVisitorKey()
```

`@LoginAccess` 仍表示登录入口或登录态校验入口，不要求 `Authorization`。由于现有切面已经“方法级注解优先于类级注解”，`VisitorPortfolioController` 可以继续类级别标注 `@VisitorAccess`，仅给 `/open` 方法额外标注 `@LoginAccess`。

## 接口调整

### 打开访客作品集

```text
POST /api/visitor/portfolios/{shareCode}/open
```

注解：

```java
@LoginAccess
@PostMapping("/api/visitor/portfolios/{shareCode}/open")
```

请求体保持：

```json
{
  "loginCode": "wx.login code",
  "sourceType": "WECHAT_SHARE_CARD",
  "idempotencyKey": "open-..."
}
```

响应在现有 `VisitorPortfolioResponse` 基础上增加访客登录字段：

```json
{
  "tokenType": "Bearer",
  "token": "wf-visitor-v1.xxx",
  "expiresInSeconds": 2592000,
  "visitorKey": "stable visitor key",
  "visitRecordId": 33,
  "needVisitorProfile": true,
  "visitorProfileToken": "short-lived-profile-token",
  "renderData": {}
}
```

字段建议：

- `tokenType`：固定 `Bearer`。
- `token`：加密后的访客登录令牌。
- `expiresInSeconds`：访客令牌有效期秒数。
- `visitorKey`：继续返回，用于前端本地展示和兼容；服务端后续接口不信任前端回传值。

即使作品集因为余额不足返回维护中，只要 `loginCode` 有效并解析出访客，也可以返回访客 token。这样 `/open` 的“登录态签发”语义保持稳定。

### 访客受保护接口

以下接口继续使用类级别 `@VisitorAccess`，调用时必须带访客 token：

```text
POST /api/visitor/portfolios/{shareCode}/visitor-avatar/upload-ticket
PUT  /api/visitor/portfolios/{shareCode}/visitor-profile
GET  /api/visitor/portfolios/{shareCode}/schedule
GET  /api/visitor/portfolios/{shareCode}/schedule-options
POST /api/visitor/portfolios/{shareCode}/schedule-query
POST /api/visitor/portfolios/{shareCode}/events
POST /api/visitor/portfolios/{shareCode}/contact-leads
```

统一请求头：

```text
Authorization: Bearer wf-visitor-v1.xxx
```

## Token 设计

### 前缀

- 维护者：`wf-maintainer-v1.`
- 访客：`wf-visitor-v1.`

前缀用于快速区分 token 类型，避免维护者 token 被误用于访客接口，或访客 token 被误用于维护端接口。

### 载荷

访客 token payload：

```text
visitorId:visitorKey:issuedAtEpochSeconds
```

字段含义：

- `visitorId`：全局访客 ID，服务端内部使用。
- `visitorKey`：全局访客稳定 key，用于访问记录和事件链路。
- `issuedAtEpochSeconds`：签发时间，用于计算过期时间。

### 加密

推荐抽出通用加解密服务：

```text
EncryptedAuthTokenService
```

职责：

- 从 `auth.token.secret` 派生 AES key。
- 使用 AES-GCM 加密 payload。
- 拼接 token 前缀。
- Base64 URL safe 编码。
- 解密并返回 payload。

这样维护者和访客共用同一套加密基础能力，但保留各自的业务解析服务。

### 有效期

建议第一版访客 token 有效期与维护端保持一致：

```text
30 天
```

原因：

- 访客再次打开分享页时会重新调用 `/open`，可自然刷新 token。
- 访客事件可能比较频繁，过短有效期会带来不必要的重新登录。
- 与维护端一致可以减少前后端状态处理差异。

如后续希望收紧，可把访客有效期单独配置为：

```yaml
auth:
  token:
    visitor-expires-in-seconds: 2592000
```

## 缓存设计

新增：

```text
VisitorAuthTokenService
```

解析流程：

1. 使用 `AuthorizationHeaderUtils.normalizeBearerToken()` 标准化请求头。
2. token 为空则认证失败。
3. token 不是 `wf-visitor-v1.` 前缀则认证失败。
4. 先读统一缓存 `CacheService`。
5. 缓存命中则直接返回访客上下文。
6. 缓存未命中则解密 token。
7. 解析 `visitorId`、`visitorKey`、`issuedAtEpochSeconds`。
8. 校验过期时间。
9. 查 `wf_visitor`，确认 `visitorId` 存在且 `visitorKey` 一致。
10. 写入缓存。

缓存 key：

```text
visitor:auth-token:{normalizedToken}
```

如果担心 token 原文作为 key 偏长或进入缓存观测面，可改用摘要：

```text
visitor:auth-token:{sha256(normalizedToken)}
```

推荐使用摘要 key。

缓存值：

```java
ResolvedVisitorToken(Long visitorId, String visitorKey, Instant expiresAt)
```

缓存 TTL：

```text
min(token 剩余有效期, 10 分钟)
```

这与维护端 `AuthTokenService` 的 `AUTH_CACHE_TTL = 10 minutes` 保持一致。缓存命中时不解密、不查库，适合访客事件等高频接口。

## 上下文设计

新增：

```text
com.jxc.wefolio.common.auth.VisitorContext
com.jxc.wefolio.common.auth.VisitorContextHolder
```

`VisitorContext` 字段：

```java
private final Long visitorId;
private final String visitorKey;
private final String token;
```

`VisitorContextHolder` 方法：

```java
Optional<VisitorContext> current()
Optional<Long> getVisitorId()
Long requireVisitorId()
Optional<String> getVisitorKey()
String requireVisitorKey()
void set(VisitorContext context)
void clear()
```

异常语义：

- `requireVisitorId()` 无上下文时抛 `AuthenticationRequiredException("访客未登录")`。
- `requireVisitorKey()` 无上下文时抛 `AuthenticationRequiredException("访客未登录")`。

## 切面设计

`AuthAspect` 改为三条认证路径：

```text
LOGIN      -> 直接放行
SYSTEM     -> 直接放行
MAINTAINER -> authenticateMaintainer()
VISITOR    -> authenticateVisitor()
```

访客认证伪代码：

```java
private Object authenticateVisitor(ProceedingJoinPoint joinPoint, HttpServletRequest request, String requestInfo) throws Throwable {
    if (request == null) {
        log.warn("访客认证失败：无法获取 HTTP 请求");
        throw new AuthenticationRequiredException();
    }

    String authorization = request.getHeader("Authorization");
    Optional<ResolvedVisitorToken> visitor = visitorAuthTokenService.resolveAuthenticatedVisitor(authorization);
    if (visitor.isEmpty()) {
        log.warn("访客认证失败：令牌无效或已过期, request={}", requestInfo);
        throw new AuthenticationRequiredException();
    }

    VisitorContextHolder.set(new VisitorContext(visitor.visitorId(), visitor.visitorKey(), authorization));
    try {
        return joinPoint.proceed();
    } finally {
        VisitorContextHolder.clear();
    }
}
```

维护者上下文和访客上下文建议分开，不把访客塞进 `AuthContextHolder`。这样 `requireUserId()` 和 `requireVisitorKey()` 语义清晰，不会混用。

## 业务服务调整

### `VisitorPortfolioService`

`openPortfolio()`：

- 继续调用 `visitorService.resolveByLoginCode(loginCode)`。
- 记录打开事件时使用 `visitorSession.visitor().getId()` 和 `visitor.getVisitorKey()`。
- 构建响应时增加访客 token 字段。

`querySchedule()`：

- 当前参数里的 `visitorKey` 改为从 `VisitorContextHolder.requireVisitorKey()` 获取。
- 请求参数可暂时保留，但不作为可信来源。

`submitScheduleQuery()`：

- 使用 `VisitorContextHolder.requireVisitorKey()` 覆盖 `request.getVisitorKey()`。
- 记录事件和业务记录时使用上下文 visitorKey。

`recordEvent()`：

- 忽略请求体 visitorKey，使用上下文 visitorKey。

### `ContactLeadService`

`submit()`：

- 忽略请求体 visitorKey，使用 `VisitorContextHolder.requireVisitorKey()`。
- 如果需要 visitorId，可从 `VisitorContextHolder.requireVisitorId()` 获取。

### `VisitorService`

头像昵称相关接口需要叠加校验：

- `visitorProfileToken` 继续校验作品集、访客、访问记录上下文。
- 额外要求 `VisitorContextHolder.requireVisitorId()` 与 profile token 中的 visitorId 一致。
- 防止 profile token 被跨访客使用。

## 小程序调整

现有 `utils/request.js` 会自动读取维护者 token 并带上 `Authorization`。访客接口上线后必须区分维护者 token 和访客 token。

建议增加 `authMode`：

```js
request({
  url,
  method,
  data,
  authMode: 'maintainer' | 'visitor' | 'none'
})
```

默认值：

```text
maintainer
```

三种模式：

- `maintainer`：沿用当前逻辑，读取 `wefolio_token`。
- `visitor`：读取访客 token，例如 `wefolio_visitor_token`。
- `none`：不写 `Authorization`。

访客页流程：

1. `wx.login()` 获取 `loginCode`。
2. 调用 `/open`，使用 `authMode: 'none'`。
3. 保存响应中的访客 token。
4. 后续访客接口统一使用 `authMode: 'visitor'`。

访客 token 存储建议：

```text
wefolio_visitor_token
wefolio_visitor_token_expires_at
```

`/open` 返回新 token 时覆盖本地旧 token。

## 预览页影响

预览页不受影响。

当前预览页走维护端接口：

```text
GET  /api/mine/portfolios/{portfolioId}/preview
GET  /api/mine/portfolios/{portfolioId}/published-preview
GET  /api/mine/portfolios/{portfolioId}/schedule-options
POST /api/mine/portfolios/{portfolioId}/schedule-query-preview
```

这些接口位于 `MinePortfolioController`，类级别为 `@MaintainerAccess`，继续使用维护者 token。

共享组件 `portfolio-schedule-query` 需要保留现有分支：

- `preview=true`：请求 `/api/mine/...`，使用维护者 token。
- `preview=false`：请求 `/api/visitor/...`，使用访客 token。

因此实现时不能把组件内的所有档期查询统一改成访客 token。

## 兼容策略

第一阶段：

- DTO 中的 `visitorKey` 字段保留。
- 前端可以继续传 `visitorKey`，但后端不信任，以 `VisitorContextHolder` 为准。
- `/open` 返回 `visitorKey` 和 token，保证旧页面展示逻辑不受影响。

第二阶段：

- 小程序访客端稳定使用 visitor token 后，可以逐步删除不必要的 `visitorKey` 请求字段。
- 后端测试确认没有服务逻辑依赖请求体 visitorKey 后，再清理 DTO 和工具函数。

## 风险与处理

### 维护者 token 误传到访客接口

处理：

- `VisitorAuthTokenService` 要求 `wf-visitor-v1.` 前缀。
- 维护者 token 前缀为 `wf-maintainer-v1.`，会认证失败。

### 访客 token 误传到维护端接口

处理：

- `AuthTokenService` 只解析 `wf-maintainer-v1.`。
- 访客 token 会认证失败。

### 高频访客事件导致解密成本高

处理：

- `VisitorAuthTokenService` 使用 `CacheService` 缓存解析结果。
- TTL 为 `min(剩余有效期, 10 分钟)`。

### 预览页误走访客认证

处理：

- 预览页继续请求 `/api/mine/...`。
- 组件按 `preview` 属性选择接口和 token 类型。
- 增加前端测试固定 `preview=true` 时请求维护端接口。

### profile token 被复制使用

处理：

- 资料 token 继续短期有效。
- 保存头像昵称时额外校验认证上下文 visitorId 与资料 token visitorId 一致。

## 测试计划

### 后端单元测试

`EncryptedAuthTokenServiceTest`：

- 能加密并解密维护者 payload。
- 能加密并解密访客 payload。
- 篡改 token 后解密失败。
- 错误前缀不被错误认证服务接受。

`VisitorAuthTokenServiceTest`：

- 无 Authorization 返回空。
- 非 `wf-visitor-v1.` 前缀返回空。
- 有效 token 返回 visitorId、visitorKey、expiresAt。
- 过期 token 返回空或抛认证失败。
- 缓存命中时不重复解密、不查库。
- 缓存未命中时校验 `wf_visitor` 存在且 visitorKey 一致。

`AuthAspectTest`：

- `/open` 方法级 `@LoginAccess` 优先于类级 `@VisitorAccess`。
- `@VisitorAccess` 无 token 时抛未登录。
- `@VisitorAccess` 有效 token 时写入 `VisitorContextHolder`。
- 请求结束后清理 `VisitorContextHolder`。

`VisitorContextHolderTest`：

- `requireVisitorId()` 无上下文时抛未登录。
- `requireVisitorKey()` 无上下文时抛未登录。
- set/current/clear 行为正确。

`VisitorPortfolioServiceTest`：

- `/open` 返回 tokenType、token、expiresInSeconds。
- 事件、档期查询、线索提交使用上下文 visitorKey。
- 请求体 visitorKey 与上下文不一致时，以上下文为准。
- 头像昵称保存要求 profile token visitorId 与上下文 visitorId 一致。

### 小程序测试

`request.test.js`：

- 默认 `authMode` 带维护者 token。
- `authMode: 'none'` 不带 Authorization。
- `authMode: 'visitor'` 带访客 token。

`page-async.test.js` 或访客页测试：

- 访客页 `/open` 使用 `authMode: 'none'`。
- `/open` 返回 token 后保存访客 token。
- 事件、线索、头像、档期接口使用 `authMode: 'visitor'`。

`portfolio-standard-preview.test.js`：

- 预览页仍请求 `/api/mine/portfolios/.../preview`。
- 预览档期查询仍请求 `/api/mine/portfolios/.../schedule-query-preview`。

## 建议实施顺序

1. 抽出通用 `EncryptedAuthTokenService`，保持维护者 token 行为不变。
2. 新增访客 token DTO 字段和 `VisitorAuthTokenService`。
3. 新增 `VisitorContext`、`VisitorContextHolder`。
4. 改造 `AuthAspect`，加入 `authenticateVisitor()`。
5. 给 `/open` 方法添加 `@LoginAccess`，并返回访客 token。
6. 改造访客业务服务，统一从 `VisitorContextHolder` 获取 visitorId/visitorKey。
7. 改造小程序 request 的 `authMode` 和访客页调用。
8. 补齐后端和小程序测试。
9. 运行后端 `mvn test` 和小程序 `node --test tests/*.test.js`。

## 最终行为

完成后，访客端链路为：

```text
访客打开分享页
  -> wx.login()
  -> POST /api/visitor/portfolios/{shareCode}/open (@LoginAccess)
  -> 返回 Bearer wf-visitor-v1.xxx
  -> 小程序保存访客 token
  -> 后续 /api/visitor/... 请求带访客 token
  -> @VisitorAccess 切面缓存校验 token
  -> VisitorContextHolder.requireVisitorKey()
```

维护端预览链路保持：

```text
维护者进入预览页
  -> /api/mine/portfolios/... (@MaintainerAccess)
  -> AuthContextHolder.requireUserId()
```
