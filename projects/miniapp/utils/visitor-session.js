const {
  VISITOR_TOKEN_EXPIRES_AT_STORAGE_KEY,
  VISITOR_TOKEN_STORAGE_KEY,
  request
} = require('./request')

const VISITOR_PORTFOLIO_API_PREFIX = '/api/visitor/portfolios'
const SOURCE_TYPE_WECHAT_SHARE_CARD = 'WECHAT_SHARE_CARD'
const WX_LOGIN_EMPTY_MESSAGE = '微信登录凭证为空'
const WX_LOGIN_FAILED_MESSAGE = '微信登录失败'
const WX_LOGIN_TIMEOUT_MESSAGE = '微信登录超时，请重试'
const WX_LOGIN_TIMEOUT_MS = 5000

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
  const runtimeWx = getRuntimeWx(options.wxApi)
  const loginCode = await wxLogin(runtimeWx)
  const response = await request({
    url: `${VISITOR_PORTFOLIO_API_PREFIX}/${shareCode}/open`,
    method: 'POST',
    authMode: 'none',
    data: {
      loginCode,
      sourceType: options.sourceType || SOURCE_TYPE_WECHAT_SHARE_CARD,
      idempotencyKey: createIdempotencyKey(options.idempotencyPrefix || 'open')
    }
  })
  saveVisitorToken(response, runtimeWx)
  return response
}

function isVisitorAuthRequired(error, requestOptions = {}) {
  return Boolean(error && error.authRequired && (error.authMode === 'visitor' || requestOptions.authMode === 'visitor'))
}

async function requestWithVisitorSessionRefresh(requestOptions = {}, options = {}) {
  try {
    return await request(requestOptions)
  } catch (error) {
    if (!isVisitorAuthRequired(error, requestOptions)) {
      throw error
    }
    if (!options.shareCode) {
      throw error
    }
    const response = await openVisitorSession(options.shareCode, options)
    if (options.onRefresh) {
      options.onRefresh(response)
    }
    return request(requestOptions)
  }
}

module.exports = {
  SOURCE_TYPE_WECHAT_SHARE_CARD,
  openVisitorSession,
  requestWithVisitorSessionRefresh,
  saveVisitorToken,
  wxLogin
}
