# 作品 SHA-256 去重设计

## 背景

`wf_work` 需要新增原文件 SHA-256 和缩略图或封面 SHA-256，用于识别同一维护者重复上传的作品。上传链路仍沿用现有两段式流程：小程序先向后端申请 COS 直传票据，上传完成后再调用确认接口创建作品。

本次改动不处理历史数据。当前环境没有历史作品数据，数据库迁移可以直接新增必填字段和唯一索引。

## 目标

- `wf_work` 保存原文件 SHA-256 和缩略图或封面 SHA-256，两个字段均必填。
- `wf_work.cover_object_key` 改为必填。
- 用 `user_id + media_sha256 + deleted` 建唯一索引，限制同一用户的未删除作品不能重复上传同一原文件。
- 新增作品重复时，提示已存在作品标题。
- 前端负责计算 SHA-256，后端信任前端提交值，不下载 COS 文件重新计算。
- 图片小于等于 100KB 时，缩略图直接复用原图，缩略图对象键和缩略图 SHA-256 复制原文件相关字段。

## 非目标

- 不做跨用户去重。
- 不做 COS 文件内容级强校验。
- 不做历史作品 SHA-256 回填。
- 不改变现有作品编辑、排序、删除、标签等功能语义。

## 数据库设计

新增 migration，例如 `V17__add_work_sha256_dedup.sql`。

`wf_work` 调整：

```sql
ALTER TABLE `wf_work`
  ADD COLUMN `media_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT '原文件 SHA-256' AFTER `media_object_key`,
  MODIFY COLUMN `cover_object_key` VARCHAR(512) NOT NULL COMMENT 'COS 缩略图或封面对象键',
  ADD COLUMN `cover_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT '缩略图或封面 SHA-256' AFTER `cover_object_key`,
  ADD UNIQUE KEY `uk_work_user_media_sha256` (`user_id`, `media_sha256`, `deleted`);
```

`wf_work_upload_task` 调整：

```sql
ALTER TABLE `wf_work_upload_task`
  ADD COLUMN `file_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT '当前上传对象的 SHA-256' AFTER `object_key`;
```

`file_sha256` 表达当前上传任务对应 COS 对象自身的 SHA-256。主文件任务保存原文件 SHA-256，缩略图或封面任务保存缩略图或封面 SHA-256。

## 后端设计

### DTO 与实体

- `WorkEntity` 新增 `mediaSha256`、`coverSha256` 字段。
- `WorkUploadTaskEntity` 新增 `fileSha256` 字段。
- `MineWorkUploadTicketRequest.UploadFileItem` 新增 `sha256` 字段。

后端字段和关键方法注释需明确说明：SHA-256 由小程序端计算并提交，后端为避免下载 COS 文件带来的性能开销，信任前端值，仅做格式、必填和唯一性校验。

### SHA-256 校验

后端新增统一校验方法：

- 空值报错。
- 去首尾空格后转小写。
- 必须匹配 `^[0-9a-f]{64}$`。

固定语义字符串需要提取为静态常量，例如正则、错误提示、列名和重复提示模板。

### 上传票据创建

`createUploadTickets` 在准备上传任务时读取 `UploadFileItem.sha256`，写入 `WorkUploadTaskEntity.fileSha256`。

主文件票据创建前先按当前用户和原文件 SHA-256 查询未删除作品：

- 如果已有作品，拒绝创建票据并提示 `作品已存在：「{title}」`。
- 封面或缩略图任务不参与作品重复拦截，只保存自己的 `fileSha256`。

同一批次内可继续保留现有对象键冲突和幂等逻辑；如果同批次出现相同主文件 SHA-256，应直接提示同批次重复文件。

### 上传完成确认

确认时仍先校验主任务和封面任务归属、状态、COS 头信息和封面任务关系，但不下载 COS 文件计算 hash。

确认规则：

- 主任务 `fileSha256` 写入 `wf_work.media_sha256`。
- 图片小于等于 100KB 且未传封面任务时：
  - `cover_object_key = media_object_key`
  - `cover_sha256 = media_sha256`
- 图片大于 100KB 必须传缩略图任务：
  - `cover_object_key = coverTask.objectKey`
  - `cover_sha256 = coverTask.fileSha256`
- 视频必须传封面任务：
  - `cover_object_key = coverTask.objectKey`
  - `cover_sha256 = coverTask.fileSha256`

创建作品前再次按 `user_id + media_sha256 + deleted=0` 查询重复作品，返回已有标题。插入作品时再捕获唯一索引冲突，兜底查询重复作品并返回同样提示。

### 响应与错误提示

重复上传统一提示：

```text
作品已存在：「{title}」
```

如果并发下唯一索引冲突但未能查到标题，则提示：

```text
作品已存在，请勿重复上传
```

当前 `upload-complete` 支持批量逐项返回失败，重复作品作为单项失败消息返回，不影响其它作品继续确认。

## 小程序设计

### SHA-256 计算

新增 `utils/sha256.js`，实现纯 JS SHA-256。小程序端通过 `FileSystemManager.readFile` 读取本地临时文件为 `ArrayBuffer` 后计算 SHA-256 小写 hex。

实现约束：

- 不新增 npm 依赖。
- 按文件逐个计算，避免多个 100MB 视频同时进入内存。
- 计算阶段页面文案显示为准备上传或计算指纹。
- 计算失败时提示用户重新选择文件。

### 上传链路

`normalizeChosenMediaFile` 后补充原文件 SHA-256，或者在 `handleSubmit` 前统一补齐所有待上传文件的 hash。

`buildUploadTicketPayload` 为主文件上传项增加 `sha256`。

`prepareCoverUploadFiles` 规则调整：

- 图片小于等于 100KB：不生成、不上传缩略图，后端确认时复用原图。
- 图片大于 100KB：生成缩略图，计算缩略图 SHA-256，并在封面票据请求中提交。
- 视频：必须有封面图，压缩后计算封面 SHA-256，并在封面票据请求中提交。

`buildCoverUploadTicketPayload` 为封面或缩略图上传项增加 `sha256`。

### 前端重复体验

后端可能在申请上传票据时直接返回重复作品标题。小程序直接展示后端错误消息即可。批量上传中若部分文件已拿到票据，后续确认失败仍按现有单项失败展示。

## 测试设计

后端测试：

- migration 结构测试：新增字段、`cover_object_key NOT NULL`、唯一索引存在。
- `MineWorkService` 上传票据测试：缺 SHA-256、格式错误、同批重复、已有作品重复。
- `WorkUploadTransactionService` 确认测试：
  - 图片小于等于 100KB 复用原图对象键和 SHA-256。
  - 图片大于 100KB 必须有缩略图任务。
  - 视频必须有封面任务。
  - 重复作品返回已有标题。
  - 唯一索引并发冲突兜底提示。

小程序测试：

- SHA-256 工具用标准测试向量验证。
- `work-upload` payload 测试：主文件和封面任务都提交 `sha256`。
- 图片小于等于 100KB 不创建封面上传任务。
- 图片大于 100KB、视频封面会计算并提交封面 SHA-256。

验证命令：

```bash
cd projects/java/wefolio-java-runtime
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn test

cd projects/miniapp
node --test tests/*.test.js
```
