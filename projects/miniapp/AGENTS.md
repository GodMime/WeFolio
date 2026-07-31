# AGENTS.md

本文件适用于 `projects/miniapp/` 及其子目录，是 Codex 在 WeFolio 微信小程序模块内工作的目录级指南。仓库根目录还有 `AGENTS.md`，后端模块有独立指南；如有冲突，以用户最新指令优先，并优先遵守更具体目录下的说明。

## 模块定位

`projects/miniapp/` 是 WeFolio（映期Folio）的微信小程序端，面向婚庆、演艺服务从业者，承接维护者登录、我的工作台、基础信息、访问记录、团队列表、团队维护、成员邀请、我的消息等一期能力，并逐步补齐 PRD 中的作品、作品集、档期、联系线索、积分充值和访客端页面。

权威业务与设计来源：

- 产品规则：`../../docs/PRD.md`，当前 PRD 已覆盖到“我的消息”、团队邀请、积分与作品/作品集/档期闭环。
- 视觉与交互：`../../design/prototype.html`，当前设计稿含登录、我的、积分、团队、作品、作品集、访客页、档期等 29 个页面。
- 接口契约：`../java/wefolio-java-runtime/` 中的 Controller、DTO 和 Service

当前小程序使用 Skyline 渲染引擎和 glass-easel 组件框架，配置见 `app.json`：

```json
{
  "renderer": "skyline",
  "componentFramework": "glass-easel",
  "navigationStyle": "custom"
}
```

## 常用命令

用微信开发者工具打开本目录：

```text
projects/miniapp/
AppID: wxa214c25850cdf268
```

运行小程序 Node 测试：

```bash
cd projects/miniapp
node --test tests/*.test.js
```

没有 `package.json` 和 npm 脚本时，不要为运行现有测试额外引入依赖。

## 代码结构

```text
app.js                         全局小程序配置，当前含线上 API baseUrl
app.json                       页面注册、Skyline/glass-easel 配置、自定义导航、懒加载
app.wxss                       全局 page、button、page-shell 基础样式
components/navigation-bar/     自定义顶部导航栏
pages/login/                   登录/注册页
pages/index/                   “我的”工作台，当前作为主入口页
pages/profile/                 基础信息维护页
pages/visits/                  访问记录页
pages/messages/                我的消息列表、筛选、单条/批量/全量已读
pages/teams/                   我的团队列表与创建团队
pages/team-maintenance/        团队资料和成员查看/维护页
pages/team-member-add/         按个人唯一码查询候选人并发送团队邀请
pages/team-invitations/        团队邀请详情、接受、拒绝
utils/request.js               REST 请求封装、Response 解包、Bearer token 注入
utils/session.js               登录态读写、401 处理和微信登录接口封装
utils/avatar.js                个人头像压缩与上传
utils/team-avatar.js           团队图标上传
utils/dashboard.js             我的工作台数据归一化
utils/messages.js              消息列表、未读徽标和已读 payload 归一化
utils/profile.js               基础信息、标签色板和表单校验
utils/teams.js                 团队列表/详情、成员邀请、邀请确认归一化和表单校验
utils/visits.js                访问记录归一化
tests/                         node:test 单元测试与静态布局测试
```

各页面 `.json` 目前都通过 `usingComponents` 引入 `/components/navigation-bar/navigation-bar`。新增页面若沿用自定义导航，也要显式注册该组件。

## 已实现范围

当前小程序已落地：

- 登录页：体验入口与单一维护者登录/注册入口；页面先静默预检微信身份，老用户直接登录，仅新用户授权手机号注册，昵称和头像可在注册后补充，推荐码选填；后端为新注册用户写入系统默认头像。
- 我的页：个人摘要、唯一码复制、积分余额、三项指标、访问记录入口、我的团队入口、我的消息未读入口、底部四项导航占位。
- 基础信息页：头像、姓名/艺名、职业、服务城市、个人简介、标签色板、退出登录、注销账号。
- 访问记录页：统计指标、近 7 日趋势 canvas、来源和行为摘要列表。
- 团队列表页：团队摘要、团队列表、创建团队展开表单、团队图标上传后回写。
- 团队维护页：团队资料编辑、成员搜索、加入状态筛选、成员状态标签、拥有者添加成员入口。
- 添加团队成员页：按个人唯一码查询候选人，选择管理者/普通成员，设置团队作品集引用权限并发送邀请。
- 团队邀请页：从系统消息 actionUrl 进入，展示邀请详情、权限说明，支持接受或拒绝。
- 我的消息页：全部/未读/团队/积分筛选，单条已读、选中未读批量已读、当前分类全量已读。

仍是占位或未接入：

- 底部导航中的“作品 / 作品集 / 档期”页面。
- 充值按钮、完整积分页、充值页和积分规则页；后端已有积分概览、流水、试算接口，小程序页面尚未接入。
- PRD 中的作品管理、标准/高级作品集、访客端、档期维护、联系线索、微信支付等后续模块。

实现未落地模块前，先对照 `../../docs/PRD.md` 的迭代规划和 `../../design/prototype.html` 的页面编号；若设计稿缺失，以 PRD 业务规则和通用状态要求为验收依据。涉及新接口时先查后端 Controller、DTO、Service 和 migration，不要在小程序端自行虚构字段或扣费规则。

## Mock 体验版约定

- mock 体验版必须完全独立于正式维护者端页面和组件；除登录页的“体验”入口和跳转外，不要改动既有正式页面或正式组件来承载 mock 行为。
- mock 页面统一放在 `pages/mock/`，mock 专用组件统一放在 `components/mock/`，mock 数据、作品集 JSON 和本地草稿逻辑统一放在 `utils/mock-experience.js` 或同级 mock 专用文件。
- mock 页面禁止引入 `utils/request.js`、`utils/session.js`、`utils/avatar.js`、`utils/team-avatar.js`、`utils/visitor-session.js`；禁止调用 `wx.request`、`wx.uploadFile`、`/api/`、维护者 token/session 或任何后端接口。
- mock 的档期、作品、素材库、作品集、编辑、预览、“我的”等数据只使用本地 mock 数据或本地草稿态；新增、保存草稿、发布、档位操作等需要后端身份的动作统一提示 `请去“我的”页面注册登录`。
- mock 页面可以复用不需要改动的展示组件和样式；如果复用会导致修改正式页面或正式组件，则新建 mock 专用组件。
- mock 中用到头像的地方统一使用 `https://cdn2.we-folio.dingchenyong.top/demo/demo-avatar.png`。
- mock 相关测试优先放在 `tests/mock-*.test.js`，重点覆盖“不引入 request/session、不出现 API 调用”和 mock 数据结构完整性。

## 前后端接口约定

后端统一响应结构：

```json
{
  "success": true,
  "message": "ok",
  "data": {}
}
```

小程序必须通过 `utils/request.js` 的 `request()` 访问 REST API。它会：

- 拼接默认域名 `https://api.we-folio.dingchenyong.top`。
- 从本地存储 key `wefolio_token` 读取 token。
- 自动补 `Authorization: Bearer <token>`。
- 对 401 抛出带 `authRequired: true` 的错误。
- 对 `success === false` 使用后端 `message` 抛错。
- 返回 `body.data`，没有 `data` 时返回完整 body。

当前已对接接口：

| 小程序能力 | 方法与路径 | 后端位置 |
|---|---|---|
| 微信登录预检 | `POST /api/auth/maintainer/wechat-login/precheck` | `MiniappAuthController` |
| 微信授权登录/注册 | `POST /api/auth/maintainer/wechat-login` | `MiniappAuthController` |
| 登录态校验 | `GET /api/auth/session` | `MiniappAuthController` |
| 个人头像上传 | `POST /api/auth/avatar` | `MiniappAuthController` |
| 账号注销 | `POST /api/auth/account/cancel` | `MiniappAuthController` |
| 我的工作台 | `GET /api/mine/dashboard` | `MineController` |
| 基础信息读取/保存 | `GET/PUT /api/mine/profile` | `MineController` |
| 访问记录 | `GET /api/mine/visits` | `MineController` |
| 团队列表/创建 | `GET/POST /api/mine/teams` | `MineTeamController` |
| 团队详情/保存 | `GET/PUT /api/mine/teams/{teamId}` | `MineTeamController` |
| 团队成员候选查询 | `GET /api/mine/teams/{teamId}/member-candidate` | `MineTeamController` |
| 邀请团队成员 | `POST /api/mine/teams/{teamId}/members` | `MineTeamController` |
| 团队邀请详情/接受/拒绝 | `GET /api/mine/team-invitations/{memberId}`、`POST /api/mine/team-invitations/{memberId}/accept`、`POST /api/mine/team-invitations/{memberId}/reject` | `MineTeamController` |
| 团队图标上传 | `POST /api/mine/teams/{teamId}/avatar` | `MineTeamController` |
| 消息列表 | `GET /api/mine/messages` | `MineMessageController` |
| 消息未读数 | `GET /api/mine/messages/unread-count` | `MineMessageController` |
| 消息已读 | `PUT /api/mine/messages/read`、`PUT /api/mine/messages/read-all` | `MineMessageController` |
| 积分概览/流水/试算 | `GET /api/mine/points`、`GET /api/mine/points/transactions`、`POST /api/mine/points/calculate` | `MinePointController` |

新增接口时优先从后端 Controller 和 DTO 获取字段名，不要根据 PRD 自行猜字段。后端倾向下发展示文案、动作路径和样式 token，例如 `roleText`、`memberCountText`、`statusTone`、`userStatusTone`、`followTone`、`actionUrl`；小程序只做空值兼容和必要兜底。

## 登录与会话

登录态封装在 `utils/session.js`：

- `setToken()` 保存后端返回的 token。
- `clearToken()` 清除当前设备登录态。
- `hasLocalToken()` 用于页面请求前本地预检。
- `handleMaintainerAuthRequired()` 处理维护者 401：清维护者 token、toast 提示并跳转登录页。
- `precheckMaintainerWechatLogin()` 预检当前微信身份下一步是否需要手机号授权。
- `maintainerWechatLogin()` 调用维护者微信登录接口。

页面请求维护者接口前，沿用现有模式：

```js
if (!hasLocalToken()) {
  this.redirectToLogin()
  return
}
```

维护者接口收到 `error.authRequired` 时统一调用 `handleMaintainerAuthRequired(error.message)`。访客接口使用 `utils/visitor-session.js` 的 `/open` 刷新与重试流程，不跳转维护者登录页。

登录与注册流程注意点：

- 页面加载时用一次 `wx.login()` code 调预检接口，真正登录或注册时必须重新调用 `wx.login()`，不能复用已消费的临时码。
- 已绑定微信身份的用户点击同一个正式主按钮直接登录，不调用手机号能力；仅预检判定为新用户时启用 `getPhoneNumber`。
- 首次注册需要手机号组件返回的 `phoneCode`，推荐码选填。
- `wx.pluginLogin()` 可能不存在或失败，当前策略是失败时传空 `pluginLoginCode`，不阻断注册。
- 首次注册不要求头像和昵称，页面提交空值，后端使用“微信用户”和系统默认头像；默认头像固定为 `https://cdn2.we-folio.dingchenyong.top/system/wefolio-default-avatar-512.jpg`，仅用于今后新注册用户，不回填已有用户；用户可在注册后到基础信息页补充。
- 预检失败时保持手机号能力关闭，正式主按钮用于重新识别，不得默认要求老用户授权手机号。

## 上传与 COS

个人头像：

- 使用 `utils/avatar.js`。
- 远程 URL 直接复用；本地临时文件才走 `wx.uploadFile`。
- 头像上限 5MB；超过时用 `wx.compressImage` 压缩到 512x512，质量 95。
- `wx.uploadFile` 不走 `request.js`，必须手动补 Bearer token。
- 上传接口返回字符串 JSON，需要解析后读取 `data.url`。

团队图标：

- 使用 `utils/team-avatar.js`。
- 必须先有 `teamId`。
- 本地文件上限 5MB；超过时用 `wx.compressImage` 压缩到 512x512，质量 95，压缩后仍超过上限则提示“团队图标不能超过 5MB”。
- 上传到 `/api/mine/teams/{teamId}/avatar`，成功后用返回 URL 调 `PUT /api/mine/teams/{teamId}` 回写团队资料。

后端 COS 路径规则：

```text
个人：{uniqueCode}/others/{uuid}.{ext}
团队：{teamCode}/others/{uuid}.{ext}
图片作品：{uniqueCode}/work/image/{uuid}.{ext}
视频作品：{uniqueCode}/work/video/{uuid}.{ext}
作品集素材：{uniqueCode}/protfolio/{uuid}.{ext}
```

不要在小程序端拼最终 COS key；小程序只上传文件或提交后端返回的公开 URL。

## 页面开发模式

页面逻辑优先沿用当前结构：

1. `data` 中放安全默认值，例如 `normalizeDashboard({})`、`normalizeTeamList({})`、`normalizeMessageList({})`。
2. `onLoad` 或 `onShow` 调 `bootstrap()`。
3. `bootstrap()` 先做 `hasLocalToken()`，再请求后端。
4. 页面保持 `loading`、`errorMessage`、内容三态。
5. 后端响应先进 `utils/*` 的 normalize，再进入 `setData()`。
6. 表单提交前由 utils 构建 payload 和校验结果，页面只负责 toast、loading 和导航。

适合放在 utils 的逻辑：

- 字段裁剪和 payload 构造。
- 展示文案兜底。
- 数字、列表、状态色调归一化。
- 表单长度、数量、重复校验。
- 可注入 `wxApi` 的上传或请求辅助函数。

页面 JS 不要堆复杂业务规则。若规则可以用 Node `node:test` 验证，优先抽到 utils。

## 业务规则要点

基础信息：

- 姓名/艺名、职业身份、服务城市最大 50 字。
- 个人简介最大 500 字。
- 标签最多 10 个，单个最多 10 字，不能为空、不能重复。
- 标签颜色必须来自 `utils/profile.js` 的 9 色色板，并与后端 `MineProfileService` 保持一致。
- 头像更新次数后端有限制，小程序只展示后端错误，不自行绕过。

团队：

- 团队名称必填，最大 100 字。
- 团队简介最大 1000 字。
- 当前角色以后端 `role`、`canMaintain` 和 `canManageMembers` 为准；前端隐藏按钮只是体验优化，不能视作权限来源。
- `OWNER` 和 `MANAGER` 可维护团队资料，`MEMBER` 只查看。
- 只有 `OWNER` 可查询成员候选人、发送邀请、维护成员权限；添加成员页不能提供拥有者角色选择。
- 邀请成员只允许 `MANAGER` 或 `MEMBER`，团队内职业最大 50 字；引用权限默认与设计稿一致：个人作品集和头像资料开启，个人作品素材关闭。
- 团队列表只展示后端返回的已加入团队；待确认等状态在维护页成员列表展示。
- 团队邀请消息跳转到 `/pages/team-invitations/team-invitations?memberId=...`，邀请页只处理当前登录用户自己的邀请。
- 成员列表搜索在本地按 `displayName`、`profession`、`uniqueCode` 过滤。
- `statusTone`、`userStatusTone` 直接映射 `.role-pill.teal/.amber/.muted` 等样式 token。

消息：

- 消息均为系统消息，不做私聊、回复或会话能力。
- 筛选项固定为全部、未读、团队、积分，对应 `utils/messages.js` 中的 `FILTER_QUERY`。
- 单条/批量已读只提交未读消息 ID，payload 为 `{ messageIds: [...] }`。
- 全量已读在全部筛选下提交空对象；团队或积分筛选下提交 `{ category: 'TEAM'|'POINT' }`。
- 列表中的 `actionUrl` 由后端生成，团队邀请跳转到邀请处理页；小程序不在消息列表里直接接受或拒绝邀请。

访问记录：

- 指标字段来自 `summary.totalVisitCount`、`summary.todayVisitCount`、`summary.scheduleQueryCount`。
- 趋势点来自 `trend.points`，canvas 只在 `onReady` 或数据更新后绘制。
- 跟进状态色调使用后端 `followTone`，前端只拼 `follow-pill ${tone}`。
- 不向维护者展示未经授权的访客手机号、openid 或真实身份。

积分与作品集：

- “我的”页只展示工作台返回的积分摘要和低余额提醒；完整积分页接入前，充值按钮保持明确的体验版提示。
- 积分概览、流水和试算接口已有后端实现，新增页面时优先对接 `MinePointController`，不要在前端自行计算扣费。
- 创建团队会消耗积分并由后端校验余额、初始化团队 COS 目录，小程序只展示后端错误。
- 团队没有积分账户，团队作品集访问不扣积分。
- 个人作品集余额不足时，访客端只展示 “UNDER MAINTENANCE / 维护中”，不得暴露维护者余额。
- 预览模式必须有明确标识并由后端排除访问统计和积分消耗，不能只依赖前端不发事件。
- 高级作品集 AI 只能渲染后端校验后的结构化 schema，不能执行 AI 生成代码。

## 视觉与交互

以 `design/prototype.html` 为视觉基准，并尊重现有 WXSS：

- 页面根节点使用 `.page-shell` 和页面级 class，例如 `.messages-page`、`.team-invitations-page`。
- 页面主体使用 `scroll-view`，当前列表型页面设置 `scroll-y type="list"`。
- 自定义导航统一使用 `<navigation-bar>`，一级 tab 页面不展示返回按钮，二级页面展示返回。
- 主色以深墨 `#17202a`、青绿 `#0f766e`、湖蓝 `#2d5f9a`、琥珀 `#8a4b09` 和浅背景 `#f5f7fb` 为主。
- 组件样式沿用 `.panel`、`.metric-grid`、`.primary-button`、`.secondary-button`、`.role-pill`、`.skeleton-panel` 等命名。
- 登录页 segmented tabs 当前用 flex 兼容 Skyline，不要改成 CSS grid。
- 消息筛选、团队角色选择、权限开关等横向控件优先使用 flex，避免 Skyline 下 grid 兼容差异。
- 需要动画展开的区域保持节点常驻，用 `max-height/opacity/transform/pointer-events` 切换，避免 `wx:if` 导致无法过渡。
- 底部主导航固定为“档期 / 作品 / 作品集 / 我的”，图标 + 文字，当前仅“我的”可用。
- 静态系统小图放在小程序本地包 `assets/system/`，页面和脚本使用 `/assets/system/...` 引用；代码包内图片和音频资源总量需控制在 200KB 内。超过 200KB 的大图使用 `https://cdn2.we-folio.dingchenyong.top/system/...` 远程加载并依赖微信/CDN 缓存。当前本地图包括访客记录、团队、消息和作品空态图，登录背景与 logo 走 CDN；不要退回旧 OSS URL、COS 源站域名或无 `/system/` 的路径。
- 按钮和行项目要保证足够点击区域；用于删除、返回等图标触发器可使用 `view` 加 `aria-role="button"` 和清晰 `aria-label`。

改 WXML/WXSS 时，优先运行相关 layout 测试，因为它们锁定了 Skyline 兼容、动画挂载、按钮宽度、静态资源和图标结构。

## 测试约定

小程序测试使用 Node 内置 `node:test` 和 `assert`。

常用命令：

```bash
cd projects/miniapp
node --test tests/*.test.js
```

按改动选择重点测试：

- 请求和登录态：`tests/request.test.js`、`tests/session.test.js`
- 登录页布局和注册流程：`tests/login-layout.test.js`
- 我的页和底部图标：`tests/dashboard.test.js`、`tests/mine-layout.test.js`、`tests/tabbar-icons.test.js`
- 基础信息：`tests/profile.test.js`、`tests/profile-layout.test.js`、`tests/avatar.test.js`
- 消息中心：`tests/messages.test.js`、`tests/messages-layout.test.js`
- 团队：`tests/teams.test.js`、`tests/team-layout.test.js`、`tests/team-avatar.test.js`
- 访问记录：`tests/visits.test.js`、`tests/visits-layout.test.js`

新增或修改业务规则时：

- 先补 utils 单元测试，再改实现。
- 页面结构或样式约束用静态布局测试覆盖。
- 上传逻辑通过可注入 `wxApi` 的 mock 测试，不访问真实微信或 COS。
- 在说明“已完成”“测试通过”前，必须运行能证明结论的命令并阅读退出码和测试汇总。

## 编码规范

- 使用 CommonJS：`const xxx = require(...)`、`module.exports = {...}`。
- 保持现有微信小程序写法，不引入构建器、npm 依赖或 TypeScript，除非用户明确要求。
- JS 注释如需新增，使用中文，并解释业务原因或平台限制；不要写无信息量注释。
- 不新增 `// TODO`；后续事项写在用户可见说明或任务文档中。
- 不在 WXML 里拼复杂业务判断；状态、文案和颜色 token 尽量在 utils 中准备好。
- 不硬编码后端敏感字段、密钥、手机号、openid、access_token 或 Authorization。
- 不打印真实 token、手机号、openid、微信 code、phoneCode、pluginLoginCode。
- `project.private.config.json` 属于本地开发配置，非必要不要修改。
- 若需要调整 `DEFAULT_BASE_URL` 或静态系统资源路径，同步更新对应测试。

## 安全与工作区

- 访客端页面不得强制登录，维护者端接口必须走 `Authorization`。
- 前端权限控制只做展示，所有团队角色、内容归属、积分扣费和线索查看权限以后端为准。
- 不要把后端 `.env`、微信 AppSecret、COS 密钥或真实用户数据复制进小程序代码、测试或回复。
- 工作区可能已有用户未提交改动；不要回滚、格式化或覆盖与当前任务无关的文件。
- 编辑范围保持小，优先遵循现有页面、utils 和测试的命名风格。
