# Mock 个人作品集组件补齐设计与实施方案（Implementation Plan）

> **For agentic workers:** 后续执行使用 `superpowers:subagent-driven-development` 或 `superpowers:executing-plans`，逐项完成本文复选框。本文档的创建不代表开始实现；未经用户明确指示，不执行 `git commit`。

**Goal:** 在现有 mock 体验中新增 4 个静态作品，补齐个人标准作品集缺少的 5 类组件、背景音乐能力，并更新已有组件。

**Architecture:** 沿用现有 mock 页面、本地数据、本地草稿和独立渲染结构。新增和更新的业务逻辑在 mock 专属目录实现，正式代码仅供行为和外观对照；现有无需改动的纯展示复用维持原状。全部组件通过现有 mock 编辑页、预览页接入，不增加页面或路由。

**Tech Stack:** 微信小程序、Skyline、glass-easel、CommonJS、WXML/WXSS、微信原生媒体能力、Node.js 内置 `node:test`。

**Spec:** 本文第 1–9 节为确认范围下的设计规格，第 10–12 节为实施步骤与验收标准；执行时整体阅读。

**日期：** 2026-09-17。**文档状态：** 代码实现与自动化验证已完成，Skyline/真机验收待完成。第 10–12 节已按源码、回归测试和独立复核更新；首次收尾记录见第 14 节，用户反馈后的字段与组件一致性复核见第 15 节。文档放在仓库现有的 `docs/plans/`。

## Global Constraints

- 本次只新增作品、新增组件、更新组件，以及这些功能在既有 mock 编辑页、预览页和作品页中的必要接入。
- 只补个人标准作品集，团队入口及其现有行为保持原状。
- mock 新增和更新的业务代码完全独立，不导入正式作品集业务实现，不修改正式页面、正式组件承载 mock 行为。
- 原有无需改动的纯展示组件、样式和工具复用保持原状；不开展公共依赖迁移，不扩大共享依赖。
- mock 页面禁止引入 request、session、上传工具、访客会话工具；禁止调用 `wx.request`、`wx.uploadFile`、后端 `/api/` 或使用维护者 token/session。
- 所有作品、作品集配置、资料、档期和组件交互只使用本地演示数据或 mock 本地草稿。
- 新建作品/作品集、保存草稿、发布、上传、真实分享、档期提交、联系信息提交等身份操作统一提示：`请去“我的”页面注册登录`。
- 头像继续统一使用 `https://cdn2.we-folio.dingchenyong.top/demo/demo-avatar.png`。
- 不新增页面、路由、分包、构建器、npm 依赖或 TypeScript；不修改 `app.json`、`app.js`、登录会话和后端代码。
- 遵守小程序包边界；组件不得反向引用分包工具；不得以无业务意义的主包引用绕过检查。
- 大媒体直接使用指定 CDN URL，不下载打包、不上传、不生成 COS 对象键。
- JavaScript 注释使用中文；固定语义文案、枚举和资源地址提取为常量。
- 保留无关未提交修改，不执行提交、发布或部署。

---

## 1. 范围与交付物

### 1.1 本次交付

1. 在现有 7 个作品上增加音频、GIF、两段视频，共 11 个作品。
2. 新增 `VIDEO_CAROUSEL`、`STRUCTURED_TEXT_SECTION`、`TEXT_GRID`、`CONTACT_INFO`、`HYPERLINK`，使个人组件库达到 15 类。
3. 增加背景音乐设置与悬浮播放控件。背景音乐是作品集级配置，不作为第 16 类内容组件。
4. 更新已有组件的媒体筛选、可见字段、排版、背景、分组和样式配置。
5. 完成现有 mock 作品页、编辑页、预览页的本地交互接入、旧草稿兼容与测试。

### 1.2 不纳入本次

- 团队作品集功能、团队组件、成员素材和权限。
- 正式页面/组件重构、公共组件迁移、全项目依赖独立化。
- App 全局会话维护、登录流程、令牌刷新、访问埋点和后端接口调整。
- 新的作品详情页、视频播放页、访客入口、分享路由或页面注册。
- 真实上传、保存、发布、联系人收集、档期查询、分享及任何后端写入。
- 作品集列表整体改版、刷新/加载状态重构、支付、积分等无关能力。

### 1.3 “无接口”与运行环境边界

mock 业务及本次新增依赖不得发起后端请求，也不得读取或改动正式登录状态。图片、音频、视频通过平台媒体能力读取公开 CDN 静态文件属于允许的展示行为。

本次沿用同一个小程序 App 运行环境。现有 App 在设备保留登录态时可能执行全局会话检查，登录页进入体验前也有其原有请求；这些宿主行为不在本次修改范围。不得为本任务清 token、拦截全局请求，或重新加入已被用户排除的 App 会话调整。验收分别记录 mock 自身行为与宿主既有行为，不把后者归因于新组件，也不宣称本次改变了整个进程的网络行为。

## 2. 当前差异与目标

盘点依据是当前源代码，不能依据较早文档中的“未实现”说明推断正式功能。

| 能力 | 当前 mock | 本次目标 |
|---|---|---|
| 组件种类 | 10 类 | 15 类，名称、媒体限制与正式个人组件对应 |
| 静态作品 | 图片 6、视频 1 | 图片 6、视频 3、动图 1、音频 1 |
| 背景音频 | 无 | 选择、开关、试听、移除、3 种控件样式及拖动 |
| 文字说明 | 基本文字、字体、字号、对齐 | 颜色、行间距、图片/GIF/视频背景与背景处理 |
| 个人资料 | 基本资料展示 | 布局、字段显隐、边框、颜色与留白 |
| 单/双列作品 | 本地选择与展示 | 分组编辑、选择顺序、标题/描述显示控制 |
| 单个作品 | 图片/视频、标题开关 | GIF、描述开关；继续使用既有页内展示 |
| 二维码/分割线 | 基础配置 | 二维码来源与本地素材选择、自定义分割线颜色 |
| 本地草稿 | mock revision 2 | mock revision 3，保留旧配置并兼容新增字段 |
| 后端能力 | 身份动作提示登录 | 保持原状 |

当前正式个人编辑器 revision 为 14，mock 为 2。二者独立维护，不把 mock 版本号机械设置为 14，也不直接导入正式配置归一化模块。

当前 mock 已有组件添加、排序、删除、背景色、底部菜单和本地预览。这些能力保留，更新只围绕新增作品和组件接入展开。

## 3. 静态作品设计

### 3.1 资源清单

保留现有 `101–107` 的 ID 和 URL。新增记录采用稳定 ID，避免旧草稿引用变化。

| ID | 名称 | 类型 | MIME | 资源 |
|---|---|---|---|---|
| 101–106 | 原有风景图片 | `IMAGE` | 原值 | 保持原有 6 张图片 |
| 107 | 原有风景视频 | `VIDEO` | `video/mp4` | 保持原有视频 |
| 108 | IF YOU - BIGBANG | `AUDIO` | `audio/mpeg` | [音频](https://cdn2.we-folio.dingchenyong.top/system/IF_YOU-BIGBANG.mp3) |
| 109 | OpenAI 透明动图 | `ANIMATION` | `image/gif` | [动图](https://cdn2.we-folio.dingchenyong.top/system/OpenAI-transparent.gif) |
| 110 | 蓝天流云 | `VIDEO` | `video/mp4` | [蓝天视频](https://cdn2.we-folio.dingchenyong.top/system/mixkit-blue-sky-background-as-the-clouds-travel-blown-by-the-26108-full-hd.mp4) |
| 111 | 海浪沙滩 | `VIDEO` | `video/mp4` | [海浪视频](https://cdn2.we-folio.dingchenyong.top/system/mixkit-waves-coming-to-the-beach-5016-full-hd.mp4) |

资源常量使用以下原始 URL，音频文件名中没有反斜杠：

```js
const MOCK_AUDIO_URL = 'https://cdn2.we-folio.dingchenyong.top/system/IF_YOU-BIGBANG.mp3'
const MOCK_ANIMATION_URL = 'https://cdn2.we-folio.dingchenyong.top/system/OpenAI-transparent.gif'
const MOCK_SKY_VIDEO_URL = 'https://cdn2.we-folio.dingchenyong.top/system/mixkit-blue-sky-background-as-the-clouds-travel-blown-by-the-26108-full-hd.mp4'
const MOCK_WAVES_VIDEO_URL = 'https://cdn2.we-folio.dingchenyong.top/system/mixkit-waves-coming-to-the-beach-5016-full-hd.mp4'
```

### 3.2 数据与缩略图

- 沿用 `id/workId/mediaType/title/originalFileName/mediaUrl/coverUrl/thumbnailUrl/previewUrl/mimeType/durationMs/width/height/description/sortOrder/tags` 等字段。
- `isVideo`、`isAudio`、`isAnimation` 分别由类型派生，不能用“非视频就是图片”的二分逻辑。
- 原有记录顺序不变，新记录依次追加；新增排序值为 `8000/9000/10000/11000`。
- 总数、全部筛选计数、标签计数从实际记录计算，不继续硬编码为 7。
- 沿用现有标签 ID 201；新增两段风景视频归入该标签，音频和动图不强行归入风景标签。因此“全部”为 11，“风景作品”为 9。
- 音频封面使用已有静态默认音频封面 URL：`https://cdn2.we-folio.dingchenyong.top/system/default-audio-cover-v1-200kb.png`。
- GIF 使用原始 GIF URL 展示，保留透明背景与动画；不转 JPG、不压缩成静态图。
- 新视频未提供封面，使用 mock 自有“暂无视频封面”占位及播放图标，不能把 MP4 URL 当成 `<image>` 的源，也不伪造不存在的缩略图 URL。
- 未核实的时长、文件大小和尺寸保持未知值，不编造。UI 隐藏未知数值或显示“未知”；播放器可用实际加载结果更新页面态，不通过后端补元数据。
- 演示作品仍采用现有本地可用状态；审核状态只表示演示记录，不触发真实审核服务。

### 3.3 媒体可选范围

| 使用位置 | 可选类型 | 当前可选 ID |
|---|---|---|
| 图片轮播 `CAROUSEL` | `IMAGE` | 101–106 |
| 视频轮播 `VIDEO_CAROUSEL` | `VIDEO` | 107、110、111 |
| 双列/单列作品 `WORK_GRID/WORK_LIST` | `IMAGE/VIDEO` | 101–107、110、111 |
| 单个作品 `SINGLE_WORK` | `IMAGE/VIDEO/ANIMATION` | 除 108 外的 10 个作品 |
| 超链接展示图 `HYPERLINK` | `IMAGE/ANIMATION` | 101–106、109 |
| 文字背景 | `IMAGE/ANIMATION/VIDEO` | 除 108 外的 10 个作品 |
| 背景音频 | `AUDIO` | 108 |
| 作品库浏览 | 全部四种类型 | 101–111 |

未知类型、未知使用位置一律不纳入选择。搜索和标签筛选在媒体筛选之后执行；已经选中的作品按选择顺序保存，不能因筛选列表顺序改变。

## 4. 新增组件设计

### 4.1 视频轮播 `VIDEO_CAROUSEL`

配置采用正式个人组件的字段语义：

```js
const videoCarouselConfig = {
  title: '视频作品',
  workIds: [107, 110, 111],
  showComponentTitle: true,
  showTitle: true,
  showDescription: false,
  displayStyle: 'STACKED',
  showSwipeHint: true
}
```

- 标题最多 10 字；`displayStyle` 为 `STACKED` 或 `PORTRAIT_CARDS`。
- 只选视频，去重后必须为 3–8 个；不足 3 个不能确认，第 9 个不能加入。
- 默认示例直接使用三个不同视频，不复制同一视频伪造记录。
- 支持选择、移除、顺序调整、两种样式、组件标题/作品标题/描述/滑动提示开关。
- 叠放模式支持拖动与吸附；竖版卡片保持正式版的封面比例与相邻卡片提示。
- 点击作品通过事件交给现有预览页视频弹层播放；不引入正式播放器页面。
- 样式切换、菜单切换、组件删除和卸载时清理轮播动画计时器。

### 4.2 结构化文字说明 `STRUCTURED_TEXT_SECTION`

- 支持 `EYEBROW/TITLE/PARAGRAPH/LIST/HINT/SPACER` 六种区块。
- 区块可新增、删除、调整顺序；每个文字区块独立设置字体、字号、字重、颜色、对齐和上下间距。
- 最多 20 个区块、合计 2000 字；列表为 1–10 项。
- 字号 10–96 rpx；区块间距为 0–128 rpx、步长 4；留白高度为 0–512 rpx、步长 4。
- 行间距为 0.5–3.0 倍、步长 0.1；留白区块不支持行间距。
- 支持图片、GIF、静音循环视频背景及原图/暗色蒙层/渐变处理。
- 编辑器维护独立临时配置，确认时校验并写入页面草稿；取消不写回该组件的本次修改。
- 视频背景只作装饰，不显示播放控件、不录制播放事件。

### 4.3 文字网格 `TEXT_GRID`

- 支持 1–8 行、1–5 列，列宽比例 1–4，最小行高 80–600 rpx。
- 支持矩形区域合并、单元格拆分、撤销；保留原有文字，不静默截断或丢弃内容。
- 每格最多 8 段，每段最多 8 个文字片段，全组件最多 2000 字。
- 片段支持字体、字号、字重和颜色；段落支持水平对齐、行间距、上下间距；单元格支持垂直对齐。
- 网格支持底色、边框开关、线宽 1–12 rpx、边框颜色、外侧留白 0–96 rpx；格间距、内边距、圆角为 0–48 rpx。
- 缩小网格若会移除非空单元格，必须提示先移走或清空范围外文字；非矩形合并、重复覆盖和空洞覆盖均拒绝。
- 撤销历史仅保留当前编辑会话的最近 40 步，不写入草稿。
- Skyline 下采用正式布局的独立实现，以 flex、测量和定位完成网格，不能依赖 CSS grid。

### 4.4 联系信息 `CONTACT_INFO`

- 配置为 `contactPhone/contactWechat`、边框开关、线宽、颜色及水平/垂直留白。
- 电话最多 32 字，微信最多 64 字；禁止换行与控制字符，至少填写一项。
- 使用明确的演示文字，例如“示例电话”“demo_wefolio”，不读取真实手机号或个人资料接口。
- “从资料填写”只取 mock 本地演示资料；不能调用正式 contact-info-editor 或其请求适配层。
- 点击复制当前显示内容，成功态显示“已复制”，2 秒后恢复；复制失败保留内容并提示重试。
- 边框关闭时保留已配置的颜色和留白偏好；再次开启后继续使用。

### 4.5 超链接 `HYPERLINK`

共同配置使用 `workId/actionType/showClickIcon/iconPosition`，展示作品仅为图片或 GIF。

```js
// 本地内部演示目标。
const internalHyperlinkConfig = {
  workId: 109,
  actionType: 'INTERNAL_PORTFOLIO',
  targetPortfolioId: 9002,
  showClickIcon: true,
  iconPosition: 'OVERLAY'
}

// 外部内容只复制，不访问外部页面。
const externalHyperlinkConfig = {
  workId: 109,
  actionType: 'EXTERNAL_LINK',
  externalContent: 'https://example.com/',
  promptText: '已复制，请在浏览器打开',
  showClickIcon: true,
  iconPosition: 'BELOW'
}
```

- 图标位置支持 `OVERLAY/OVERLAY_BOTTOM_CENTER/OVERLAY_CENTER/BELOW`；支持隐藏图标。
- 外部内容长度 1–2048，提示语长度 1–30；按真实复制成功回调显示提示，失败不显示成功态。
- 对齐正式提示时序：系统复制反馈后延迟 1700ms 显示自定义提示，持续 2500ms；隐藏时暂停、卸载时释放计时器。
- 切换动作类型时移除另一种类型的字段，避免配置同时保留互斥目标。
- 内部目标来自 mock 常量注册表；内置只读目标 9002 仅为组件演示快照，展示标题、文字和演示联系信息，不含递归超链接。
- 目标 9002 不增加作品集列表记录或新建能力；通过现有 mock 预览页的 `demoTarget=9002` 参数展示，不新增页面注册。
- 进入目标预览时暂停当前媒体；目标快照不覆盖用户 9001 的编辑草稿，返回后恢复原预览配置。
- 未注册目标提示“演示作品集暂不可用”；不能拼接正式访客地址、使用真实 shareCode 或请求后端查目标。

## 5. 更新已有组件

| 组件 | 更新内容 | 保留的边界 |
|---|---|---|
| `CAROUSEL` | 图片选择顺序、最多 9 张校验；新增媒体进入作品库后仍只筛图片 | 现有轮播展示复用不修改；只有 6 张样例也不放宽规则 |
| `PROFILE` | `VERTICAL/HORIZONTAL` 布局；头像、姓名、职业、城市、简介、标签、二维码 7 个可见字段；边框、颜色、留白 | 只读本地资料，头像固定为 demo 头像，不上传 |
| `SCHEDULE_QUERY` | 内联/弹层两种展示方式及标题说明配置正确贯通编辑与预览 | 日期选择在本地；提交继续提示登录；保持现有月历隐藏档期标记规则 |
| `WORK_GRID` | 分组新增、改名、删除、排序；分组作品选择/顺序；标题与描述开关 | 只选图片/视频；不接入作品接口 |
| `WORK_LIST` | 同样补齐分组编辑与标题/描述开关 | 沿用单列展示，不增加详情路由 |
| `SINGLE_WORK` | GIF、标题和描述开关、图片宽高适配、错误占位、视频互斥 | 仅页内媒体展示；不暴露需要新详情页的 `DETAIL_PAGE` 入口 |
| `QR_CONTACT` | 资料二维码/自定义图片来源、本地图片选择、尺寸和标签开关、点击预览 | 上传入口继续提示登录；空二维码显示空态；不生成真实联系二维码 |
| `CONTACT_FORM` | 内联/弹层样式和本地字段状态贯通 | 提交不保存联系人、不写本地联系人库，统一提示登录 |
| `TEXT_SECTION` | 字体、字号、颜色、行间距、对齐；图片/GIF/视频背景及处理方式 | 正式简单文字无独立字重编辑；旧 mock `title` 保留兼容，不新增该编辑项 |
| `DIVIDER` | 完整 HEX 颜色及旧枚举兼容、正整数像素高度 | 保留原有默认视觉值，不能把合法历史高度静默改掉 |

补充约束：

- 个人资料边框宽 1–12 rpx，水平/垂直留白 0–96 rpx。布局切换不能清空资料或可见字段配置。
- 作品分组名最多 20 字；配置与渲染分别维护组顺序和组内作品顺序。删除分组不能删除作品库记录。
- 单个作品采用 `workId/showTitle/showDescription`；历史 `showTitle` 保留，缺失 `showDescription` 默认 `false`。
- 既有 mock 中没有独立详情页能力；本次不复制正式 `openMode/detailOptions` 的路由行为，不把被排除的详情页需求藏进组件更新。
- 简单文字内容最多 200 字；字体为 `SYSTEM/WECHAT_SANS_SS`，字号 10–96 rpx，行距 0.5–3.0、步长 0.1，颜色支持跟随主题或合法 HEX。
- 旧文字未设置行距时继续使用原 CSS 行距，不能强制补成新的默认倍数。
- 文字背景字段为 `backgroundEnabled/backgroundWorkId/backgroundTreatment`；简单文字另有 `verticalAlignment=TOP/CENTER/BOTTOM`，结构化文字没有这一项。
- 文字背景默认关闭，背景处理默认 `GRADIENT`，简单文字垂直对齐默认 `CENTER`；确认关闭文字背景后删除 `backgroundWorkId`。
- 自定义二维码素材选择仅提供本地图片。没有真实二维码资源时，用户选中图片可体验布局与预览，界面不暗示该图片可联系真实账号。
- 组件间距作为这些组件组合展示的必要配置加入 `style.componentSpacingRpx`，默认 32，范围 0–96 rpx；现有背景色和底部菜单机制保持原状。

## 6. 背景音乐与媒体生命周期

### 6.1 背景音乐配置

```js
config.backgroundAudio = {
  enabled: false,
  workId: 108,
  displayStyle: 'DISC'
}
```

- 顶层配置只保存三个字段，不保存媒体 URL、音频实例、播放进度或计时器。
- 控件样式为 `DISC/SLEEVE/MINI_PLAYER`，分别对应轻量唱片、封套抽盘和迷你播放器。
- 默认示例已选择音频 108，但不开启背景音乐；用户开启后在本地预览体验。
- 编辑支持开启/关闭、选择、试听、移除和样式预览。关闭保留选择；移除将 `workId` 置为 `null` 并关闭，和文字背景关闭规则区分。
- 预览依据本地作品 ID 解析 `mediaUrl/coverUrl/title`，使用独立的 mock 音频控制器。
- 控件支持拖动、边界限制及结束后吸附右侧；区分拖动与点击，拖动不能误触播放。

### 6.2 互斥与清理

| 场景 | 行为 |
|---|---|
| 作品库试听音频 | 创建一个音频实例；开始前关闭当前有声视频 |
| 背景音乐开始 | 暂停单个作品视频，关闭有声视频预览弹层 |
| 单个视频或轮播视频开始 | 暂停背景音乐与试听音频，停止前一个有声视频 |
| 文字背景视频 | 静音循环，不抢占有声音频；切换菜单/隐藏时暂停 |
| 菜单切换 | 清理离开菜单的媒体与动画；作品集级背景音频配置保留 |
| 页面隐藏 | 暂停全部有声媒体、背景视频和提示计时器 |
| 页面显示 | 恢复可交互状态，不强制恢复已被用户暂停的音频 |
| 页面卸载/删除对应组件 | 销毁音频实例、视频上下文引用、拖动与轮播动画计时器 |
| 播放失败 | 状态回到暂停并提示重试；不得进入无限自动重试 |

用户开启背景音乐后，预览可按正式行为尝试播放一次；微信拒绝自动播放时保持可手动点击，不把播放调用成功当作实际播放成功。播放状态以 `onPlay/onPause/onEnded/onError` 为准，旧实例的迟到事件不得修改新实例状态。

## 7. 数据流、编辑状态与旧草稿

### 7.1 数据流

```text
静态作品 / 本地演示资料 / 本地目标快照
                  ↓
     mock 配置归一化、媒体筛选、校验
                  ↓
         既有 mock 编辑页页面草稿
                  ↓ 点击“预览”
    wefolio_mock_portfolio_draft 本地缓存
                  ↓
       重新解析本地作品并构建 renderData
                  ↓
   既有 mock 预览页 → mock 展示组件/媒体控件
                  ↑
       组件事件由页面处理，不请求后端
```

配置保存 ID 与编辑字段；渲染结果附带本地资源 URL。不能把渲染对象里的临时状态反向写回配置。

### 7.2 编辑与保存语义

- 现有“保存草稿”“发布”按钮继续只提示登录；不得改为成功保存或发布。
- 现有 `handlePreview()` 继续把当前页面草稿写入 mock 专属缓存，再打开现有预览页。
- 保持原有旧组件的即时页面草稿修改方式，不为本任务重构所有弹层的确认/取消语义。
- 单个作品保留“临时选择 → 完成后应用”，并补上 `showDescription`。
- 新增复杂编辑器使用独立临时副本，确认前校验；取消丢弃该副本。这样不能把文字网格中间的非法结构写入页面草稿。
- 新增图片/视频选择只是引用演示作品；不会创建、修改、删除作品库记录。
- 预览返回保留已有配置；重置只清除 mock 草稿 key，不读取或删除正式草稿、维护者或访客存储。

### 7.3 旧草稿升级

mock revision 从 2 升到 3，`schemaVersion` 继续使用现有 `standard-personal-v1`，存储 key 保持 `wefolio_mock_portfolio_draft`。

1. 读取旧对象，先检查基础结构；缺少版本的旧配置按旧格式兼容。
2. 保留组件 key、类型、顺序、配置、作品引用、已有分享信息、背景色与底部菜单。
3. 仅补新增字段的兼容默认值，例如 `componentSpacingRpx=32`、背景音频关闭、缺失的描述开关为 `false`。
4. 旧草稿不自动插入新组件、不替换用户作品选择；新组件通过组件库添加。
5. 首次体验或主动重置加载更新后的示例；旧草稿升级不能等同于重置。
6. 归一化后重新从本地资源生成 renderData，不能信任缓存中的旧 renderData。
7. 结构损坏时展示本地默认示例并提示“本地体验草稿无法读取，已展示默认示例”；不立即覆盖原存储，只有用户预览或重置才写入/清理。
8. 遇到高于当前支持版本的草稿不静默降级写回；提示不兼容并展示安全的默认示例。

当前 `normalizeMockPortfolioConfig()` 会重建仅有 `backgroundColor` 的 style，必须一并保留新增间距字段。当前渲染分支最后把未知组件当作联系表单，必须改成显式处理 `CONTACT_FORM`；未知组件显示“暂不支持的组件”占位并保留原配置，不伪装成其它类型。

### 7.4 默认演示配置

- 保留现有“主页/作品”两个底部菜单，不增加导航层级或页面。
- 主页展示资料、图片轮播、文字说明、结构化文字、文字网格、分割线、联系信息、二维码、档期查询与联系表单。
- 作品菜单展示视频轮播 `[107,110,111]`、单个 GIF `109`、单列/双列作品分组和超链接。
- 默认配置覆盖全部 15 类；二维码无资源时展示明确空态，仍能进入本地选择体验。
- 背景音乐默认选择 108、关闭；内置超链接目标 9002 是单独常量快照，不写入用户草稿。
- 示例文案和按钮沿用当前 mock 提示规则，不增加真实发布状态、访问人数或新建作品集记录。

## 8. 文件边界与接口

本节文件路径均相对仓库根目录。新增文件的名称作为实施时的约定；组件目录中的同名 `.js/.json/.wxml/.wxss` 为一个完整组件单元。

### 8.1 修改现有文件

| 文件/目录 | 责任 |
|---|---|
| `projects/miniapp/pages/mock/utils/mock-experience.js` | 保留现有导出；新增 4 个作品、15 类注册、配置/草稿升级、渲染分支和计数 |
| `projects/miniapp/pages/mock/works/works.{js,wxml,wxss,json}` | 四种媒体卡片、音频试听、GIF预览、新视频占位与媒体清理 |
| `projects/miniapp/pages/mock/portfolio-standard-edit/portfolio-standard-edit.{js,wxml,wxss,json}` | 新组件编辑器接入、旧组件配置、作品筛选、背景音乐及间距设置 |
| `projects/miniapp/pages/mock/portfolio-standard-preview/portfolio-standard-preview.{js,wxml,wxss,json}` | 新渲染分支、音频控制、事件处理、本地目标快照和生命周期 |
| `projects/miniapp/components/mock/portfolio-renderer/portfolio-renderer.{js,wxml,wxss,json}` | 显式组件分发、已有组件展示更新、事件转发 |
| `projects/miniapp/components/mock/portfolio-schedule-query/*` | 仅在现有展示方式配置接入确有缺口时补齐，不改变提交行为 |
| `projects/miniapp/components/mock/portfolio-contact-form/*` | 仅补齐展示方式/本地状态接入，不增加提交能力 |
| `projects/miniapp/pages/mock/styles/works.wxss` | 作品页新增媒体外观 |
| `projects/miniapp/pages/mock/styles/portfolio-standard-edit.wxss` | 当前编辑页中新增配置区域外观 |
| `projects/miniapp/pages/mock/styles/portfolio-standard-preview.wxss` | 当前预览页新控件、媒体与间距外观 |
| `projects/miniapp/tests/mock-experience.test.js`、`mock-page-parity.test.js` | 更新旧的作品数量与组件清单断言，保留已有规则测试 |

不改 mock 作品集列表与团队入口。组件样式优先放到各自的 WXSS，不把所有新样式堆进公共文件。

### 8.2 新增 mock 页面工具

均位于 `projects/miniapp/pages/mock/utils/`：

| 文件 | 责任 |
|---|---|
| `mock-work-media.js` | 四种类型标识、按组件/使用位置筛选、媒体资源规范化 |
| `mock-portfolio-components.js` | 新增组件默认配置、配置校验和明确的组件类型集合 |
| `mock-portfolio-text.js` | 简单/结构化文字、文字背景、颜色/行距校验及渲染 view model |
| `mock-portfolio-text-grid.js` | 网格配置、合并/拆分/撤销、覆盖校验和布局数据 |
| `mock-portfolio-audio.js` | 可注入 wx 能力的音频控制器、背景音频配置规范化 |
| `mock-portfolio-hyperlink.js` | 本地目标注册表、目标解析、外部复制提示控制 |

工具之间只能依赖 mock 本地模块或沿用的现有纯展示基础能力，不能新增正式业务模块依赖。作品库由调用方传给校验工具，避免 `mock-experience` 与组件配置工具循环依赖。

### 8.3 新增 mock 组件

均位于 `projects/miniapp/components/mock/`：

| 组件目录 | 责任 |
|---|---|
| `video-carousel/` | 视频轮播展示、手势、动画与点击事件 |
| `structured-text-section/`、`structured-text-editor/` | 结构化文字展示与区块编辑 |
| `text-grid/`、`text-grid-editor/` | 网格展示/测量、单元格选择与编辑操作事件 |
| `contact-info/`、`contact-info-editor/` | 联系信息展示、复制事件、内容和外观编辑 |
| `hyperlink/`、`hyperlink-editor/` | 图文入口、图标位置、两种动作配置 |
| `background-audio-control/` | 三种音频控件外观、拖动和播放切换事件 |
| `text-background-editor/` | 文字背景开关、处理方式及本地素材选择事件 |

`components/mock/` 属于主包，不能 `require pages/mock/utils/*`。配置归一化、作品解析、网格编辑操作由页面工具完成，再以属性传给组件。组件只输出用户操作、配置变更或布局测量结果；组件内部必要的手势/布局辅助代码放在自身目录，不访问作品库、缓存或请求层。

### 8.4 拟新增的页面工具接口

```js
// mock-work-media.js：未知上下文返回 []，不修改输入数组。
selectMockWorksFor(context, works) // -> Work[]
// context: 15类中的媒体组件类型，或 TEXT_BACKGROUND/BACKGROUND_AUDIO。

// mock-portfolio-components.js：调用方显式传作品库；错误返回具体文案。
createMockComponentConfig(componentType) // -> Config
validateMockComponentConfig(componentType, config, works) // -> { valid, message }

// mock-experience.js：沿用已有草稿结构；失败时保持原草稿不变。
applyMockComponentConfig(draft, componentKey, nextConfig, menuKey)
// -> { valid, message, draft }
normalizeMockPortfolioConfig(config) // -> Config；扩展并导出供测试使用
buildMockPortfolioRenderData(config) // -> RenderData；沿用既有导出

// mock-portfolio-text-grid.js：返回新对象，不原地改输入。
mergeMockGridCells(grid, selectedKeys) // -> Grid；非法操作抛含业务提示的 Error
splitMockGridCell(grid, cellKey) // -> Grid
createMockGridHistory(grid) // -> { get(), apply(next), undo(), clear(), size() }

// mock-portfolio-audio.js：页面在生命周期中显式调用。
createMockAudioController({ wxApi, onPlaying, beforePlay, onError })
// -> { setResource(resource), play(), pause(), toggle(), hide(), show(), destroy() }

// mock-portfolio-hyperlink.js：仅注册的本地目标可用。
resolveMockHyperlinkTarget(targetPortfolioId) // -> 本地只读快照的副本或 null
```

现有 `addMockComponent/removeMockComponent/reorderMockComponent/updateComponentConfig/getMockPortfolioDraft/saveMockPortfolioDraft/resetMockPortfolioDraft` 保持对当前页面可用，不借机全面改名或重写旧调用链。

### 8.5 组件事件与渲染约定

- 渲染继续保留现有顶层 `componentKey/componentType/name/sortOrder` 结构；新增组件增加各自明确的渲染字段，不强制迁移旧组件的数据封装。
- 媒体事件传 `componentKey/workId`，页面从当前本地 renderData 中定位作品；不信任事件携带的任意 URL。
- 展示组件不持有草稿或登录态；复制、播放、跳转由页面处理。
- 编辑器事件使用 `configchange` 传临时配置，`confirm` 申请校验应用，`cancel` 放弃当前临时副本；网格 `operation` 事件由页面工具处理后回传新配置。
- 背景音频控件 `toggle` 只表达用户意图，实际 playing 状态由播放器回调更新。

## 9. 错误处理与兼容策略

| 情况 | 处理 |
|---|---|
| CDN 图片/GIF加载失败 | 显示占位，保留标题；用户可重试，不请求作品接口 |
| 新视频无封面 | 显示文字占位和播放按钮，允许播放媒体本身 |
| 视频播放失败 | 提示“视频播放失败，请稍后重试”，清掉活动播放状态 |
| 音频失败或平台拒绝自动播放 | 控件保持暂停，允许用户点击重试，不自动循环重试 |
| 组件引用未知/不允许类型的作品 | 编辑时提示重新选择，预览显示空态；不查后端 |
| 字数、数量、颜色、行距或网格结构不合法 | 在当前编辑弹层显示具体错误，不关闭弹层，不写回非法配置 |
| 超链接目标不存在 | 提示“演示作品集暂不可用”，保留当前预览 |
| 复制失败 | 提示复制失败，不能展示成功状态 |
| 草稿损坏/版本过高 | 展示默认示例并说明原因，不自动覆盖原缓存 |
| 身份操作 | 统一使用现有登录提示函数，不模拟成功 |

不新增自动重试、上传降级、后端查询兜底或真实身份预检。Skyline 的层级、视频弹层和安全区按当前 mock 页面结构处理。

## 10. 分阶段实施任务

以下复选框记录代码及自动化验证状态，勾选不替代第 11.2 节的平台实测。原任务按纯工具和独立组件拆分；收尾修复按不重叠文件分工合并。原实现的测试先后顺序不追溯认证；本轮缺陷回归已验证先失败、修复后通过。

### 任务 1：新增资源与统一媒体筛选

**文件：** 修改 `pages/mock/utils/mock-experience.js`、`pages/mock/works/*`、`pages/mock/styles/works.wxss`；新增 `pages/mock/utils/mock-work-media.js`、`tests/mock-portfolio-media.test.js`。路径前缀均为 `projects/miniapp/`。

**输入/输出：** 输入现有作品记录及四个指定 URL；输出 11 个作品和 `selectMockWorksFor(context, works)`。

- [x] 新增测试包含下列媒体资源与筛选断言，并验证修复后通过。

```js
const assert = require('node:assert/strict')
const test = require('node:test')
const { MOCK_WORK_LIBRARY } = require('../pages/mock/utils/mock-experience')
const { selectMockWorksFor } = require('../pages/mock/utils/mock-work-media')

test('媒体库包含11条且组件只能选择允许的媒体', () => {
  const works = MOCK_WORK_LIBRARY.works
  const ids = type => selectMockWorksFor(type, works).map(work => work.id)
  assert.equal(works.length, 11)
  assert.equal(new Set(works.map(work => work.id)).size, 11)
  assert.deepEqual(ids('VIDEO_CAROUSEL'), [107, 110, 111])
  assert.deepEqual(ids('BACKGROUND_AUDIO'), [108])
  assert.deepEqual(ids('HYPERLINK'), [101, 102, 103, 104, 105, 106, 109])
  assert.equal(ids('WORK_GRID').includes(108), false)
  assert.equal(ids('WORK_GRID').includes(109), false)
  assert.deepEqual(ids('UNKNOWN'), [])
})
```

- [x] 按第 3 节加入常量和数据，修正类型派生、计数、标签和视频缩略图兜底。
- [x] 筛选函数使用封闭的类型映射，先筛类型再交给现有搜索/标签筛选；不因新增媒体放宽旧组件限制。
- [x] 作品页新增音频预览状态和 GIF 标识；音频实际生命周期在任务 6 接入。
- [x] 相关文件已在完整测试中通过；可独立运行 `node --test tests/mock-portfolio-media.test.js tests/mock-page-parity.test.js`；对旧的“7个作品”断言按新的真实标签数量修改，保留搜索测试。

**独立验收：** 作品数据与类型筛选正确，新资源不进入不支持它们的组件候选列表。

### 任务 2：组件注册与草稿兼容

**文件：** 修改 `pages/mock/utils/mock-experience.js`；新增 `pages/mock/utils/mock-portfolio-components.js`、`tests/mock-portfolio-draft.test.js`。

**输入/输出：** 输入任务 1 的作品库；输出 15 类默认配置、校验入口、revision 3 草稿与各类渲染结果。

- [x] 增加 15 类清单、旧草稿保留、未知组件与媒体引用失效测试。旧草稿核心断言如下。

```js
const assert = require('node:assert/strict')
const { normalizeMockPortfolioConfig } = require('../pages/mock/utils/mock-experience')
const before = {
  editorSchemaRevision: 2,
  style: { backgroundColor: '#151515' },
  components: [{
    componentKey: 'user_text', componentType: 'TEXT_SECTION', sortOrder: 1000,
    config: { content: '保留用户内容', fontSizeRpx: 26 }
  }],
  bottomNav: { enabled: false }
}
const migrated = normalizeMockPortfolioConfig(before)
assert.equal(migrated.editorSchemaRevision, 3)
assert.equal(migrated.style.backgroundColor, '#151515')
assert.equal(migrated.style.componentSpacingRpx, 32)
assert.deepEqual(migrated.components.map(item => item.componentKey), ['user_text'])
assert.equal(migrated.components[0].config.content, '保留用户内容')
assert.equal(migrated.components[0].config.lineHeight, undefined)
assert.equal(migrated.backgroundAudio.enabled, false)
assert.equal(before.editorSchemaRevision, 2)
```

- [x] 扩展组件注册表、默认值和明确的渲染分支，移除未知类型落入联系表单的隐式兜底。
- [x] 实现 `applyMockComponentConfig()`：定位组件、校验、复制草稿、替换目标配置并重建 renderData；校验失败返回原草稿。
- [x] 将草稿读取接到 revision 3 归一化，保留 key、顺序、菜单及旧字段；默认示例覆盖 15 类，旧草稿不自动追加。
- [x] 用存储 spy 验证 `get/save/resetMockPortfolioDraft()` 只访问 mock 专属 key；模拟损坏对象、未来版本和缺失字段。
- [x] 相关文件已在完整测试中通过；可独立运行 `node --test tests/mock-portfolio-draft.test.js tests/mock-experience.test.js`。

**独立验收：** 旧编辑内容可恢复；新增字段经过缓存再读仍存在；不影响正式存储。

### 任务 3：视频轮播与已有媒体组件

**文件：** 新增 `components/mock/video-carousel/*`、`tests/mock-portfolio-components.test.js`；修改 renderer、现有编辑页和预览页对应文件。

**输入/输出：** 输入任务 1 的作品库、任务 2 的配置/校验；输出可编辑和播放的视频轮播、更新后的单个作品及列表组件。

- [x] 已加入数量、去重、非视频、样式与顺序断言；对 8/9 个边界使用测试内构造的不同 ID 视频，不增加生产示例作品。

```js
const assert = require('node:assert/strict')
const { MOCK_WORK_LIBRARY } = require('../pages/mock/utils/mock-experience')
const {
  createMockComponentConfig, validateMockComponentConfig
} = require('../pages/mock/utils/mock-portfolio-components')
const works = MOCK_WORK_LIBRARY.works
const valid = createMockComponentConfig('VIDEO_CAROUSEL')
valid.workIds = [107, 110, 111]
assert.equal(validateMockComponentConfig('VIDEO_CAROUSEL', valid, works).valid, true)
assert.equal(validateMockComponentConfig('VIDEO_CAROUSEL', {
  ...valid, workIds: [107, 110]
}, works).valid, false)
assert.equal(validateMockComponentConfig('VIDEO_CAROUSEL', {
  ...valid, workIds: [107, 110, 108]
}, works).valid, false)
```

- [x] 建立 mock 视频轮播四件套；通过属性接收已解析作品，通过事件交给预览页播放。
- [x] 在现有编辑弹层添加作品选择、顺序、两种样式和显示开关；完成按钮调用本地校验。
- [x] 更新单个作品支持 GIF/描述；更新单/双列作品分组和显示选项，不增加详情页入口。
- [x] 将视频事件接到现有页内视频/根层弹层，覆盖缺少封面、播放错误和菜单切换清理。
- [x] 相关文件已在完整测试中通过；可独立运行 `node --test tests/mock-portfolio-components.test.js tests/mock-portfolio-media.test.js tests/mock-experience.test.js`。

**独立验收：** 三个视频可排序、切换样式和播放；GIF仅出现在允许的组件中。

### 任务 4：文字、结构化文字和网格

**文件：** 新增 `pages/mock/utils/mock-portfolio-text.js`、`mock-portfolio-text-grid.js`、结构化文字/网格/背景编辑组件，以及 `tests/mock-portfolio-text.test.js`；修改现有编辑页、renderer 与预览页。

**输入/输出：** 输入本地媒体库、临时配置及主题；输出可校验的文字配置、网格操作结果和展示 view model。

- [x] 为字数、区块数、颜色、行距、背景媒体类型写边界测试；至少包含下列无损操作测试。

```js
const assert = require('node:assert/strict')
const {
  createMockGridHistory, mergeMockGridCells
} = require('../pages/mock/utils/mock-portfolio-text-grid')
const grid = {
  rows: 2, columns: 2, columnWeights: [1, 1], rowMinHeightsRpx: [180, 180],
  gapRpx: 16, cellPaddingRpx: 24, cellRadiusRpx: 24,
  cellBorder: false, cellBackground: 'AUTO',
  cellBorderWidthRpx: 1, cellBorderColor: 'AUTO',
  horizontalMarginRpx: 0, verticalMarginRpx: 0,
  cells: Array.from({ length: 4 }, (_, index) => ({
    cellKey: `cell_${index}`, row: Math.floor(index / 2), column: index % 2,
    rowSpan: 1, columnSpan: 1, verticalAlignment: 'CENTER',
    blocks: [{
      blockKey: `block_${index}`, alignment: 'LEFT',
      marginTopRpx: 0, marginBottomRpx: 12,
      runs: [{
        runKey: `run_${index}`, text: index === 0 ? '保留内容' : '',
        fontFamily: 'SYSTEM', fontSizeRpx: 28, fontWeight: 'NORMAL', color: 'AUTO'
      }]
    }]
  }))
}
const snapshot = JSON.stringify(grid)
const history = createMockGridHistory(grid)
const selectedKeys = grid.cells.slice(0, 2).map(cell => cell.cellKey)
const merged = mergeMockGridCells(grid, selectedKeys)
history.apply(merged)
assert.equal(JSON.stringify(grid), snapshot)
assert.ok(JSON.stringify(history.get()).includes('保留内容'))
assert.deepEqual(history.undo(), grid)
assert.throws(() => mergeMockGridCells(grid, [
  grid.cells[0].cellKey, grid.cells[3].cellKey
]), /矩形/)
```

- [x] 实现第 4/5 节的文字与网格纯工具；测试构造函数使用完整合法配置，非法值返回具体错误，不能靠截断内容变合法。
- [x] 新组件只处理编辑交互/测量；合并、拆分和规范化操作经事件交给页面工具，再以新配置回传。
- [x] 增加文字背景选择与渲染，确保 GIF 使用原资源、视频静音循环；关闭背景后移除引用。
- [x] 旧简单文字内容、旧字号、缺省行距和旧 title 展示继续兼容；没有独立字重编辑项。
- [x] 相关文件已在完整测试中通过；可独立运行 `node --test tests/mock-portfolio-text.test.js tests/mock-portfolio-draft.test.js tests/mock-page-parity.test.js`。

**独立验收：** 文字编辑与预览一致；网格撤销不丢内容；媒体背景不发接口。

### 任务 5：联系信息、超链接和资料外观

**文件：** 新增联系信息/超链接组件与编辑器、`pages/mock/utils/mock-portfolio-hyperlink.js`、`tests/mock-portfolio-hyperlink.test.js`；修改 profile/二维码/分割线的 mock 编辑和展示分支。

**输入/输出：** 输入 mock 资料、作品库和本地目标 9002；输出联系信息、复制事件和现有预览页内的目标展示。

- [x] 写内容长度、控制字符、互斥字段、复制成功/失败、提示计时器和本地目标测试。

```js
const assert = require('node:assert/strict')
const { MOCK_WORK_LIBRARY } = require('../pages/mock/utils/mock-experience')
const { validateMockComponentConfig } = require('../pages/mock/utils/mock-portfolio-components')
const { resolveMockHyperlinkTarget } = require('../pages/mock/utils/mock-portfolio-hyperlink')
const works = MOCK_WORK_LIBRARY.works
const target = resolveMockHyperlinkTarget(9002)
assert.ok(target)
assert.equal(resolveMockHyperlinkTarget(123456), null)
target.config.components.length = 0
assert.ok(resolveMockHyperlinkTarget(9002).config.components.length > 0)
assert.equal(validateMockComponentConfig('HYPERLINK', {
  workId: 108, actionType: 'EXTERNAL_LINK',
  externalContent: 'https://example.com/', promptText: '已复制'
}, works).valid, false)
```

- [x] 实现 mock 联系信息及本地资料填充；组件只发复制事件，页面调用平台剪贴板。
- [x] 实现超链接动作切换、图标位置、注册目标查找；只向既有 mock 预览路由导航，拒绝任意目标。
- [x] 在预览页区分普通草稿与只读 `demoTarget`，返回原页不覆盖草稿，也不增加列表记录。
- [x] 更新资料布局/显隐/边框、二维码来源和自定义分割线颜色，上传入口继续提示登录。
- [x] 相关文件已在完整测试中通过；可独立运行 `node --test tests/mock-portfolio-hyperlink.test.js tests/mock-portfolio-components.test.js tests/mock-experience.test.js`。

**独立验收：** 复制结果真实反馈；内部演示跳转不出 mock；原草稿与团队入口不变。

### 任务 6：音频作品与背景音乐

**文件：** 新增 `pages/mock/utils/mock-portfolio-audio.js`、`components/mock/background-audio-control/*`、`tests/mock-portfolio-audio.test.js`；修改现有作品页、编辑页和预览页。

**输入/输出：** 输入音频 108 和背景音频配置；输出单实例媒体状态及三种控件，播放实例不写草稿。

- [x] 使用注入的 `wxApi.createInnerAudioContext` 写状态机测试，记录 create/play/pause/destroy 次数和 onPlay/onError 回调。

```js
const assert = require('node:assert/strict')
const { createMockAudioController } = require('../pages/mock/utils/mock-portfolio-audio')
const MOCK_AUDIO_URL = 'https://cdn2.we-folio.dingchenyong.top/system/IF_YOU-BIGBANG.mp3'
const calls = { create: 0, play: 0, pause: 0, destroy: 0, beforePlay: 0 }
const states = []
const errors = []
const wxApi = {
  createInnerAudioContext() {
    calls.create += 1
    const callbacks = {}
    return {
      onPlay(fn) { callbacks.play = fn },
      onPause(fn) { callbacks.pause = fn },
      onStop(fn) { callbacks.stop = fn },
      onEnded(fn) { callbacks.ended = fn },
      onError(fn) { callbacks.error = fn },
      play() { calls.play += 1; if (callbacks.play) callbacks.play() },
      pause() { calls.pause += 1; if (callbacks.pause) callbacks.pause() },
      destroy() { calls.destroy += 1 }
    }
  }
}
const beforePlay = () => { calls.beforePlay += 1 }
const onError = error => errors.push(error)
const player = createMockAudioController({
  wxApi, onPlaying: value => states.push(value), beforePlay, onError
})
player.setResource({ workId: 108, mediaUrl: MOCK_AUDIO_URL, enabled: true })
player.play()
player.hide()
player.destroy()
assert.equal(calls.create, 1)
assert.equal(calls.play, 1)
assert.ok(calls.pause >= 1)
assert.equal(calls.destroy, 1)
assert.equal(calls.beforePlay, 1)
assert.equal(errors.length, 0)
assert.ok(states.includes(true))
```

- [x] 音频 URL 由本地 workId 解析；关闭保留选择、移除清引用；三种样式共享同一播放状态。
- [x] 作品页接入试听，编辑页接入选择/试听，预览页接入背景音乐及右侧拖动吸附。
- [x] 将有声视频开始、页面隐藏、卸载与音频暂停/销毁连接；为迟到回调、失败重试和拖动不触发点击补测试。
- [x] 相关文件已在完整测试中通过；可独立运行 `node --test tests/mock-portfolio-audio.test.js tests/mock-portfolio-media.test.js tests/mock-portfolio-draft.test.js`。

**独立验收：** 音视频不叠播，隐藏暂停、卸载销毁，播放失败仍可手动重试。

### 任务 7：隔离、页面接入与整体回归

**文件：** 新增 `tests/mock-portfolio-isolation.test.js`；补齐现有 mock 测试和新测试，不修改正式业务实现或降低已有测试要求。

**输入/输出：** 输入前六项能力；输出完整 mock 编辑→预览→返回体验及隔离证据。

- [x] 递归发现 `pages/mock`、`components/mock` 的 JS/JSON/WXML/WXSS，检查新增依赖与源文件，不能继续只用固定文件清单漏掉新组件。
- [x] 记录现有允许的纯展示依赖作为冻结基线，新文件不得引入正式业务模块；已有依赖的传递调用链同样禁止请求/session/upload。
- [x] 在 VM 中对 `request/uploadFile/login` 等后端或身份能力使用抛错 stub，对正式存储 key 使用读写哨兵；执行新增组件编辑、预览、播放、复制及锁定操作。
- [x] 校验每类组件都能“创建默认配置→编辑→本地预览→返回”，样例覆盖 15 类；未知类型不被转换成联系表单。
- [x] 保留团队入口和保存/发布等动作的原有 handler 行为断言；证明没有新增页面、没有正式页面跳转。
- [x] 执行第 11.1 节全部命令并阅读退出码及汇总。
- [ ] 完成第 11.2 节的微信开发者工具与真机验证并记录结果。

**独立验收：** 不出现 mock 业务接口调用，不触碰正式存储，所有相关和完整测试通过。

## 11. 验证方式

### 11.1 自动化测试

在 `projects/miniapp/` 执行：

```bash
node --test tests/business-subpackages.test.js
node --test tests/mock-*.test.js
node --test tests/*.test.js
```

必须检查退出码与测试汇总。本轮三个命令均已执行并通过，结果见第 14 节；自动化通过不代表 Skyline 和真机验收通过。

测试覆盖重点：

- 11 个唯一 ID、四种媒体数量、四个完整 URL、正确的标签计数。
- 媒体选择矩阵与视频轮播 3–8 边界；图片轮播数量上限使用合成测试数据覆盖。
- 15 类组件的默认值、明确渲染分支、配置确认、取消和本地预览。
- 文字字数/行距、网格无损操作与合法覆盖、背景关闭清引用。
- 背景音频关闭保留选择、移除清引用、互斥播放和释放。
- 超链接只访问已注册 mock 目标，复制失败不显示成功，不污染原草稿。
- revision 2 升级、未知组件、损坏缓存、未来版本、不读取正式存储。
- 固定登录提示、不调用请求/上传/登录/会话能力、无跨分包依赖。

原测试中“覆盖全部组件”实际固定写了 10 类；应改为明确的 15 类目标，并在测试层对照正式个人组件类型集合。测试可读取正式源码或纯数据核对契约，但运行时 mock 不因此依赖正式实现，也不能直接把正式类型表作为 mock 生产注册表。

### 11.2 微信开发者工具与真机

1. 从现有体验入口进入，确认作品库 11 条；搜索蓝天/海浪可定位新增视频，风景标签只显示所属作品。
2. 音频试听可播放/暂停，GIF透明动画正常，两个新视频在无封面时可打开播放。
3. 添加视频轮播，选择三个视频并排序；切换叠放/竖版卡片，点击打开现有视频弹层。
4. 添加结构化文字，调字号、行距和区块顺序；图片/GIF/视频背景均正确显示。
5. 文字网格完成合并、拆分、撤销；长文本、暗色主题和不同屏宽无截断或重叠。
6. 联系信息复制、超链接外部复制提示和内部演示跳转正确；返回后原草稿仍存在。
7. 资料字段隐藏、横竖布局、分组作品、二维码来源、分割线颜色及组件间距与预览一致。
8. 背景音乐三种控件可拖动吸附；播放视频时背景音乐暂停，切后台不继续有声播放，返回不出现双实例。
9. 分别测试新体验、旧草稿、重置；保存/发布/上传/提交仍显示原提示。
10. 观察网络：mock自身只加载静态CDN媒体，不发业务请求；宿主进入前或全局既有请求单独记录，不借此扩大实现范围。

Node 测试不能证明 Skyline 真机动画、GIF 解码、视频层级和音频自动播放策略正常，这些必须实际检查。资源若存在格式或 CDN 可用性问题，保留可重试空态并记录具体资源，不擅自更换用户指定地址。

## 12. 完成标准

- [x] 只改 mock 相关数据、组件、现有页面的必要接入和测试；没有正式业务/App/路由变更。
- [x] 4 个新增资源 URL 完整正确，音频文件名无反斜杠；共 11 个作品，无重复视频替身。
- [x] 15 类组件均可添加、编辑、预览，所有媒体候选符合矩阵。
- [x] 三个视频可完成视频轮播的完整交互。
- [x] 背景音乐配置和三种控件可用，与视频互斥并正确释放。
- [x] 已有组件列出的更新可在现有编辑页和预览页中完成。
- [x] 原有本地缓存、身份动作提示、团队入口及静态头像原则保持。
- [x] revision 2 草稿不丢失用户配置；新默认示例覆盖全部组件。
- [x] 新增/更新业务实现保持 mock 独立；没有 request/session/upload/visitor 依赖或正式存储访问。
- [x] 分包边界、mock 相关及完整 Node 测试通过。
- [ ] Skyline 开发者工具与真机验收通过且有记录。
- [x] 未执行提交、发布或部署。

## 13. 源码核对入口

以下链接用于实施前核对实际字段和现有约定，不构成复用授权：

- [小程序项目规则](../../projects/miniapp/AGENTS.md)
- [mock 数据、配置与草稿](../../projects/miniapp/pages/mock/utils/mock-experience.js)
- [mock 编辑页](../../projects/miniapp/pages/mock/portfolio-standard-edit/portfolio-standard-edit.js)
- [mock 预览页](../../projects/miniapp/pages/mock/portfolio-standard-preview/portfolio-standard-preview.js)
- [mock 渲染组件](../../projects/miniapp/components/mock/portfolio-renderer/portfolio-renderer.wxml)
- [正式个人组件字段与约束](../../projects/miniapp/pages/portfolios/utils/portfolios.js)
- [正式媒体类型边界](../../projects/miniapp/pages/portfolios/utils/work-media.js)
- [正式个人超链接媒体范围](../../projects/miniapp/pages/portfolios/utils/portfolio-work-media.js)
- [正式结构化文字与文字背景规则](../../projects/miniapp/pages/portfolios/utils/portfolio-text-sections.js)
- [正式文字网格规则](../../projects/miniapp/pages/portfolios/utils/portfolio-text-grid.js)
- [正式背景音频字段](../../projects/miniapp/pages/portfolios/utils/portfolio-background-audio.js)
- [原 mock 规则测试](../../projects/miniapp/tests/mock-experience.test.js)
- [原 mock 外观对照测试](../../projects/miniapp/tests/mock-page-parity.test.js)
- [分包边界测试](../../projects/miniapp/tests/business-subpackages.test.js)

## 14. 2026-09-17 收尾与验证记录

本轮根据完成度检查补齐以下问题，未执行提交、发布或部署：

- 非法行距、颜色、数值和文字背景配置仅停留在编辑态，不写入页面草稿或预览缓存；合法旧组件编辑继续即时生效。
- 网格非法临时态的首次撤销恢复最近合法配置，不消耗合法历史；覆盖连续非法输入、修正后撤销及 40 步历史上限。
- 结构化留白区块的高度和上下间距均参与渲染；旧简单文字沿用 1.75 缺省行距，结构化文字仍为 1.7，显式行距正常覆盖且不回写缺省配置。
- 单列、双列、单作品选材补齐无封面视频占位和播放标识；GIF 标注为动图，标签来自实际演示数据；“我的”作品数与作品库同源为 11。
- 新增 `tests/mock-portfolio-isolation.test.js`，递归扫描 JS、组件注册、WXML 引用和样式引用的完整依赖闭包，冻结原有展示依赖；通过独立 VM 为全部 15 类执行创建、编辑、确认、预览和返回，并拦截后端能力和正式缓存访问。
- 独立代码复核未发现本轮修复的阻断问题。

### 14.1 自动化结果

运行目录：`projects/miniapp/`。以下命令均退出 0，无失败、跳过或取消：

| 命令 | 结果 |
|---|---|
| `node --test tests/business-subpackages.test.js` | 16 / 16 通过 |
| `node --test tests/mock-*.test.js` | 135 / 135 通过 |
| `node --test tests/*.test.js` | 2291 / 2291 通过 |
| `git diff --check` | 通过 |

本轮新增缺陷测试先确认失败，再修复并转绿；收尾定向文字、媒体和页面集成测试共 35 项通过。完整测试曾发现旧作品数断言仍为 7、新播放标识色值未使用现有主题变量，两处均已修正后重跑通过。

### 14.2 开发者工具与真机状态

已通过微信开发者工具 Stable 2.02.2608060 读取到本项目 mock 编辑页及 15 类组件入口；当前工具显示 WebView 模式。随后从登录页点击“先体验”，确认页面路径进入 `pages/mock/index/index`。继续读取渲染状态时工具长时间未响应并中断，因此本次只记录入口检查，不将其视为完整视觉、播放或 Skyline 验收通过。

待完成第 11.2 节全套检查：Skyline 实际布局和测量、GIF 透明动画、视频层级与 CDN 解码、背景音乐三种控件拖动和前后台播放、真机音视频互斥及网络行为。未连接真机，未运行真实设备验收；现有缓存和正式登录状态未主动清理或改写。

## 15. 2026-09-17 字段顺序、显隐与组件一致性复核

用户指出页面设置与正式版顺序、显隐不同后，重新按正式个人编辑页与展示组件逐项核对。第 14 节的自动化结果只证明当时的功能与隔离约束，不能据此推断视觉和编辑项完全一致。本轮修复如下：

| 对照范围 | 本轮修正 |
|---|---|
| 页面设置 | 顺序统一为组件间距、背景色、背景音频、底部导航；音频关闭隐藏其配置；选曲列表默认收起；已选音频才显示试听和移除；三种样例卡、选中态与文案对齐；样例事件可冒泡选卡而不直接播放 |
| PROFILE | 布局、条件边框、资料字段、展示字段连续排列；补齐本地标签增删和色板、字段计数；全菜单禁止重复添加；横排仅影响头像与身份区；关闭边框不增加外侧留白，开启缺省左右 32rpx |
| SCHEDULE_QUERY / CONTACT_FORM | 只开放正式页的展示方式字段，使用正式选项顺序、文案和选中态 |
| CAROUSEL / VIDEO_CAROUSEL | 候选显示选择序号；视频设置置于搜索之前；标题开关控制输入显隐；叠放卡片位置、入口、圆点条件和样式对齐；标题按 Unicode 字符限制 10 字 |
| WORK_GRID / WORK_LIST | 改为先选本地作品标签，再选当前标签作品；保留选择顺序和当前标签；标题、说明开关使用正式顺序与文案，兼容旧草稿分组 |
| SINGLE_WORK | 当前已选摘要、横向候选、作品名/说明开关相邻展示；GIF 加载失败回退缩略图 |
| QR_CONTACT | 来源选中态和分支对齐；个人资料来源跨菜单读取唯一 PROFILE；切换来源清空旧自定义选择；自定义来源未选图不能确认；去除正式页没有的尺寸/标签字段和预览文案 |
| TEXT_SECTION / DIVIDER | 去掉重复字号和分割线颜色入口；行距、对齐、颜色、背景顺序对齐；统一使用独立 mock 颜色控件；显示实际字体可用性 |
| STRUCTURED_TEXT_SECTION | 内容/背景分区、摘要与当前区块详情；区块预设字号/间距对齐；背景开启后 AUTO 前景使用浅色，宽高比和遮罩对齐；背景本地搜索、视频 poster 与前后台暂停 |
| TEXT_GRID | 布局/内容/外观分区、单元格/段落/片段焦点、效果预览与实测宽高同步；居中段落/边线默认值、条件边框控件和混合字号行高对齐；空网格不能确认 |
| CONTACT_INFO / HYPERLINK | 条件边框、留白顺序和效果预览；联系方式复制标记；超链接动作分支显隐、点击图标和文案对齐 |
| 编辑会话 | 取消恢复打开前草稿，新建取消不残留组件；非法输入仍不写草稿；关闭后迟到事件被忽略；子编辑器随编辑层关闭销毁，避免内部色板/分区状态残留 |

边界仍保持：只修改 mock 生产代码与测试、本文档；没有修改正式页面或正式组件，没有新增页面、路由或正式依赖。上传、保存、发布、预约等身份动作仍提示注册登录。媒体选材和作品集目标使用本地演示数据，因此不复制正式的后端分页、审核加载或身份操作；结构化区块排序继续通过上移/下移完成。正式的独立作品详情页仍属于第 1.2 节排除项。

### 15.1 本轮验证

全部命令读取退出码与汇总后记录，无失败、跳过或取消：

| 命令 / 检查 | 结果 |
|---|---|
| `node --test tests/business-subpackages.test.js` | 16 / 16 通过，退出 0 |
| `node --test tests/mock-*.test.js` | 180 / 180 通过，退出 0 |
| `node --test tests/*.test.js` | 2336 / 2336 通过，退出 0 |
| 开发者工具自带 `wcc` | 22 份 mock WXML 编译通过 |
| 开发者工具自带 `wcsc -lc` | 29 个 mock WXSS 入口及其导入闭包编译通过 |
| `git diff --check` | 通过，退出 0 |
| 独立复核定点测试 | 79 / 79 通过，未发现本轮新增阻断缺陷 |

新增回归先复现失败，再修复转绿；测试包括页面字段顺序与条件、音频选曲收起/保留/移除、组件取消与新增撤回、迟到事件、二维码来源切换、标签选择序号、网格撤销与实测预览、字体能力和媒体前后台生命周期。测试中的微信 API 由本地替身提供，递归隔离测试继续拦截后端调用及正式缓存访问。

编译检查只证明语法可编译，不等同于实际 Skyline 像素布局或真机音视频验收。第 14.2 节的工具限制与第 11.2 节设备验收待办保持有效；不能据本轮源码对照与 Node 测试宣称逐像素完全一致。未执行提交、发布或部署。
