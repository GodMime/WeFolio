# 团队作品集报错文案集中化设计

## 背景

团队作品集功能当前在 `service/teamportfolio` 目录下的 Service、Validator、Renderer 和 ReferenceExtractor 中声明了 135 个报错文案静态常量，分布于 32 个类。该结构违反 `projects/java/wefolio-java-runtime/CLAUDE.md` 的约定：业务代码中的报错文案静态常量必须放入 `com.jxc.wefolio.message` 包下的对应 Message 类，并通过 Message 类名限定引用。

现有 `TeamPortfolioMessage` 已提供 5 个团队作品集基础报错文案。本次重构在该类上继续扩充，不拆分新的 Message 类。

## 目标

- 将 `service/teamportfolio` 及其子包内的全部报错文案静态常量迁入 `TeamPortfolioMessage`。
- 所有调用点统一通过 `TeamPortfolioMessage.CONSTANT` 引用，禁止静态导入。
- 保持现有报错文字、异常类型、抛出时机和业务行为不变。
- 增加自动化结构测试，防止后续再次在团队作品集业务类中散落报错文案。

## 非目标

- 不修改团队作品集接口、业务流程、数据库结构或小程序行为。
- 不改写现有中文报错文案，不统一不同上下文中的产品措辞。
- 不顺带迁移 `service/teamportfolio` 目录之外的其他业务域报错文案。
- 不拆分 `TeamPortfolioMessage`，也不引入消息枚举、错误码或国际化框架。

## 常量组织与命名

`TeamPortfolioMessage` 保持现有不可实例化的 `final` 常量类结构。已有 5 个公开常量名称保持不变，避免破坏现有引用和基础测试。

迁入的常量采用扁平结构，名称格式为：

```text
<业务子域>_<具体语义>_MESSAGE
<业务子域>_<具体语义>_TEMPLATE
```

业务子域使用能够定位场景的前缀，例如：

- `PORTFOLIO_CONFIG_`：团队作品集配置校验和渲染。
- `PORTFOLIO_VISIT_`：团队作品集访问记录与访问事件。
- `PORTFOLIO_REFERENCE_`：引用提取、校验和引用保护。
- `PORTFOLIO_ASSET_`：团队作品集素材上传与确认。
- `CAROUSEL_`、`CONTACT_FORM_`、`SCHEDULE_QUERY_`：具体组件。
- `MEMBER_PORTFOLIO_GRID_`、`MEMBER_PORTFOLIO_LIST_`：成员作品集展示组件。

每个常量保留有意义的中文 Javadoc。业务类只导入 `TeamPortfolioMessage` 类本身，不使用 `import static`。

## 文案复用原则

当 Validator 与 Renderer 对同一业务子域、同一失败语义使用完全相同的报错文字时，共用一个 `TeamPortfolioMessage` 常量。例如轮播图 Validator 与 Renderer 的“配置不正确”属于同一失败语义。

仅文字相同但业务语义不同的错误不强行合并，分别使用带业务前缀的常量。这样可以避免未来某个场景调整文案时意外改变其他场景。

迁移过程中逐项保留字符串原值，包括空格、标点和数字格式，不进行顺手润色。

## 修改范围

生产代码修改限定为：

- `src/main/java/com/jxc/wefolio/message/TeamPortfolioMessage.java`
- `src/main/java/com/jxc/wefolio/service/teamportfolio/**/*.java`

测试代码新增或修改限定为：

- `src/test/java/com/jxc/wefolio/service/teamportfolio/TeamPortfolioMessageUsageTest.java`
- 因常量公开名称调整而必须更新的团队作品集现有测试。

不得覆盖或回滚工作区内与本次重构无关的未提交改动。

## 测试策略

本次属于无行为变化的结构重构，采用以下验证层次：

1. 先新增结构测试并运行，确认它能够检出当前散落在 32 个类中的报错常量。
2. 结构测试递归扫描 `service/teamportfolio`，断言：
   - 不存在名称以 `MESSAGE` 或 `TEMPLATE` 表示报错文案的本地 `static final String` 声明；
   - 不存在 `new BusinessException("...")` 形式的异常文案字面量；
   - 不存在对 `com.jxc.wefolio.message` 的静态导入。
3. 完成迁移后重新运行结构测试，确认约束通过。
4. 运行团队作品集相关 Maven 测试，确认异常消息和业务行为保持不变。
5. 使用 JDK 21 运行 runtime 全量 `mvn test`，确认没有编译错误或跨模块回归。

## 完成标准

- `service/teamportfolio` 及其子包内不再声明报错文案静态常量。
- 原有 135 个散落常量的所有调用点均改为 `TeamPortfolioMessage` 限定引用；同义重复常量可按复用原则合并。
- 所有报错文字与重构前逐项一致。
- 没有新增 Message 静态导入或异常文案字面量。
- 结构测试、团队作品集相关测试和 runtime 全量 Maven 测试全部通过。
