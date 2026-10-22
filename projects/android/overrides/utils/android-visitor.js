const { request } = require('./request')
const { getToken } = require('./session')

const VISITOR_OPEN_ROUTE = /^\/api\/visitor\/(portfolios|team-portfolios|advanced-team-portfolios)\/[^/]+\/open$/
const ANONYMOUS_STORAGE_KEY = 'wefolio_android_anonymous_session'
const ANONYMOUS_PREFIX = 'timeline-'

/** 原后端匿名浏览协议保留其格式前缀，访客浏览无需微信授权或维护者登录。 */
function anonymousSessionId(wxApi) {
  let id = ''
  try { id = wxApi.getStorageSync(ANONYMOUS_STORAGE_KEY) } catch (error) { /* 允许无持久存储浏览。 */ }
  if (typeof id === 'string' && /^timeline-[a-z0-9-]{32,119}$/.test(id)) return id
  id = ANONYMOUS_PREFIX + Array.from({ length: 4 }, () => Math.random().toString(36).slice(2).padEnd(12, '0')).join('')
  try { wxApi.setStorageSync(ANONYMOUS_STORAGE_KEY, id) } catch (error) { /* 会话内继续使用本次值。 */ }
  return id
}

/** 已登录用户由新增路由读取维护者身份，匿名用户沿用原匿名浏览协议。 */
async function createVisitorIdentity(wxApi = wx) {
  return getToken(wxApi) ? {} : { anonymousSessionId: anonymousSessionId(wxApi) }
}

/** 只改写已登录用户的打开请求；旧接口不接受任何安卓专用凭证。 */
function openVisitorRequest(requestFn = request, options) {
  if (!VISITOR_OPEN_ROUTE.test(options.url)) throw new Error('访客打开接口无效')
  if (options.data && options.data.anonymousSessionId) return requestFn(options)
  return requestFn({ ...options, url: options.url.replace('/api/visitor/', '/api/mobile/visitor/'), authMode: 'maintainer' })
}

module.exports = { anonymousSessionId, createVisitorIdentity, openVisitorRequest }
