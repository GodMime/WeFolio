# 作品集分享页美化 · 设计讨论记录

> 会话导出，配套设计稿：`design/portfolio-share-beautify.html`（v3.0，浏览器打开查看）。
> 导出时间：2026-07-24。

## 需求演进

1. 初始诉求：个人/团队作品集的预览页与访客页视觉一致，希望美化分享页，先出设计稿。
2. 档期查询只能查一天，不想暴露全部档期。
3. 关注现有组件配置体系——组件参数是动态配置的，设计必须尊重。
4. 轮播图与个人资料是独立组件，不做跨组件数据关联。
5. 资料卡不要悬浮。
6. 轮播图分段进度条保留项目现状。
7. 整体重设计四原则：不合并组件；参考现有配置项向前兼容；色系不参考其他页面（访客页可完全不同风格）；作品集新增色系配置。
8. 最终收敛（v3.0）：**除作品组件外全部维持现状只适配色系；作品组件微调间距 + 新增作品标题/说明显隐开关**。

## 最终方案（v3.0）

### 色系配置 theme（新增）

- `wf_portfolio.schema_json` 根级新增可选键 `theme`，白名单 `GOLD / ROSE / BLUE / PINE`（墨金默认 / 绛红 / 黛蓝 / 松绿）。
- 后端 Validator 校验白名单、非法值回落 GOLD；RenderService 透传；前端 normalize 缺省 GOLD，页面根节点挂 `theme-xxx` class，组件既有强调色改为主题变量。
- 编辑器（个人 standard-edit、团队 standard-edit）顶部新增"页面色系"选择器，即选即预览。
- 兼容：旧 schema_json 无此键 → 默认 GOLD；旧版小程序忽略此字段不报错。

### 主题色应用点（仅颜色替换，布局全部不动）

| 元素 | 现状 | 改为 |
|---|---|---|
| 档期查询 提交/打开按钮、月历选中日期 | `#17202a` | 主题色 |
| 档期查询 档位选中态 | 描边 `#c7d8ea` / 浅底 `#f3f8fc` | 主题色描边 / 主题浅底 |
| 联系表单 提交按钮 | `#17202a` | 主题色 |
| 团队访客页主按钮 | `#0f766e`（与个人不一致，顺带统一） | 主题色 |
| 作品组件 分组标签选中态 | `#000` | 主题深色 |
| 页面底色 / 分隔线 | 白 / 灰 | 近白的主题底色 / 主题线 |

不动的颜色：正文/辅助文字、灰底、disabled 灰、错误红 `#9f2d45`、轮播进度条（白色）、`tags[].color`、档位 slot color、divider color/heightPx。

### 作品组件（WORK_GRID / WORK_LIST / SINGLE_WORK）

WORK_GRID / WORK_LIST 间距微调：

| 项 | 现状 | 调整后 |
|---|---|---|
| 卡片列间距 | 12rpx | 16rpx |
| 卡片行间距 | 18rpx | 24rpx |
| 网格容器上边距 | 22rpx | 24rpx |
| 封面→标题 | 10rpx | 12rpx |
| 标题→说明 | 4rpx | 6rpx |

新增配置（可选、缺省 = 线上现状）：

- `showTitle`：WORK_GRID、WORK_LIST、SINGLE_WORK 均默认 true；关闭后作品标题行不渲染，呈"纯图墙"。
- `showDescription`：WORK_GRID、WORK_LIST、SINGLE_WORK 均默认 false；开启后显示作品说明。
- SINGLE_WORK 保留已有的 `showTitle`，并新增 `showDescription`；两个开关的缺省值分别为 true、false。

### 完全不动

组件顺序与启停、renderData 既有字段、所有组件布局排版、轮播进度条、档期单日查询逻辑、分享卡片、访问统计、配置色、mock 体验版。

## 关键技术事实（探索结论）

- 组件配置整体存于 `wf_portfolio.schema_json`（个人 `standard-personal-v1` / 团队 `standard-team-v1`），无独立组件表；组件参数在每项的 `config` 自由键值内。
- 后端装配：`PortfolioRenderService`（个人，强类型字段）/ `TeamPortfolioRenderService`（团队，自由 `data` JSON）。
- 前端规范化：`projects/miniapp/utils/visitor-portfolio.js`（个人）；团队深加工在 `pages/team-portfolios/components/*` 各组件内。
- 预览页/访客页共用渲染组件，改组件样式两边自动同时生效。
- 访客端档期月历已在前端抹掉每日提示（`portfolio-schedule-query.js` 的 `hideVisitorScheduleHints`），但 `schedule-options` 接口仍下发整月 schedules，抓包可见——彻底不暴露需后端收敛该接口（待定，独立跟进）。
- 分组"全部"有两层：后端默认组 `g_all` + 前端聚合组 `__all`。
- 个人编辑器保存时会删除 QR_CONTACT 的 title/description；多个组件 title 后端支持但编辑器无 UI。

## 待确认事项

1. **默认 GOLD 色系的主色取值**：取金色 `#b88a44`（现有作品集按钮从墨色变金色，风格焕新）还是取墨色 `#17202a`（默认 = 线上现状，金色仅点缀）。
2. 四套色系的具体色值是否调整（可增减主题）。
3. `schedule-options` 接口整月数据下发是否做后端收敛（彻底不暴露档期）。
