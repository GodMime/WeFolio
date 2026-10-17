const {
  VISITOR_TOKEN_EXPIRES_AT_STORAGE_KEY,
  VISITOR_TOKEN_STORAGE_KEY,
  request
} = require('../../../utils/request.js')
const { visitActivityLifecycle } = require('../../../utils/visit-activity-lifecycle')

const VISITOR_PORTFOLIO_API_PREFIX = '/api/visitor/portfolios'
const SOURCE_TYPE_WECHAT_SHARE_CARD = 'WECHAT_SHARE_CARD'
const SOURCE_TYPE_QR_CODE = 'QR_CODE'
const SOURCE_TYPE_PERSONAL_PORTFOLIO = 'PERSONAL_PORTFOLIO'
const WX_LOGIN_EMPTY_MESSAGE = '微信登录凭证为空'
const WX_LOGIN_FAILED_MESSAGE = '微信登录失败'
const WX_LOGIN_TIMEOUT_MESSAGE = '微信登录超时，请重试'
const WX_LOGIN_TIMEOUT_MS = 5000

// scene 只解码一次；显式旧链接的 shareCode 保持原样。
function resolvePersonalShareCode(options = {}) {
  if (options.shareCode) return options.shareCode
  if (!options.scene) return ''
  try { return decodeURIComponent(options.scene) } catch (error) { return '' }
}

function getRuntimeWx(wxApi) {
  if (wxApi) {
    return wxApi
  }
  if (typeof wx !== 'undefined') {
    return wx
  }
  throw new Error('wx 运行环境不可用')
}

function createIdempotencyKey(prefix) {
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`
}

function wxLogin(wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  return new Promise((resolve, reject) => {
    let settled = false
    const finish = (handler, value, timer) => {
      if (settled) {
        return
      }
      settled = true
      clearTimeout(timer)
      handler(value)
    }
    const timer = setTimeout(() => {
      if (settled) {
        return
      }
      settled = true
      reject(new Error(WX_LOGIN_TIMEOUT_MESSAGE))
    }, WX_LOGIN_TIMEOUT_MS)
    runtimeWx.login({
      success(response) {
        if (response && response.code) {
          finish(resolve, response.code, timer)
          return
        }
        finish(reject, new Error(WX_LOGIN_EMPTY_MESSAGE), timer)
      },
      fail(error) {
        finish(reject, new Error(error && error.errMsg ? error.errMsg : WX_LOGIN_FAILED_MESSAGE), timer)
      }
    })
  })
}

function saveVisitorToken(response = {}, wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  if (!response.token || !runtimeWx.setStorageSync) {
    return
  }
  try {
    runtimeWx.setStorageSync(VISITOR_TOKEN_STORAGE_KEY, response.token)
    if (response.expiresInSeconds) {
      runtimeWx.setStorageSync(VISITOR_TOKEN_EXPIRES_AT_STORAGE_KEY, Date.now() + Number(response.expiresInSeconds) * 1000)
    }
  } catch (error) {
    // 本地存储写入失败时不阻断作品集打开流程。
  }
}

async function openVisitorSession(shareCode, options = {}) {
  if (!shareCode) {
    throw new Error('作品集分享码缺失')
  }
  const context = options.browserContext || null
  if (context && context.isInvalid()) throw Object.assign(new Error('访问上下文已失效'), { statusCode: 403 })
  if (context && context.isDisposed && context.isDisposed()) throw Object.assign(new Error('访问上下文已关闭'), { contextDisposed: true })
  const contextOptions = context ? context.openOptions() : {}
  const resolved = Object.assign({}, options, contextOptions)
  const runtimeWx = getRuntimeWx(resolved.wxApi)
  const anonymousSessionId = String(resolved.anonymousSessionId || '').trim()
  const identityData = anonymousSessionId
    ? { anonymousSessionId }
    : { loginCode: await wxLogin(runtimeWx) }
  const response = await (resolved.requestFn || request)({
    url: `${VISITOR_PORTFOLIO_API_PREFIX}/${shareCode}/open`,
    method: 'POST',
    authMode: 'none',
    data: Object.assign({}, identityData, {
      sourceType: resolved.sourceType || SOURCE_TYPE_WECHAT_SHARE_CARD,
      idempotencyKey: resolved.idempotencyKey || createIdempotencyKey(resolved.idempotencyPrefix || 'open')
    }, resolved.tracking ? { tracking: resolved.tracking } : {})
  })
  if (context) {
    if (context.isDisposed && context.isDisposed()) throw Object.assign(new Error('访问上下文已关闭'), { contextDisposed: true })
    if (context.isInvalid()) throw Object.assign(new Error('访问上下文已失效'), { statusCode: 403 })
    context.acceptSession(response)
    if (context.isInvalid()) throw Object.assign(new Error('访客身份已变化'), { statusCode: 403 })
    context.sessionGeneration = (context.sessionGeneration || 0) + 1
  }
  if (resolved.persistToken !== false) saveVisitorToken(response, runtimeWx)
  return response
}

function isVisitorAuthRequired(error, requestOptions = {}) {
  return Boolean(error && error.authRequired && (error.authMode === 'visitor' || requestOptions.authMode === 'visitor'))
}

async function requestWithVisitorSessionRefresh(requestOptions = {}, options = {}) {
  const context = options.browserContext || visitActivityLifecycle.findContext('PERSONAL', options.shareCode)
  const requestFn = options.requestFn || request
  const beforeGeneration = context && context.sessionGeneration || 0
  if (context && context.isInvalid()) throw Object.assign(new Error('访问上下文已失效'), { statusCode: 403 })
  try {
    return await requestFn(requestOptions)
  } catch (error) {
    if (!isVisitorAuthRequired(error, requestOptions)) {
      throw error
    }
    if (!options.shareCode) {
      throw error
    }
    let response
    if (context) {
      if (context.isInvalid()) throw error
      if ((context.sessionGeneration || 0) !== beforeGeneration) response = context.getSession()
      else {
        if (!context.refreshPromise) {
          context.beginRecovery()
          context.refreshPromise = openVisitorSession(options.shareCode, Object.assign({}, options, { browserContext: context }))
            .then(async (session) => {
              if (context.onRefresh) await context.onRefresh(session)
              return session
            }).catch((failure) => { context.recoveryFailed(failure); throw failure })
            .finally(() => { context.refreshPromise = null })
        }
        response = await context.refreshPromise
      }
    } else response = await openVisitorSession(options.shareCode, options)
    if (options.onRefresh) {
      await options.onRefresh(response)
    }
    const refreshedOptions = Object.assign({}, requestOptions)
    if (requestOptions.data && response) {
      refreshedOptions.data = Object.assign({}, requestOptions.data)
      if (Object.prototype.hasOwnProperty.call(refreshedOptions.data, 'visitorKey')) refreshedOptions.data.visitorKey = response.visitorKey || ''
      if (Object.prototype.hasOwnProperty.call(refreshedOptions.data, 'visitorProfileToken')) refreshedOptions.data.visitorProfileToken = response.visitorProfileToken || ''
    }
    return requestFn(refreshedOptions)
  }
}

module.exports = {
  SOURCE_TYPE_QR_CODE,
  resolvePersonalShareCode,
  SOURCE_TYPE_PERSONAL_PORTFOLIO,
  SOURCE_TYPE_WECHAT_SHARE_CARD,
  openVisitorSession,
  requestWithVisitorSessionRefresh,
  saveVisitorToken,
  wxLogin
}
