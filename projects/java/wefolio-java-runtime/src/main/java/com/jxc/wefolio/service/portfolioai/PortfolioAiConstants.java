package com.jxc.wefolio.service.portfolioai;

import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioTextFontFamilyDict;
import com.jxc.wefolio.dict.TeamPortfolioComponentTypeDict;

/** Runtime AI 服务使用的固定 JSON 键、模式与资源标识；编码沿现有普通字典。 */
public final class PortfolioAiConstants {
    /** 常量容器不允许实例化。 */
    private PortfolioAiConstants() { }

    /** 新建模式。 */
    public static final String MODE_CREATE = "CREATE";
    /** 当前菜单定向修改模式。 */
    public static final String MODE_MODIFY_PAGE = "MODIFY_PAGE";
    /** 个人归属类型。 */
    public static final String OWNER_USER = PortfolioOwnerTypeDict.USER.getCode();
    /** 团队归属类型。 */
    public static final String OWNER_TEAM = PortfolioOwnerTypeDict.TEAM.getCode();
    /** 普通个人资料组件类型。 */
    public static final String COMPONENT_PROFILE = PortfolioComponentTypeDict.PROFILE.getCode();
    /** 普通团队资料组件类型。 */
    public static final String COMPONENT_TEAM_PROFILE = TeamPortfolioComponentTypeDict.TEAM_PROFILE.getCode();
    /** 普通单作品组件类型。 */
    public static final String COMPONENT_SINGLE_WORK = PortfolioComponentTypeDict.SINGLE_WORK.getCode();
    /** 普通个人超链接组件类型。 */
    public static final String COMPONENT_HYPERLINK = PortfolioComponentTypeDict.HYPERLINK.getCode();
    /** 普通个人网格作品组件类型。 */
    public static final String COMPONENT_WORK_GRID = PortfolioComponentTypeDict.WORK_GRID.getCode();
    /** 普通个人列表作品组件类型。 */
    public static final String COMPONENT_WORK_LIST = PortfolioComponentTypeDict.WORK_LIST.getCode();
    /** 普通文字网格组件类型。 */
    public static final String COMPONENT_TEXT_GRID = PortfolioComponentTypeDict.TEXT_GRID.getCode();
    /** 普通团队成员作品集网格组件类型。 */
    public static final String COMPONENT_MEMBER_PORTFOLIO_GRID = TeamPortfolioComponentTypeDict.MEMBER_PORTFOLIO_GRID.getCode();
    /** 普通团队成员作品集列表组件类型。 */
    public static final String COMPONENT_MEMBER_PORTFOLIO_LIST = TeamPortfolioComponentTypeDict.MEMBER_PORTFOLIO_LIST.getCode();
    /** 系统字体标识。 */
    public static final String SYSTEM_FONT_ID = PortfolioTextFontFamilyDict.SYSTEM.getCode();
    /** 合法候选结果类型。 */
    public static final String RESULT_CANDIDATE = "CANDIDATE";
    /** 任务存储和响应中的澄清结果类型。 */
    public static final String RESULT_CLARIFICATION = "CLARIFICATION";
    /** 模型信封中的澄清状态。 */
    public static final String OUTPUT_NEEDS_CLARIFICATION = "NEEDS_CLARIFICATION";
    /** 不支持当前冻结契约的错误码。 */
    public static final String ERROR_CONTRACT_UNAVAILABLE = "CONTRACT_UNAVAILABLE";
    /** 腾讯云 TokenHub 模型供应商标识。 */
    public static final String PROVIDER_TOKENHUB = "TOKENHUB";
    /** 冻结的模型思考开启值。 */
    public static final String THINKING_ENABLED = "enabled";
    /** 个人或成员的作品来源类型。 */
    public static final String SOURCE_KIND_WORK = "WORK";
    /** 成员已发布个人作品集整体来源类型。 */
    public static final String SOURCE_KIND_MEMBER_PORTFOLIO = "MEMBER_PORTFOLIO";
    /** 已授权个人作品提供的分享封面图片资源类型。 */
    public static final String RESOURCE_KIND_IMAGE = "IMAGE";
    /** 固定版本字体资源类型。 */
    public static final String RESOURCE_KIND_FONT = "FONT";
    /** 隐藏联系电话资源类型。 */
    public static final String RESOURCE_KIND_CONTACT_PHONE = "CONTACT_PHONE";
    /** 隐藏联系微信资源类型。 */
    public static final String RESOURCE_KIND_CONTACT_WECHAT = "CONTACT_WECHAT";
    /** 已确认二维码图片资源类型。 */
    public static final String RESOURCE_KIND_QR_IMAGE = "QR_IMAGE";
    /** 个人超链接目标作品集资源类型。 */
    public static final String RESOURCE_KIND_PERSONAL_PORTFOLIO = "PERSONAL_PORTFOLIO";
    /** 已确认外部内容资源类型。 */
    public static final String RESOURCE_KIND_EXTERNAL_LINK = "EXTERNAL_LINK";
    /** 修改文字操作。 */
    public static final String OP_UPDATE_TEXT = "UPDATE_TEXT";
    /** 修改普通参数操作。 */
    public static final String OP_UPDATE_PARAMS = "UPDATE_PARAMS";
    /** 绑定受控来源或资源操作。 */
    public static final String OP_BIND_RESOURCES = "BIND_RESOURCES";
    /** 移除组件操作。 */
    public static final String OP_REMOVE_COMPONENT = "REMOVE_COMPONENT";
    /** 移动组件操作。 */
    public static final String OP_MOVE_COMPONENT = "MOVE_COMPONENT";
    /** 插入组件操作。 */
    public static final String OP_INSERT_COMPONENT = "INSERT_COMPONENT";
    /** 修改允许的全局字段操作。 */
    public static final String OP_UPDATE_GLOBAL = "UPDATE_GLOBAL";
    /** 当前页开始的位置值。 */
    public static final String POSITION_START = "START";
    /** 当前页末尾的位置值。 */
    public static final String POSITION_END = "END";
    /** 已有锚点之前的位置值。 */
    public static final String POSITION_BEFORE = "BEFORE";
    /** 已有锚点之后的位置值。 */
    public static final String POSITION_AFTER = "AFTER";
    /** 当前请求人的活动任务字段名。 */
    public static final String KEY_ACTIVE_TASK = "activeTask";
    /** 普通超链接跳转类型字段。 */
    public static final String KEY_ACTION_TYPE = "actionType";
    /** 已授权成员的公开名称字段。 */
    public static final String KEY_MEMBER_NAME = "memberName";
    /** 普通超链接的内部作品集跳转模式。 */
    public static final String ACTION_INTERNAL_PORTFOLIO = "INTERNAL_PORTFOLIO";
    /** 可供新绑定的本人普通已发布作品集句柄前缀。 */
    public static final String TARGET_PORTFOLIO_REF_PREFIX = "target_portfolio_";
    /** 编辑会话来源上下文字段名。 */
    public static final String KEY_AI_CONTEXT = "aiContext";
    /** 客户端声明的 AI 契约版本字段名。 */
    public static final String KEY_AI_CONTRACT_VERSION = "aiContractVersion";
    /** 资源允许绑定的普通槽位字段名。 */
    public static final String KEY_ALLOWED_SLOTS = "allowedSlots";
    /** 移动或插入的已有组件锚点字段名。 */
    public static final String KEY_ANCHOR_COMPONENT_KEY = "anchorComponentKey";
    /** 用户的澄清回答文本字段名。 */
    public static final String KEY_ANSWER_TEXT = "answerText";
    /** 已消耗尝试次数字段名。 */
    public static final String KEY_ATTEMPT_COUNT = "attemptCount";
    /** 当前候选是否可采用字段名。 */
    public static final String KEY_AVAILABLE = "available";
    /** 公开资料头像地址字段名。 */
    public static final String KEY_AVATAR_URL = "avatarUrl";
    /** 普通全局背景音乐配置字段名。 */
    public static final String KEY_BACKGROUND_AUDIO = "backgroundAudio";
    /** 团队文字背景所属成员字段名。 */
    public static final String KEY_BACKGROUND_MEMBER_USER_ID = "backgroundMemberUserId";
    /** 文字背景绑定作品字段名。 */
    public static final String KEY_BACKGROUND_WORK_ID = "backgroundWorkId";
    /** 完整普通配置基准字段名。 */
    public static final String KEY_BASE_CONFIG = "baseConfig";
    /** 任务提交时的数据库草稿修订字段名。 */
    public static final String KEY_BASE_DRAFT_REVISION = "baseDraftRevision";
    /** 冻结模型端点字段名。 */
    public static final String KEY_BASE_URL = "baseUrl";
    /** 受控资源绑定清单字段名。 */
    public static final String KEY_BINDINGS = "bindings";
    /** 个人资料简介字段名。 */
    public static final String KEY_BIO = "bio";
    /** 文字区块稳定身份字段名。 */
    public static final String KEY_BLOCK_KEY = "blockKey";
    /** 文字区块数组字段名。 */
    public static final String KEY_BLOCKS = "blocks";
    /** 普通底部菜单配置字段名。 */
    public static final String KEY_BOTTOM_NAV = "bottomNav";
    /** 来源当前是否可新增绑定字段名。 */
    public static final String KEY_CAN_BIND = "canBind";
    /** 完整可采用候选配置字段名。 */
    public static final String KEY_CANDIDATE = "candidate";
    /** 网格格子稳定身份字段名。 */
    public static final String KEY_CELL_KEY = "cellKey";
    /** 网格格子数组字段名。 */
    public static final String KEY_CELLS = "cells";
    /** 公开城市信息字段名。 */
    public static final String KEY_CITY = "city";
    /** 用户的结构化澄清回答字段名。 */
    public static final String KEY_CLARIFICATION_ANSWER = "clarificationAnswer";
    /** 客户端能力声明字段名。 */
    public static final String KEY_CLIENT_CAPABILITIES = "clientCapabilities";
    /** 普通颜色参数字段名。 */
    public static final String KEY_COLOR = "color";
    /** 普通网格列宽权重字段名。 */
    public static final String KEY_COLUMN_WEIGHTS = "columnWeights";
    /** 普通网格列数字段名。 */
    public static final String KEY_COLUMNS = "columns";
    /** 插入操作的组件提案字段名。 */
    public static final String KEY_COMPONENT = "component";
    /** 普通组件稳定身份字段名。 */
    public static final String KEY_COMPONENT_KEY = "componentKey";
    /** 普通组件类型字段名。 */
    public static final String KEY_COMPONENT_TYPE = "componentType";
    /** 普通正文组件数组字段名。 */
    public static final String KEY_COMPONENTS = "components";
    /** 普通组件参数对象字段名。 */
    public static final String KEY_CONFIG = "config";
    /** 普通联系手机槽位字段名。 */
    public static final String KEY_CONTACT_PHONE = "contactPhone";
    /** 普通联系微信槽位字段名。 */
    public static final String KEY_CONTACT_WECHAT = "contactWechat";
    /** 服务端冻结的协议版本字段名。 */
    public static final String KEY_CONTRACT_VERSION = "contractVersion";
    /** 服务端生成并提供模型读取的授权作品封面地址字段名。 */
    public static final String KEY_COVER_URL = "coverUrl";
    /** 公开名称或字体名称字段名。 */
    public static final String KEY_DISPLAY_NAME = "displayName";
    /** 普通编辑器能力修订字段名。 */
    public static final String KEY_EDITOR_SCHEMA_REVISION = "editorSchemaRevision";
    /** 运行或普通组件启用标志字段名。 */
    public static final String KEY_ENABLED = "enabled";
    /** 安全任务错误码字段名。 */
    public static final String KEY_ERROR_CODE = "errorCode";
    /** 安全任务错误提示字段名。 */
    public static final String KEY_ERROR_MESSAGE = "errorMessage";
    /** 可供页面展示的新增受控错误详情。 */
    public static final String KEY_ERROR = "error";
    /** 错误详情中的固定分类字段。 */
    public static final String KEY_CODE = "code";
    /** 错误详情中的安全中文说明字段。 */
    public static final String KEY_MESSAGE = "message";
    /** 显式字段在普通规范化中被丢弃。 */
    public static final String ERROR_NORMALIZATION_FIELD_DROPPED = "NORMALIZATION_FIELD_DROPPED";
    /** 显式字段在普通规范化中改变类型。 */
    public static final String ERROR_NORMALIZATION_TYPE_CHANGED = "NORMALIZATION_TYPE_CHANGED";
    /** 显式字段在普通规范化中改变值。 */
    public static final String ERROR_NORMALIZATION_VALUE_CHANGED = "NORMALIZATION_VALUE_CHANGED";
    /** 显式数组在普通规范化中改变元素数量。 */
    public static final String ERROR_NORMALIZATION_ARRAY_CHANGED = "NORMALIZATION_ARRAY_CHANGED";
    /** 普通超链接外部内容槽位字段名。 */
    public static final String KEY_EXTERNAL_CONTENT = "externalContent";
    /** 服务端冻结的授权事实字段名。 */
    public static final String KEY_FACTS = "facts";
    /** 文字操作指定字段字段名。 */
    public static final String KEY_FIELD = "field";
    /** 普通字体选择槽位字段名。 */
    public static final String KEY_FONT_ID = "fontId";
    /** 普通字体固定版本字段名。 */
    public static final String KEY_FONT_VERSION = "fontVersion";
    /** 普通配置的字体版本映射字段名。 */
    public static final String KEY_FONTS = "fonts";
    /** 普通作品分组稳定身份字段名。 */
    public static final String KEY_GROUP_KEY = "groupKey";
    /** 普通作品分组数组字段名。 */
    public static final String KEY_GROUPS = "groups";
    /** 候选实际差异标志字段名。 */
    public static final String KEY_HAS_CHANGES = "hasChanges";
    /** 授权作品的媒体高度字段名。 */
    public static final String KEY_HEIGHT = "height";
    /** 请求人作用域中的提交幂等键字段名。 */
    public static final String KEY_IDEMPOTENCY_KEY = "idempotencyKey";
    /** 规范化业务输入快照字段名。 */
    public static final String KEY_INPUT = "input";
    /** 团队资料简介字段名。 */
    public static final String KEY_INTRO = "intro";
    /** 普通列表或菜单条目数组字段名。 */
    public static final String KEY_ITEMS = "items";
    /** 来源、资源或结果类型字段名。 */
    public static final String KEY_KIND = "kind";
    /** 资源公开显示名称字段名。 */
    public static final String KEY_LABEL = "label";
    /** 统一协议能力预算字段名。 */
    public static final String KEY_LIMITS = "limits";
    /** 任务提交时的本地编辑版本字段名。 */
    public static final String KEY_LOCAL_EDIT_REVISION = "localEditRevision";
    /** 完整普通配置组件数量上限字段名。 */
    public static final String KEY_MAX_COMPONENTS = "maxComponents";
    /** 普通完整配置字节预算字段名。 */
    public static final String KEY_MAX_CONFIG_BYTES = "maxConfigBytes";
    /** 编辑来源上下文字节预算字段名。 */
    public static final String KEY_MAX_CONTEXT_BYTES = "maxContextBytes";
    /** 严格 JSON 最大深度字段名。 */
    public static final String KEY_MAX_JSON_DEPTH = "maxJsonDepth";
    /** 用户描述 Unicode 码点上限字段名。 */
    public static final String KEY_MAX_PROMPT_CODE_POINTS = "maxPromptCodePoints";
    /** 活动来源总数上限字段名。 */
    public static final String KEY_MAX_SOURCE_COUNT = "maxSourceCount";
    /** 冻结的模型输出 token 上限字段名。 */
    public static final String KEY_MAX_TOKENS = "maxTokens";
    /** 冻结的模型思考深度字段名。 */
    public static final String KEY_REASONING_EFFORT = "reasoningEffort";
    /** 授权作品的媒体类型字段名。 */
    public static final String KEY_MEDIA_TYPE = "mediaType";
    /** 服务端生成并提供模型读取的授权作品媒体地址字段名。 */
    public static final String KEY_MEDIA_URL = "mediaUrl";
    /** 来源实际所属成员字段名。 */
    public static final String KEY_MEMBER_USER_ID = "memberUserId";
    /** 生成或修改模式字段名。 */
    public static final String KEY_MODE = "mode";
    /** 冻结的模型名称字段名。 */
    public static final String KEY_MODEL = "model";
    /** 冻结的供应商调用参数字段名。 */
    public static final String KEY_MODEL_CONFIG = "modelConfig";
    /** 服务端支持的模式清单字段名。 */
    public static final String KEY_MODES = "modes";
    /** 普通作品组名称或编辑器组件名称元数据字段名。 */
    public static final String KEY_NAME = "name";
    /** 已有隐藏资源的内部节点原位置字段名。 */
    public static final String KEY_NODE_KEY = "nodeKey";
    /** 受限语义操作类型字段名。 */
    public static final String KEY_OP = "op";
    /** 受限语义操作序列字段名。 */
    public static final String KEY_OPERATIONS = "operations";
    /** 澄清问题选项身份字段名。 */
    public static final String KEY_OPTION_ID = "optionId";
    /** 已严格校验的模型信封字段名。 */
    public static final String KEY_OUTPUT = "output";
    /** 真实作品集归属身份字段名。 */
    public static final String KEY_OWNER_ID = "ownerId";
    /** 真实作品集归属类型字段名。 */
    public static final String KEY_OWNER_TYPE = "ownerType";
    /** 当前菜单的生成提案字段名。 */
    public static final String KEY_PAGE = "page";
    /** 已开放普通参数集合字段名。 */
    public static final String KEY_PARAMS = "params";
    /** 受限操作负载字段名。 */
    public static final String KEY_PAYLOAD = "payload";
    /** 建议任务轮询间隔字段名。 */
    public static final String KEY_POLL_AFTER_MS = "pollAfterMs";
    /** 真实作品集编号字段名。 */
    public static final String KEY_PORTFOLIO_ID = "portfolioId";
    /** 普通远程字体客户端能力字段名。 */
    public static final String KEY_PORTFOLIO_REMOTE_FONT = "portfolioRemoteFont";
    /** 移动或插入位置字段名。 */
    public static final String KEY_POSITION = "position";
    /** 公开职业信息字段名。 */
    public static final String KEY_PROFESSION = "profession";
    /** 普通个人资料快照字段名。 */
    public static final String KEY_PROFILE = "profile";
    /** 用户原始意图描述字段名。 */
    public static final String KEY_PROMPT = "prompt";
    /** 服务端冻结的提示词版本字段名。 */
    public static final String KEY_PROMPT_VERSION = "promptVersion";
    /** 冻结的模型供应商字段名。 */
    public static final String KEY_PROVIDER = "provider";
    /** 二维码组件图片槽位字段名。 */
    public static final String KEY_QR_URL = "qrUrl";
    /** 澄清问题稳定身份字段名。 */
    public static final String KEY_QUESTION_ID = "questionId";
    /** 澄清问题清单字段名。 */
    public static final String KEY_QUESTIONS = "questions";
    /** 操作或未使用素材说明字段名。 */
    public static final String KEY_REASON = "reason";
    /** 服务端资源句柄字段名。 */
    public static final String KEY_REF = "ref";
    /** 字体固定版本引用字段名。 */
    public static final String KEY_REFERENCE = "reference";
    /** 普通只读候选渲染结果字段名。 */
    public static final String KEY_RENDER_DATA = "renderData";
    /** 安全任务结果对象字段名。 */
    public static final String KEY_RESULT = "result";
    /** 已有资源原位复用限制字段名。 */
    public static final String KEY_REUSE_ONLY = "reuseOnly";
    /** 普通网格各行最小高度字段名。 */
    public static final String KEY_ROW_MIN_HEIGHTS_RPX = "rowMinHeightsRpx";
    /** 普通网格行数字段名。 */
    public static final String KEY_ROWS = "rows";
    /** 网格文字片段稳定身份字段名。 */
    public static final String KEY_RUN_KEY = "runKey";
    /** 网格文字片段数组字段名。 */
    public static final String KEY_RUNS = "runs";
    /** 仅供模型读取的脱敏澄清回答字段名。 */
    public static final String KEY_SAFE_CLARIFICATION_ANSWER = "safeClarificationAnswer";
    /** 仅供模型读取的脱敏意图描述字段名。 */
    public static final String KEY_SAFE_PROMPT = "safePrompt";
    /** 普通作品集 schema 版本字段名。 */
    public static final String KEY_SCHEMA_VERSION = "schemaVersion";
    /** 允许修改的全局配置组字段名。 */
    public static final String KEY_SECTION = "section";
    /** 新建模式的全局设置提案字段名。 */
    public static final String KEY_SETTINGS = "settings";
    /** 普通分享配置字段名。 */
    public static final String KEY_SHARE = "share";
    /** 受控绑定的普通槽位字段名。 */
    public static final String KEY_SLOT = "slot";
    /** 普通组件或作品组派生顺序字段名。 */
    public static final String KEY_SORT_ORDER = "sortOrder";
    /** 冻结来源的真实身份字段名。 */
    public static final String KEY_SOURCE_ID = "sourceId";
    /** 编辑会话中的稳定素材号字段名。 */
    public static final String KEY_SOURCE_NO = "sourceNo";
    /** 主内容绑定的有序素材号字段名。 */
    public static final String KEY_SOURCE_NOS = "sourceNos";
    /** 编辑来源或冻结授权来源清单字段名。 */
    public static final String KEY_SOURCES = "sources";
    /** 任务或模型信封状态字段名。 */
    public static final String KEY_STATUS = "status";
    /** 冻结的供应商流式开关字段名。 */
    public static final String KEY_STREAM = "stream";
    /** 普通页面样式字段名。 */
    public static final String KEY_STYLE = "style";
    /** 服务端重算的候选说明字段名。 */
    public static final String KEY_SUMMARY = "summary";
    /** 授权作品所属用户的当前标签字段名。 */
    public static final String KEY_TAGS = "tags";
    /** 受限操作目标字段名。 */
    public static final String KEY_TARGET = "target";
    /** 本次指定的真实菜单身份字段名。 */
    public static final String KEY_TARGET_MENU_KEY = "targetMenuKey";
    /** 普通个人超链接目标作品集字段名。 */
    public static final String KEY_TARGET_PORTFOLIO_ID = "targetPortfolioId";
    /** 请求人可访问的任务编号字段名。 */
    public static final String KEY_TASK_ID = "taskId";
    /** 普通团队资料快照字段名。 */
    public static final String KEY_TEAM = "team";
    /** 真实团队编号字段名。 */
    public static final String KEY_TEAM_ID = "teamId";
    /** 公开团队名称字段名。 */
    public static final String KEY_TEAM_NAME = "teamName";
    /** 冻结的模型采样温度字段名。 */
    public static final String KEY_TEMPERATURE = "temperature";
    /** 冻结的供应商思考配置字段名。 */
    public static final String KEY_THINKING = "thinking";
    /** 普通标题字段字段名。 */
    public static final String KEY_TITLE = "title";
    /** 提案、结构或范围类型字段名。 */
    public static final String KEY_TYPE = "type";
    /** 安全不可用原因字段名。 */
    public static final String KEY_UNAVAILABLE_REASON = "unavailableReason";
    /** 未使用的来源清单字段名。 */
    public static final String KEY_UNUSED_SOURCES = "unusedSources";
    /** 实际使用的素材号字段名。 */
    public static final String KEY_USED_SOURCE_NOS = "usedSourceNos";
    /** 文字操作值或隐藏资源原值字段名。 */
    public static final String KEY_VALUE = "value";
    /** 个人资料的微信二维码槽位字段名。 */
    public static final String KEY_WECHAT_QR_URL = "wechatQrUrl";
    /** 授权作品的媒体宽度字段名。 */
    public static final String KEY_WIDTH = "width";
    /** 普通单作品引用字段名。 */
    public static final String KEY_WORK_ID = "workId";
    /** 普通有序作品引用数组字段名。 */
    public static final String KEY_WORK_IDS = "workIds";
    /** 固定 AI 协议版本。 */
    public static final String CONTRACT_VERSION = AiPortfolioProtocol.CONTRACT_VERSION;
    /** 服务端新建文字内部节点的稳定键前缀。 */
    public static final String AI_NODE_KEY_PREFIX = "ai_";
    /** 服务端新建 AI 组件的稳定键前缀。 */
    public static final String AI_COMPONENT_KEY_PREFIX = "c_ai_";
    /** 新建会话初始资料组件的稳定键。 */
    public static final String INITIAL_PROFILE_COMPONENT_KEY = "c_profile";
    /** 可信字体目录句柄前缀。 */
    public static final String FONT_REF_PREFIX = "font_";
    /** 已授权个人作品分享封面句柄前缀，后缀采用素材号。 */
    public static final String SOURCE_COVER_REF_PREFIX = "source_cover_";
    /** 已有隐藏资源原位句柄前缀。 */
    public static final String EXISTING_REF_PREFIX = "existing_";
    /** 用户明确提供资源的隐藏句柄前缀。 */
    public static final String INPUT_REF_PREFIX = "input_";
    /** 已验证公开资料基础句柄。 */
    public static final String PROFILE_BASE_REF = "profile_base";
    /** 当前本人公开头像的分享头像隐藏句柄。 */
    public static final String PROFILE_SHARE_AVATAR_REF = "profile_share_avatar";
    /** 本人公开分享头像句柄的模型展示名称。 */
    public static final String PROFILE_SHARE_AVATAR_LABEL = "本人头像（分享头像）";
    /** 当前公开资料联系手机的隐藏事实句柄。 */
    public static final String PROFILE_CONTACT_PHONE_REF = "profile_contact_phone";
    /** 当前公开资料联系微信的隐藏事实句柄。 */
    public static final String PROFILE_CONTACT_WECHAT_REF = "profile_contact_wechat";
    /** 普通身份字段的名称后缀。 */
    public static final String ID_FIELD_SUFFIX = "Id";
    /** 普通正文组件对象路径后缀。 */
    public static final String COMPONENT_ITEM_PATH_SUFFIX = "." + KEY_COMPONENTS + "[]";
    /** 顶层普通字体映射路径。 */
    public static final String FONTS_PATH = "." + KEY_FONTS;
    /** 普通标题字段路径后缀。 */
    public static final String TITLE_PATH_SUFFIX = "." + KEY_TITLE;
    /** 实际作品来源使用统计的命名空间前缀。 */
    public static final String WORK_REFERENCE_PREFIX = SOURCE_KIND_WORK + ":";
    /** 实际成员作品集使用统计的命名空间前缀。 */
    public static final String MEMBER_PORTFOLIO_REFERENCE_PREFIX = SOURCE_KIND_MEMBER_PORTFOLIO + ":";

    /** 提交幂等键的严格格式。 */
    public static final String IDEMPOTENCY_KEY_PATTERN = "[A-Za-z0-9_-]{1,64}";
    /** 服务端生成稳定节点键的分段符。 */
    public static final String STABLE_KEY_SEPARATOR = "_";
    /** 来源类型与真实身份的命名空间分隔符。 */
    public static final String REFERENCE_SEPARATOR = ":";
    /** 新建会话的普通默认分享标题。 */
    public static final String DEFAULT_PORTFOLIO_TITLE = "未命名作品集";
    /** 实际新增组件说明的开头。 */
    public static final String SUMMARY_INSERT_PREFIX = "新增 ";
    /** 实际移除组件说明的开头。 */
    public static final String SUMMARY_REMOVE_PREFIX = "移除 ";
    /** 实际内容或样式修改说明的开头。 */
    public static final String SUMMARY_MODIFY_PREFIX = "修改 ";
    /** 实际位置调整说明的开头。 */
    public static final String SUMMARY_MOVE_PREFIX = "调整 ";
    /** 实际原样保留说明的开头。 */
    public static final String SUMMARY_RETAIN_PREFIX = "保留 ";
    /** 新增或移除组件数量说明的结尾。 */
    public static final String SUMMARY_COMPONENT_COUNT_SUFFIX = " 个组件";
    /** 位置调整数量说明的结尾。 */
    public static final String SUMMARY_MOVE_SUFFIX = " 个组件的位置";
    /** 原样保留数量说明的结尾。 */
    public static final String SUMMARY_RETAIN_SUFFIX = " 个组件的内容、样式和位置";
    /** 内容或样式修改数量说明的结尾。 */
    public static final String SUMMARY_MODIFY_SUFFIX = " 个组件的内容或样式";
    /** 实际全局参数调整说明的开头。 */
    public static final String SUMMARY_GLOBAL_PREFIX = "更新";
    /** 实际分享参数调整的显示名称。 */
    public static final String SUMMARY_SHARE_LABEL = "分享信息";
    /** 实际全局样式调整的显示名称。 */
    public static final String SUMMARY_STYLE_LABEL = "页面样式";
    /** 实际背景音乐调整的显示名称。 */
    public static final String SUMMARY_AUDIO_LABEL = "背景音乐";
    /** 实际使用素材编号说明的开头。 */
    public static final String SUMMARY_USED_SOURCES_PREFIX = "已使用素材号：";
    /** 未使用素材编号说明的开头。 */
    public static final String SUMMARY_UNUSED_SOURCE_PREFIX = "未使用素材 ";
    /** 未使用素材编号与说明的分隔符。 */
    public static final String SUMMARY_REASON_SEPARATOR = "：";
    /** 模型输入中的外链替代说明。 */
    public static final String REDACTED_LINK = "[链接请在参数面板填写]";
    /** 模型输入中的联系电话替代说明。 */
    public static final String REDACTED_PHONE = "[联系方式请在参数面板填写]";
    /** 模型输入中的联系微信替代说明。 */
    public static final String REDACTED_WECHAT = "[已验证微信句柄或请在参数面板填写]";
    /** 无歧义中国手机号码的匹配格式。 */
    public static final String PHONE_NUMBER_PATTERN = "(?<![0-9])1[3-9][0-9]{9}(?![0-9])";
    /** 明确微信原文的精确捕获格式。 */
    public static final String EXPLICIT_WECHAT_PATTERN = "(?i)(?:微信(?:号)?|wechat|wx)\\s*[:：]?\\s*([a-z][a-z0-9_-]{5,19})(?![a-z0-9_-])";
    /** 明确 HTTP 外链原文的精确匹配格式。 */
    public static final String EXPLICIT_URL_PATTERN = "https?://[^\\s，。；、<>\\\"]+";
    /** 发送侧所有直接 HTTP 地址的去敏格式。 */
    public static final String REDACT_URL_PATTERN = "(?i)https?://[^\\s，。；]+";
    /** 发送侧明确微信文字的去敏格式。 */
    public static final String REDACT_WECHAT_PATTERN = "(?i)(?:微信(?:号)?|wechat|wx)\\s*[:：]?\\s*[a-z0-9_-]{5,}";
}
