# 个人作品集删除方案

## 目标

作品集列表页支持个人作品集左滑露出删除按钮，点击删除并确认后删除作品集。删除后维护端列表不再展示该作品集，访客分享链接不可继续访问；访问历史和客户线索继续保留，并通过快照字段展示删除前的作品集名称。

本方案只覆盖标准个人作品集。团队作品集和高级作品集仍按当前未开放能力处理。

## 数据表影响

### 新增快照字段

`wf_visit_record` 新增：

```sql
portfolio_title_snapshot VARCHAR(100) NULL COMMENT '被访问作品集标题快照'
portfolio_share_code_snapshot VARCHAR(32) NULL COMMENT '被访问作品集分享编码快照'
```

用途：作品集删除后，访问历史仍能展示被访问作品集名称和分享编码上下文。

`wf_contact_lead` 新增：

```sql
portfolio_title_snapshot VARCHAR(100) NULL COMMENT '来源作品集标题快照'
portfolio_share_code_snapshot VARCHAR(32) NULL COMMENT '来源作品集分享编码快照'
```

用途：作品集删除后，客户线索仍能展示“来自哪个作品集”。

### 删除时更新

`wf_portfolio`：

- 逻辑删除当前作品集。
- `deleted = id`，符合项目 `@TableLogic(value = "0", delval = "id")` 约定。
- 写入 `deleted_at`。

`wf_portfolio_reference`：

- 删除当前作品集全部引用记录。
- 包含草稿和已发布引用。
- 避免作品删除检查继续被已删除作品集阻塞。

### 删除时保留

- `wf_portfolio_history`：保留保存和发布历史，用于审计。
- `wf_portfolio_share_record`：保留分享记录。
- `wf_visit_record`、`wf_visit_event`：保留访问历史。
- `wf_contact_lead`：保留客户线索和跟进状态。
- `wf_point_transaction`、`wf_point_meter`：保留积分流水和计量记录，不退款。
- `wf_work`、`wf_work_tag`：作品库素材和标签不删除。

## COS 清理策略

采用保守版清理，不扫描整个 `{uniqueCode}/protfolio/` 目录。

删除前仅解析当前作品集的 `draft_config_json` 和 `published_config_json`，从以下字段收集 URL 或对象键：

- `share.coverUrl`
- `share.avatarUrl`
- `PROFILE` 组件 `config.profile.avatarUrl`
- `PROFILE` 组件 `config.profile.wechatQrUrl`
- `QR_CONTACT` 组件 `config.qrUrl`

只删除能明确匹配当前用户和当前作品集 ID 的对象：

```text
{uniqueCode}/protfolio/cover-{portfolioId}-{yyyyMMddHHmmss}-{8位hex}.jpg|png
{uniqueCode}/protfolio/profile-avatar-{portfolioId}-{yyyyMMddHHmmss}-{8位hex}.jpg|png
{uniqueCode}/protfolio/qr-contact-{portfolioId}-{yyyyMMddHHmmss}-{8位hex}.jpg|png
```

不删除：

- `{uniqueCode}/work/image/` 下的作品图片。
- `{uniqueCode}/work/video/` 下的视频和封面。
- `{uniqueCode}/others/` 下的基础头像、微信二维码等杂项。
- 其它作品集 ID 的 `protfolio` 素材。
- 已直传成功但从未保存进草稿或正式配置的孤儿素材。

COS 删除在数据库事务提交后执行。单个对象删除失败只记录 warn 日志，不回滚数据库删除，也不阻断其它对象继续清理。

## 后端接口

新增维护端接口：

```text
POST /api/mine/portfolios/delete/{portfolioId}
```

`MinePortfolioService.deletePortfolio(portfolioId)` 流程：

1. 读取当前登录用户 ID。
2. 校验作品集存在且属于当前用户。
3. 校验 `owner_type = USER`、`template_type = STANDARD`。
4. 解析草稿和正式配置，收集保守版 COS 清理对象键。
5. 删除当前作品集引用。
6. 逻辑删除 `wf_portfolio` 并写入 `deleted_at`。
7. 事务提交后执行 COS 对象清理。

## 快照写入

`PortfolioVisitService.recordOpen(...)`：

- 新建访问汇总时写入作品集标题快照和分享编码快照。
- 更新已有访问汇总时刷新快照，保证作品集改名后最近访问记录能展示最新名称。

`ContactLeadService.submit(...)`：

- 插入线索时写入作品集标题快照和分享编码快照。

标题快照从已发布配置 `publishedConfigJson.share.title` 获取，缺失时兜底为“个人作品集”。

## 小程序交互

作品集列表页复用作品库左滑删除模式：

- `revealedPortfolioId`：当前露出删除按钮的作品集 ID。
- `portfolioTouchStart`：滑动起点。
- `deletingPortfolioId`：删除中的作品集 ID。

交互流程：

1. 用户在个人作品集卡片左滑。
2. 卡片右侧露出红色“删除”按钮。
3. 点击删除后弹出确认框。
4. 确认后调用删除接口。
5. 成功后提示“作品集已删除”，刷新列表和统计。
6. 失败时展示错误提示；登录失效走现有登录态处理。

## 错误处理

- 作品集不存在、已删除或非本人作品集：返回“作品集不存在”。
- 非标准个人作品集：返回“当前作品集暂未开放维护”。
- 并发删除失败：返回“作品集删除失败，请刷新重试”。
- COS 删除失败：只记录日志，不透出给用户。

## 验证覆盖

- 数据库 migration 测试锁定访问记录和客户线索快照字段。
- 访问记录测试覆盖新建和更新时写入快照。
- 客户线索测试覆盖提交时写入快照。
- 后端删除测试覆盖逻辑删除引用、保守版 COS 清理、单个 COS 删除失败继续执行。
- 控制器测试固定删除接口路径。
- 小程序测试覆盖左滑露出删除、确认删除调用接口并刷新列表、点击已露出卡片只关闭删除按钮。
