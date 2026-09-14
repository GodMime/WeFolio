const {
  VISITOR_TOKEN_STORAGE_KEY,
  VISITOR_TOKEN_EXPIRES_AT_STORAGE_KEY,
  request
} = require('../../../utils/request.js')
const { visitActivityLifecycle } = require('../../../utils/visit-activity-lifecycle.js')

const TEAM_VISITOR_PREFIX = '/api/visitor/team-portfolios'
const SOURCE_TYPE_WECHAT_SHARE_CARD = 'WECHAT_SHARE_CARD'
const IDEMPOTENCY_KEY_PREFIX = 'team-open-'
const IDEMPOTENCY_KEY_MAX_LENGTH = 64
let idempotencySequence = 0

function text(value) {
  return String(value || '').trim()
}

function createIdempotencyKey() {
  idempotencySequence += 1
  return `${IDEMPOTENCY_KEY_PREFIX}${Date.now().toString(36)}-${idempotencySequence.toString(36)}`
}

function resolveOpenIdempotencyKey(options) {
  if (options.idempotencyKey === undefined || options.idempotencyKey === null) return createIdempotencyKey()
  const normalized = text(options.idempotencyKey)
  if (!normalized || normalized.length > IDEMPOTENCY_KEY_MAX_LENGTH) throw new Error('打开作品集幂等键必须为1至64个字符')
  return normalized
}

function resolveTeamShareCode(options = {}) {
  if (text(options.shareCode)) return text(options.shareCode)
  let scene = text(options.scene)
  if (!scene) return ''
  try { scene = decodeURIComponent(scene) } catch (error) { return '' }
  const match = scene.match(/(?:^|&)shareCode=([^&]+)/)
  return text(match ? match[1] : scene)
}

function wxLogin(wxApi) {
  const runtimeWx = wxApi || (typeof wx !== 'undefined' ? wx : null)
  if (!runtimeWx || !runtimeWx.login) return Promise.reject(new Error('微信登录环境不可用'))
  return new Promise((resolve, reject) => {
    runtimeWx.login({
      success(response) { response && response.code ? resolve(response.code) : reject(new Error('微信登录失败')) },
      fail(error) { reject(new Error(error && error.errMsg ? error.errMsg : '微信登录失败')) }
    })
  })
}

function saveTeamVisitorToken(session = {}, wxApi) {
  const runtimeWx = wxApi || (typeof wx !== 'undefined' ? wx : null)
  if (!runtimeWx || !runtimeWx.setStorageSync || !session.token) return
  try {
    runtimeWx.setStorageSync(VISITOR_TOKEN_STORAGE_KEY, session.token)
    const expiresIn = Math.max(0, Number(session.expiresInSeconds) || 0)
    runtimeWx.setStorageSync(VISITOR_TOKEN_EXPIRES_AT_STORAGE_KEY, expiresIn ? Date.now() + expiresIn * 1000 : '')
  } catch (error) {
    // 朋友圈单页模式本地存储不可用时不阻断作品集打开。
  }
}

async function openTeamVisitorSession(options = {}) {
  const context = options.browserContext || null
  if (context && context.isInvalid()) throw Object.assign(new Error('访问上下文已失效'), { statusCode: 403 })
  if (context && context.isDisposed && context.isDisposed()) throw Object.assign(new Error('访问上下文已关闭'), { contextDisposed: true })
  if (context) options = Object.assign({}, options, context.openOptions())
  const shareCode = text(options.shareCode)
  if (!shareCode) throw new Error('分享码无效')
  const idempotencyKey = resolveOpenIdempotencyKey(options)
  const anonymousSessionId = text(options.anonymousSessionId)
  const identityData = anonymousSessionId
    ? { anonymousSessionId }
    : { loginCode: await wxLogin(options.wxApi) }
  const session = await (options.requestFn || request)({
    url: `${TEAM_VISITOR_PREFIX}/${encodeURIComponent(shareCode)}/open`,
    method: 'POST',
    authMode: 'none',
    data: Object.assign({}, identityData, {
      sourceType: text(options.sourceType) || SOURCE_TYPE_WECHAT_SHARE_CARD,
      idempotencyKey
    }, options.tracking ? { tracking: options.tracking } : {})
  })
  if (context) {
    if (context.isDisposed && context.isDisposed()) throw Object.assign(new Error('访问上下文已关闭'), { contextDisposed: true })
    if (context.isInvalid()) throw Object.assign(new Error('访问上下文已失效'), { statusCode: 403 })
    context.acceptSession(session)
    if (context.isInvalid()) throw Object.assign(new Error('访客身份已变化'), { statusCode: 403 })
    context.sessionGeneration = (context.sessionGeneration || 0) + 1
  }
  if (options.persistToken !== false) saveTeamVisitorToken(session, options.wxApi)
  return session
}

async function requestWithTeamVisitorSessionRefresh(options = {}) {
  const requestFn = options.requestFn || request
  const requestOptions = Object.assign({}, options.requestOptions, { authMode: 'visitor' })
  if (!text(requestOptions.url).startsWith(`${TEAM_VISITOR_PREFIX}/`)) throw new Error('只允许团队访客接口')
  const context = options.browserContext || visitActivityLifecycle.findContext('TEAM', options.shareCode)
  const beforeGeneration = context && context.sessionGeneration || 0
  if (context && context.isInvalid()) throw Object.assign(new Error('访问上下文已失效'), { statusCode: 403 })
  try {
    return await requestFn(requestOptions)
  } catch (error) {
    if (!error || !error.authRequired) throw error
    let session
    if (context) {
      if (context.isInvalid()) throw error
      if ((context.sessionGeneration || 0) !== beforeGeneration) session = context.getSession()
      else {
        if (!context.refreshPromise) {
          context.beginRecovery()
          context.refreshPromise = openTeamVisitorSession(Object.assign({}, options, { browserContext: context }))
            .then(async (response) => { if (context.onRefresh) await context.onRefresh(response); return response })
            .catch((failure) => { context.recoveryFailed(failure); throw failure })
            .finally(() => { context.refreshPromise = null })
        }
        session = await context.refreshPromise
      }
    } else session = await openTeamVisitorSession(options)
    if (typeof options.onRefresh === 'function') await options.onRefresh(session)
    const refreshedOptions = typeof options.refreshRequestOptions === 'function'
      ? options.refreshRequestOptions(session, requestOptions)
      : Object.assign({}, requestOptions, requestOptions.data ? { data: Object.assign({}, requestOptions.data,
          Object.prototype.hasOwnProperty.call(requestOptions.data, 'visitorKey') ? { visitorKey: session.visitorKey || '' } : {},
          Object.prototype.hasOwnProperty.call(requestOptions.data, 'visitorProfileToken') ? { visitorProfileToken: session.visitorProfileToken || '' } : {}) } : {})
    return requestFn(Object.assign({}, refreshedOptions, { authMode: 'visitor' }))
  }
}

module.exports = {
  SOURCE_TYPE_WECHAT_SHARE_CARD,
  TEAM_VISITOR_PREFIX,
  createIdempotencyKey,
  openTeamVisitorSession,
  requestWithTeamVisitorSessionRefresh,
  resolveTeamShareCode,
  saveTeamVisitorToken,
  wxLogin
}
