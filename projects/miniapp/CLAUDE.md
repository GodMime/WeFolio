# CLAUDE.md

本文件适用于 `projects/miniapp/` 及其子目录，是 Claude Code 在 WeFolio 微信小程序模块内工作的目录级指南。

## 模块定位

`projects/miniapp/` 是 WeFolio（映期Folio）的微信小程序端，面向婚庆、演艺服务从业者，承接维护者登录、我的工作台、基础信息、访问记录、团队列表、团队维护、成员邀请、我的消息等一期能力，并逐步补齐 PRD 中的作品、作品集、档期、联系线索、积分充值和访客端页面。

权威业务与设计来源：

- 产品规则：`../../docs/PRD.md`，当前已覆盖"我的消息"、团队邀请、积分与作品/作品集/档期闭环。
- 视觉与交互：`../../design/prototype.html`，含登录、我的、积分、团队、作品、作品集、访客页、档期等 29 个页面。
- 接口契约：`../java/wefolio-java-runtime/` 中的 Controller、DTO 和 Service。
- 后端架构指南：`../java/wefolio-java-runtime/CLAUDE.md`。

当前小程序使用 **Skyline 渲染引擎**和 **glass-easel 组件框架**，配置见 `app.json`。

## Build & Run

```bash
# 用微信开发者工具打开本目录
# AppID: wxa214c25850cdf268
# 路径: projects/miniapp/

# 运行小程序 Node 测试
cd projects/miniapp
node --test tests/*.test.js

# 按匹配模式运行部分测试
node --test --test-name-pattern="dashboard" tests/*.test.js
```

没有 `package.json` 和 npm 脚本，不引入额外构建依赖。纯原生微信小程序开发，不使用 TypeScript、Webpack 或 Vite。

## Architecture

```
微信小程序 (Skyline / glass-easel)
        │
        ▼
Spring Boot REST API (:8090)
        │
     ┌──┴──┐
     ▼     ▼
  MySQL  腾讯云 COS
  (8.0)  (对象存储)
```

**小程序端技术要点：**

| 项 | 说明 |
|---|---|
| 渲染引擎 | Skyline（`app.json` 中 `renderer: "skyline"`） |
| 组件框架 | glass-easel（`componentFramework: "glass-easel"`） |
| 导航栏 | 自定义 `<navigation-bar>` 组件，`navigationStyle: "custom"` |
| 懒加载 | `lazyCodeLoading: "requiredComponents"` |
| 基础库 | 3.15.0+（`project.private.config.json`） |
| API 基址 | `https://api.we-folio.dingchenyong.top`（`app.js` globalData） |
| 静态系统资源 | 小图 `/assets/system/`；大图 `https://cdn2.we-folio.dingchenyong.top/system/` |

## 代码结构

```text
app.js                         全局小程序配置，含线上 API baseUrl
app.json                       页面注册、Skyline/glass-easel 配置、自定义导航、懒加载
app.wxss                       全局 page、button、.page-shell 基础样式
project.config.json            微信开发者工具项目配置
project.private.config.json    本地开发配置（libVersion、skylineRenderEnable 等）
sitemap.json                   站点地图（允许所有页面被索引）
.eslintrc.js                   ESLint 配置（CommonJS、wx 全局变量）

components/
  navigation-bar/              自定义顶部导航栏（back/home 按钮、title、loading）

pages/
  index/                       "我的"工作台（主入口页）
  login/                       登录/注册页（注册 + 微信授权登录）
  profile/                     基础信息维护页
  messages/                    我的消息列表（筛选、单条/批量/全量已读）
  visits/                      访问记录页（指标卡片、7 日趋势 canvas）
  teams/                       团队列表与创建
  team-maintenance/            团队资料和成员查看/维护
  team-member-add/             按个人唯一码查询候选人并发送团队邀请
  team-invitations/            团队邀请详情（接受/拒绝）

utils/
  request.js                   REST 请求封装（Response 解包、Bearer token 注入、401 处理）
  session.js                   登录态读写、401 处理和微信登录接口封装
  avatar.js                    个人头像压缩与上传
  team-avatar.js               团队图标上传
  dashboard.js                 我的工作台数据归一化
  messages.js                  消息列表、未读徽标和已读 payload 归一化
  profile.js                   基础信息、标签色板和表单校验
  teams.js                     团队列表/详情、成员邀请、邀请确认归一化和表单校验
  visits.js                    访问记录归一化

tests/
  共 17 个测试文件，含 utils 单元测试和页面静态布局测试
```

## 页面清单

| 页面 | 路径 | 功能说明 |
|------|------|----------|
| 我的工作台 | `pages/index/index` | 个人摘要卡片、唯一码复制、积分余额、三项指标（作品数/发布作品集数/近7天访客数）、入口卡片（访客记录/团队/消息）、底部四栏导航占位 |
| 登录/注册 | `pages/login/login` | 注册 Tab（选择头像 + 昵称 + 手机号 + 可选推荐码）和微信授权登录 Tab |
| 基础信息 | `pages/profile/profile` | 头像编辑、姓名/艺名/职业/城市/简介（含字数统计）、标签编辑器（最多 10 个，9 色调色板）、退出登录、注销账号 |
| 我的消息 | `pages/messages/messages` | 消息列表、四种筛选（全部/未读/团队/积分）、单条已读、选中未读批量已读、当前分类全量已读、按 actionUrl 跳转 |
| 访问记录 | `pages/visits/visits` | 三项指标卡片（总访问/今日访问/档期查询）、7 日趋势 canvas 折线图、访客明细列表（含跟进状态标签） |
| 团队列表 | `pages/teams/teams` | 团队摘要卡片、团队列表（角色/成员数标签）、可展开的创建团队表单（名称 + 简介 + 图标上传） |
| 团队维护 | `pages/team-maintenance/team-maintenance` | 团队资料编辑（权限控制）、成员列表（本地搜索 + 状态筛选）、添加成员入口（仅拥有者可见） |
| 添加成员 | `pages/team-member-add/team-member-add` | 按个人唯一码查询候选人、设置角色（管理者/普通成员）、设置作品集引用权限、发送邀请 |
| 团队邀请 | `pages/team-invitations/team-invitations` | 邀请详情展示（团队信息、角色、权限说明）、接受/拒绝按钮 |

各页面 `.json` 都通过 `usingComponents` 引入 `/components/navigation-bar/navigation-bar`。新增页面若沿用自定义导航，也要显式注册该组件。

## Mock 体验版约定

- mock 体验版必须完全独立于正式维护者端页面和组件；除登录页的“体验”入口和跳转外，不要改动既有正式页面或正式组件来承载 mock 行为。
- mock 页面统一放在 `pages/mock/`，mock 专用组件统一放在 `components/mock/`，mock 数据、作品集 JSON 和本地草稿逻辑统一放在 `utils/mock-experience.js` 或同级 mock 专用文件。
- mock 页面禁止引入 `utils/request.js`、`utils/session.js`、`utils/avatar.js`、`utils/team-avatar.js`、`utils/visitor-session.js`；禁止调用 `wx.request`、`wx.uploadFile`、`/api/`、维护者 token/session 或任何后端接口。
- mock 的档期、作品、素材库、作品集、编辑、预览、“我的”等数据只使用本地 mock 数据或本地草稿态；新增、保存草稿、发布、档位操作等需要后端身份的动作统一提示 `请去“我的”页面注册登录`。
- mock 页面可以复用不需要改动的展示组件和样式；如果复用会导致修改正式页面或正式组件，则新建 mock 专用组件。
- mock 中用到头像的地方统一使用 `https://cdn2.we-folio.dingchenyong.top/demo/demo-avatar.png`。
- mock 相关测试优先放在 `tests/mock-*.test.js`，重点覆盖“不引入 request/session、不出现 API 调用”和 mock 数据结构完整性。

## 前后端接口约定

### 统一响应结构

后端所有接口返回 `Response<T>`：

```json
{
  "success": true,
  "message": "ok",
  "data": {}
}
```

### request.js 行为

小程序通过 `utils/request.js` 的 `request()` 访问 REST API。函数签名：`request({ url, method?, data? })`。

核心行为：

- 拼接默认域名 `https://api.we-folio.dingchenyong.top`（可通过 `createRequestClient(baseUrl)` 覆盖）。
- 从本地存储 key `wefolio_token` 读取 token。
- 自动补 `Authorization: Bearer <token>` 请求头。
- HTTP 401 → 抛出 `{ authRequired: true, message: '登录已过期，请重新登录' }`。
- `success === false` → 使用后端 `message` 抛错。
- 返回 `body.data`，没有 `data` 字段时返回完整 `body`。

### 已对接接口

| 小程序能力 | 方法与路径 | 后端位置 |
|---|---|---|
| 微信授权登录/注册 | `POST /api/auth/maintainer/wechat-login` | `MiniappAuthController` |
| 登录态校验 | `GET /api/auth/session` | `MiniappAuthController` |
| 个人头像上传 | `POST /api/auth/avatar` | `MiniappAuthController` |
| 账号注销 | `POST /api/auth/account/cancel` | `MiniappAuthController` |
| 我的工作台 | `GET /api/mine/dashboard` | `MineController` |
| 基础信息读取/保存 | `GET/PUT /api/mine/profile` | `MineController` |
| 访问记录 | `GET /api/mine/visits` | `MineController` |
| 团队列表/创建 | `GET/POST /api/mine/teams` | `MineTeamController` |
| 团队详情/保存 | `GET/PUT /api/mine/teams/{teamId}` | `MineTeamController` |
| 团队成员候选查询 | `GET /api/mine/teams/{teamId}/member-candidate?uniqueCode=` | `MineTeamController` |
| 邀请团队成员 | `POST /api/mine/teams/{teamId}/members` | `MineTeamController` |
| 团队邀请详情/接受/拒绝 | `GET /api/mine/team-invitations/{memberId}`、`POST .../accept`、`POST .../reject` | `MineTeamController` |
| 团队图标上传 | `POST /api/mine/teams/{teamId}/avatar` | `MineTeamController` |
| 消息列表（游标分页） | `GET /api/mine/messages?status=&category=&cursor=&size=` | `MineMessageController` |
| 消息未读数 | `GET /api/mine/messages/unread-count` | `MineMessageController` |
| 消息已读 | `PUT /api/mine/messages/read`、`PUT /api/mine/messages/read-all` | `MineMessageController` |
| 积分概览/流水/试算 | `GET /api/mine/points`、`GET /api/mine/points/transactions`、`POST /api/mine/points/calculate` | `MinePointController` |

**新增接口时：** 先从后端 Controller 和 DTO 获取字段名，不要根据 PRD 自行猜字段。后端倾向下发展示文案、动作路径和样式 token（如 `roleText`、`memberCountText`、`statusTone`、`userStatusTone`、`followTone`、`actionUrl`）；小程序只做空值兼容和必要兜底。

## 登录与会话

登录态封装在 `utils/session.js`：

| 函数 | 说明 |
|------|------|
| `setToken(token)` | 保存后端返回的 token 到本地存储 |
| `clearToken()` | 清除当前设备登录态 |
| `hasLocalToken()` | 本地预检是否有 token |
| `handleMaintainerAuthRequired(message)` | 处理维护者 401：清维护者 token、toast 提示并跳转登录页 |
| `ensureSession()` | 校验当前登录态是否有效 |
| `maintainerWechatLogin(payload)` | 调用维护者微信登录接口 |

### 标准页面请求流程

```js
if (!hasLocalToken()) {
  this.redirectToLogin()
  return
}
// ... 请求 ...
// 维护者接口收到 error.authRequired → handleMaintainerAuthRequired(error.message)
// 访客接口使用 utils/visitor-session.js 的 /open 刷新与重试流程
```

### 注册流程注意点

- `wx.login()` 的 `code` 必传。
- 首次注册需要手机号组件返回的 `phoneCode`。
- `wx.pluginLogin()` 可能不存在或失败，当前策略是失败时传空 `pluginLoginCode`，不阻断注册。
- 头像和昵称必须由微信能力授权；不要回退到 `getUserProfile`。
- 小程序本地临时头像路径服务端无法直接读取。注册成功后必须先 `setToken(response.token)`，再用 `/api/auth/avatar` 上传头像，最后 `PUT /api/mine/profile` 回写公开 URL。

## 上传与 COS

### 个人头像

使用 `utils/avatar.js`：

- 远程 URL 直接复用；本地临时文件才走 `wx.uploadFile`。
- 头像上限 5 MB；超过时用 `wx.compressImage` 压缩到 512×512，质量 95。
- `wx.uploadFile` 不走 `request.js`，必须手动补 Bearer token。
- 上传接口返回字符串 JSON，需要解析后读取 `data.url`。

### 团队图标

使用 `utils/team-avatar.js`：

- 必须先有 `teamId`。
- 本地文件上限 5 MB；超过时用 `wx.compressImage` 压缩到 512×512，质量 95。
- 上传到 `/api/mine/teams/{teamId}/avatar`，成功后用返回 URL 调 `PUT /api/mine/teams/{teamId}` 回写团队资料。

### 后端 COS 路径规则

```text
个人：{uniqueCode}/others/{uuid}.{ext}
团队：{teamCode}/others/{uuid}.{ext}
图片作品：{uniqueCode}/work/image/{uuid}.{ext}
视频作品：{uniqueCode}/work/video/{uuid}.{ext}
作品集素材：{uniqueCode}/protfolio/{uuid}.{ext}
```

> **不要在小程序端拼最终 COS key**；小程序只上传文件或提交后端返回的公开 URL。

## 页面开发模式

页面逻辑优先沿用当前结构：

1. `data` 中放安全默认值（如 `normalizeDashboard({})`、`normalizeTeamDetail({})`、`normalizeMessageList({})`）。
2. `onLoad` 或 `onShow` 调 `bootstrap()`。
3. `bootstrap()` 先做 `hasLocalToken()` 预检，再请求后端加载数据。
4. 页面保持 **loading**（骨架屏）、**errorMessage**（重试按钮）、**content** 三态。
5. 后端响应先进 `utils/*` 的 normalize 函数，再进入 `setData()`。
6. 表单提交前由 utils 构建 payload 和校验结果，页面只负责 toast、loading 和导航。

### 适合放在 utils 的逻辑

- 字段裁剪和 payload 构造。
- 展示文案兜底。
- 数字、列表、状态色调归一化。
- 表单长度、数量、重复校验。
- 可注入 `wxApi` 的上传或请求辅助函数。

> 页面 JS 不要堆复杂业务规则。若规则可以用 Node `node:test` 验证，优先抽到 utils。

## 业务规则要点

### 基础信息

- 姓名/艺名、职业身份、服务城市最大 50 字。
- 个人简介最大 500 字。
- 标签最多 10 个，单个最多 10 字，不能为空、不能重复。
- 标签颜色必须来自 `utils/profile.js` 的 9 色色板（`TAG_COLOR_OPTIONS`）。
- 头像更新次数后端有限制，小程序只展示后端错误，不自行绕过。

### 团队

- 团队名称必填，最大 100 字。
- 团队简介最大 1000 字。
- 当前角色以后端 `role`、`canMaintain` 和 `canManageMembers` 为准；前端隐藏按钮只是体验优化，不能视作权限来源。
- `OWNER` 和 `MANAGER` 可维护团队资料，`MEMBER` 只查看。
- 只有 `OWNER` 可查询成员候选人、发送邀请、维护成员权限；添加成员页不能提供拥有者角色选择。
- 邀请成员只允许 `MANAGER` 或 `MEMBER`，团队内职业最大 50 字；引用权限默认：个人作品集和头像资料开启，个人作品素材关闭。
- 团队列表只展示后端返回的已加入团队；待确认等状态在维护页成员列表展示。
- 团队邀请消息跳转到 `/pages/team-invitations/team-invitations?memberId=...`，邀请页只处理当前登录用户自己的邀请。
- 成员列表搜索在本地按 `displayName`、`profession`、`uniqueCode` 过滤。
- `statusTone`、`userStatusTone` 直接映射 `.role-pill.teal/.amber/.muted` 等样式 token。

### 消息

- 消息均为系统消息，不做私聊、回复或会话能力。
- 筛选项固定为全部、未读、团队、积分，对应 `utils/messages.js` 中的 `FILTER_QUERY`。
- 单条/批量已读只提交未读消息 ID，payload 为 `{ messageIds: [...] }`。
- 全量已读：全部筛选下提交空对象 `{}`；团队或积分筛选下提交 `{ category: 'TEAM' | 'POINT' }`。
- 列表中的 `actionUrl` 由后端生成，团队邀请跳转到邀请处理页；小程序不在消息列表里直接接受或拒绝邀请。

### 访问记录

- 指标字段来自 `summary.totalVisitCount`、`summary.todayVisitCount`、`summary.scheduleQueryCount`。
- 趋势点来自 `trend.points`，canvas 只在 `onReady` 或数据更新后绘制。
- 跟进状态色调使用后端 `followTone`，前端只拼 `follow-pill ${tone}`。
- 不向维护者展示未经授权的访客手机号、openid 或真实身份。

### 积分与作品集

- "我的"页只展示工作台返回的积分摘要和低余额提醒（余额 < 50 显示提醒）。
- 积分概览、流水和试算接口已有后端实现（`MinePointController`），新增页面时优先对接，不要在前端自行计算扣费。
- 创建团队消耗 1000 积分，由后端校验余额并初始化团队 COS 目录，小程序只展示后端错误。
- 团队没有积分账户，团队作品集访问不扣积分。
- 个人作品集余额不足时，访客端只展示 "UNDER MAINTENANCE / 维护中"，不得暴露维护者余额。
- 预览模式必须有明确标识并由后端排除访问统计和积分消耗，不能只依赖前端不发事件。
- 高级作品集 AI 只能渲染后端校验后的结构化 schema，不能执行 AI 生成代码。

## 视觉与交互

以 `design/prototype.html` 为视觉基准：

### 颜色主题

| 颜色 | 用途 |
|------|------|
| `#17202a`（深墨） | 主色调，文字、导航 |
| `#0f766e`（青绿） | 强调色，团队相关 |
| `#2d5f9a`（湖蓝） | 强调色，信息相关 |
| `#8a4b09`（琥珀） | 强调色，警告相关 |
| `#f5f7fb`（浅背景） | 页面底色 |

### CSS 类名约定

- 页面根节点：`.page-shell` + 页面级 class（如 `.messages-page`、`.team-invitations-page`）
- 容器面板：`.panel`
- 指标网格：`.metric-grid`
- 按钮：`.primary-button`、`.secondary-button`
- 标签/药丸：`.role-pill`、`.follow-pill`
- 骨架屏：`.skeleton-panel`

### WXSS 布局注意

- 登录页 segmented tabs 当前用 flex 兼容 Skyline，不要改成 CSS grid。
- 消息筛选、团队角色选择、权限开关等横向控件优先使用 flex，避免 Skyline 下 grid 兼容差异。
- 需要动画展开的区域保持节点常驻，用 `max-height/opacity/transform/pointer-events` 切换，避免 `wx:if` 导致无法过渡。
- 按钮和行项目要保证足够点击区域；用于删除、返回等图标触发器可使用 `view` 加 `aria-role="button"` 和清晰 `aria-label`。

### 导航

- 一级 tab 页面（我的）不展示返回按钮，二级页面展示返回。
- 底部主导航固定为"档期 / 作品 / 作品集 / 我的"，图标 + 文字，当前仅"我的"可用。
- 静态系统小图放在小程序本地包 `assets/system/`，页面和脚本使用 `/assets/system/...` 引用；代码包内图片和音频资源总量需控制在 200KB 内。超过 200KB 的大图使用 `https://cdn2.we-folio.dingchenyong.top/system/...` 远程加载并依赖微信/CDN 缓存。不要退回旧 OSS URL 或 COS 源站域名。

## 测试约定

```bash
cd projects/miniapp
node --test tests/*.test.js
```

测试框架：Node.js 内置 `node:test` + `node:assert/strict`。

### 测试分层

**Utils 单元测试（逻辑正确性）：**

| 测试文件 | 覆盖范围 |
|----------|----------|
| `tests/request.test.js` | 默认 baseUrl、Bearer token 注入、401 处理、Response 解包 |
| `tests/session.test.js` | Token CRUD、handleMaintainerAuthRequired 行为、登录接口调用 |
| `tests/dashboard.test.js` | normalizeDashboard 完整数据和空数据兜底 |
| `tests/profile.test.js` | 9 色色板、归一化、payload 构造、表单校验 |
| `tests/messages.test.js` | 消息列表归一化、筛选查询构建、已读 payload 构造 |
| `tests/teams.test.js` | 团队列表/详情/邀请归一化、表单校验、payload 构造 |
| `tests/visits.test.js` | 访问记录归一化、趋势点高度计算 |
| `tests/avatar.test.js` | 头像上传/压缩逻辑 |
| `tests/team-avatar.test.js` | 团队图标上传逻辑 |

**静态布局测试（WXML/WXSS 结构）：**

| 测试文件 | 覆盖范围 |
|----------|----------|
| `tests/mine-layout.test.js` | 我的页结构、底部导航图标 |
| `tests/login-layout.test.js` | 登录页布局、注册流程结构 |
| `tests/profile-layout.test.js` | 基础信息页结构 |
| `tests/messages-layout.test.js` | 消息页筛选和列表结构 |
| `tests/visits-layout.test.js` | 访问记录页指标和趋势结构 |
| `tests/team-layout.test.js` | 团队页布局 |
| `tests/tabbar-icons.test.js` | 底部导航图标结构 |

### 测试规范

- 新增或修改业务规则时：先补 utils 单元测试，再改实现。
- 页面结构或样式约束用静态布局测试覆盖。
- 上传逻辑通过可注入 `wxApi` 的 mock 测试，不访问真实微信或 COS。
- 在说明"已完成""测试通过"前，必须运行能证明结论的命令并阅读退出码和测试汇总。
- 测试中使用 `global.wx` set/delete 模式或注入 `wxApi` 参数模拟微信运行时。

## 编码规范

### 必须遵守

- 使用 CommonJS：`const xxx = require(...)`、`module.exports = {...}`。
- JS 注释使用中文，解释业务原因或平台限制。
- 页面 JS 保持 `data` 安全默认值 → `bootstrap()` → loadData → normalize → `setData()` 模式。
- 状态、文案和颜色 token 在 utils 中准备好，WXML 不拼复杂业务判断。
- 表单提交前由 utils 校验和构建 payload。
- 文件上传通过 `wx.uploadFile` 手动补 Bearer token。

### 禁止事项

- ❌ 不引入构建器、npm 依赖或 TypeScript（除非用户明确要求）。
- ❌ 不新增 `// TODO`；后续事项写在用户可见说明或任务文档中。
- ❌ 不在 WXML 里拼复杂业务判断。
- ❌ 不硬编码后端敏感字段、密钥、手机号、openid、access_token 或 Authorization。
- ❌ 不打印真实 token、手机号、openid、微信 code、phoneCode、pluginLoginCode。
- ❌ 不根据 PRD 自行虚构接口字段或扣费规则。
- ❌ 不在小程序端自行拼 COS key。
- ❌ 不在前端自行计算积分扣费或权限判断（展示层优化除外）。

### 文件约定

- `project.private.config.json` 属于本地开发配置，非必要不要修改。
- 若需要调整 `DEFAULT_BASE_URL` 或静态系统资源路径，同步更新对应测试。
- 编辑范围保持小，优先遵循现有页面、utils 和测试的命名风格。

## 安全与工作区

- 访客端页面不得强制登录，维护者端接口必须走 `Authorization`。
- 前端权限控制只做展示（隐藏按钮），所有团队角色、内容归属、积分扣费和线索查看权限以后端为准。
- 不要把后端 `.env`、微信 AppSecret、COS 密钥或真实用户数据复制进小程序代码、测试或回复。
- 工作区可能已有用户未提交改动；不要回滚、格式化或覆盖与当前任务无关的文件。

---

此模块是 WeFolio 多项目仓库的子目录 `projects/miniapp/`。仓库级架构、设计文档和编码规范见根目录 `CLAUDE.md`。后端架构详见 `../java/wefolio-java-runtime/CLAUDE.md`。
