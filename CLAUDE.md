# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repo Overview

WeFolio（映期Folio）— 面向婚庆/演艺从业者的 SaaS 微信小程序。帮助个人与团队维护作品、档期、作品集展示页，追踪客户访问记录。

GitHub: `GodMime/WeFolio`，主分支 `main`，工作分支 `dev`。

**多项目仓结构：**

| 目录 | 类型 | 说明 |
|------|------|------|
| `projects/java/wefolio-java-runtime/` | Spring Boot 3.5.3 后端 | REST API 服务，详见该目录下的 `CLAUDE.md` |
| `projects/miniapp/` | 微信小程序 | Skyline 渲染引擎 + glass-easel 组件框架 |
| `projects/ai/` | 预留 | AI 能力模块（当前为空） |
| `docs/` | 文档 | PRD、数据库模型设计、技术台账 |
| `design/` | 设计稿 | 27 页线框原型 + 可视化测试 |

## Build & Run

### Java 后端

```bash
cd projects/java/wefolio-java-runtime

# 先切 JDK 21（系统默认 17）
jdk21

# 编译（跳过测试）
mvn clean package -DskipTests

# 运行测试
mvn test

# 本地运行（需要 .env 中配置数据库和 COS 凭证）
# .env 通过 IDE 或手动 source 注入环境变量
java -jar target/wefolio-java-runtime.jar
```

`.env` 文件位于 `projects/java/wefolio-java-runtime/.env`，包含 MySQL 和腾讯云 COS 凭证。**注意：此文件含敏感信息，切勿提交到 Git。**

### 微信小程序

用微信开发者工具打开 `projects/miniapp/` 目录。AppID: `wxa214c25850cdf268`。

## Mock Experience Rules

- mock 体验版必须完全独立于正式维护者端页面和组件；除登录页的“体验”入口和跳转外，不要改动既有正式页面或正式组件来承载 mock 行为。
- mock 页面统一放在 `projects/miniapp/pages/mock/`，mock 专用组件统一放在 `projects/miniapp/components/mock/`，mock 数据、作品集 JSON 和本地草稿逻辑统一放在 `projects/miniapp/utils/mock-experience.js` 或同级 mock 专用文件。
- mock 页面禁止引入 `projects/miniapp/utils/request.js`、`projects/miniapp/utils/session.js`、上传工具或访客会话工具；禁止调用 `wx.request`、`wx.uploadFile`、`/api/`、维护者 token/session 或任何后端接口。
- mock 的档期、作品、素材库、作品集、编辑、预览、“我的”等数据只使用本地 mock 数据或本地草稿态；新增、保存草稿、发布、档位操作等需要后端身份的动作统一提示 `请去“我的”页面注册登录`。
- mock 页面可以复用不需要改动的展示组件和样式；如果复用会导致修改正式页面或正式组件，则新建 mock 专用组件。
- mock 中用到头像的地方统一使用 `https://cdn2.we-folio.dingchenyong.top/demo/demo-avatar.png`。

### 设计稿部署

```bash
cd design
bash deploy.sh   # 上传 prototype.html 到 marry.dingchenyong.top
```

### Java 后端部署

```bash
cd projects/java/wefolio-java-runtime
bash deploy.sh    # Maven 构建 + SCP 上传 + systemctl 重启
```

目标服务器: `49.235.146.161`，服务名 `wefolio.service`。

## Architecture

```
微信小程序 (Skyline/glass-easel)
        │
        ▼
Spring Boot REST API (:8090)
        │
     ┌──┴──┐
     ▼     ▼
  MySQL  腾讯云 COS
  (8.0)  (对象存储)
```

**后端技术栈：** Spring Boot 3.5.3 / Java 21 / MyBatis-Plus 3.5.7 / Flyway / MySQL 8.0 / 腾讯云 COS

**包结构：** `com.jxc.wefolio` — controller / service / entity / mapper / dict / config / common / exception

详情见 `projects/java/wefolio-java-runtime/CLAUDE.md`，包含完整的 24 张表清单、实体映射、枚举字典规范。

## COS 文件夹结构

用户注册时在 COS 存储桶下按个人唯一码创建目录，用于分类存储文件：

```
{unique_code}/                  ← 用户根目录（例：WFA3B1E7A2）
├── work/
│   ├── image/                  ← 图片类作品
│   └── video/                  ← 视频类作品
├── protfolio/                  ← 作品集额外素材（封面/背景等）
└── others/                     ← 头像、微信二维码等其它素材
```

- 文件夹通过 0 字节空对象（Content-Type: application/x-directory）模拟
- 初始化逻辑：`CosService.initUserStorage(uniqueCode)`，在 `MiniappAuthService.createWechatUser()` 中触发
- 失败不影响注册主流程
- 上传接口：`CosService.upload(MultipartFile, String folderPrefix)` 传入前缀路径

## Database Conventions

- 表前缀 `wf_`，InnoDB，utf8mb4，ROW_FORMAT=DYNAMIC
- 枚举字段：`VARCHAR(32) ascii_bin` + `CHECK` 约束，Java 用 `String` 对接 `XxxDict` 枚举字典
- 金额以分为单位（`amount_fen`），积分为整数
- 全部表统一 `id` / `created_at` / `updated_at` / `deleted` / `version`，由 `BaseEntity` 管理
- 逻辑删除：`deleted` TINYINT（0/1），唯一索引必须包含 `deleted` 列以允许删除后重建
- 乐观锁：`version` INT，MyBatis-Plus `@Version` 自动 +1
- 不使用外键，通过服务层事务 + 唯一索引 + 巡检保证一致性
- 敏感字段（手机号、微信 openid）应用层信封加密，等值查询用 HMAC-SHA256 摘要
- Flyway migration：已提交 Git 的脚本**绝对不可修改**（checksum 校验），所有变更必须追加新 V 版本文件

## Coding Standards

- **所有注释使用中文**（类/字段/方法 Javadoc 及行内注释）
- 类注释格式：`/** 表名 — 中文描述 — 补充说明 */`
- 枚举字典格式：`VALUE("VALUE", "中文显示名称")`
- 具有固定语义的字符串字面量（如前缀、配置键、类型标识等）必须提取为静态常量，禁止硬编码
- 禁止：英文注释、无注释的类/字段/方法

## Design Documentation

| 文档 | 说明 |
|------|------|
| `docs/PRD.md` (v1.3) | 产品需求文档，角色定义、核心流程、业务规则 |
| `docs/database-model-design.md` (v1.4) | 数据库模型设计，24 张表完整 DDL + 索引 + 数据规则 |
| `docs/TECHNICAL_RUNBOOK.md` | 服务器/域名/数据库/缓存/监控等技术配置台账 |
| `design/prototype.html` | 27 页交互线框原型 |
