/** 微信朋友圈单页模式场景值 */
const WECHAT_TIMELINE_SINGLE_PAGE_SCENE = 1154

/** 匿名会话标识前缀 */
const ANONYMOUS_SESSION_PREFIX = 'timeline-'

/** 匿名会话标识随机段数量 */
const ANONYMOUS_RANDOM_SEGMENT_COUNT = 5

/**
 * 获取微信运行时 API，优先使用注入对象，未注入时回退到全局 wx。
 *
 * @param {object} [wxApi] 可注入的微信 API 对象
 * @returns {object|null} 微信运行时 API，不可用时返回 null
 */
function getRuntimeWx(wxApi) {
  if (wxApi) return wxApi
  if (typeof wx !== 'undefined') return wx
  return null
}

/**
 * 判断当前是否从微信朋友圈单页模式打开。
 * scene=1154 时微信限制 wx.login，需要改走匿名访客协议。
 *
 * @param {object} [wxApi] 可注入的微信 API 对象，用于测试
 * @returns {boolean} 是否为朋友圈单页模式
 */
function isWechatTimelineSinglePage(wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  if (!runtimeWx || typeof runtimeWx.getEnterOptionsSync !== 'function') return false
  try {
    const options = runtimeWx.getEnterOptionsSync() || {}
    return Number(options.scene) === WECHAT_TIMELINE_SINGLE_PAGE_SCENE
  } catch (error) {
    // 读取进入场景失败时保持普通登录流程，避免误把其他入口降级为匿名访问。
    return false
  }
}

/**
 * 生成页面级匿名会话标识，格式为 timeline-{时间戳36进制}-{5段随机字符}。
 * 该值只标记一次朋友圈页面会话，不直接作为服务端访客身份或授权依据。
 *
 * @returns {string} 长度不超过 128 字符的匿名会话标识
 */
function createAnonymousSessionId() {
  const randomSegments = []
  for (let index = 0; index < ANONYMOUS_RANDOM_SEGMENT_COUNT; index += 1) {
    randomSegments.push(Math.random().toString(36).slice(2, 13).padEnd(11, '0'))
  }
  return `${ANONYMOUS_SESSION_PREFIX}${Date.now().toString(36)}-${randomSegments.join('-')}`
}

module.exports = {
  WECHAT_TIMELINE_SINGLE_PAGE_SCENE,
  createAnonymousSessionId,
  isWechatTimelineSinglePage
}
