const DEFAULT_BASE_URL = 'https://api.we-folio.dingchenyong.top'
const TOKEN_STORAGE_KEY = 'wefolio_token'
const VISITOR_TOKEN_STORAGE_KEY = 'wefolio_visitor_token'
const VISITOR_TOKEN_EXPIRES_AT_STORAGE_KEY = 'wefolio_visitor_token_expires_at'
const AUTH_MODE_MAINTAINER = 'maintainer'
const AUTH_MODE_VISITOR = 'visitor'
const AUTH_MODE_NONE = 'none'

function getRuntimeWx(wxApi) {
  if (wxApi) {
    return wxApi
  }
  if (typeof wx !== 'undefined') {
    return wx
  }
  throw new Error('wx 运行环境不可用')
}

function defaultGetToken(wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  return runtimeWx.getStorageSync ? runtimeWx.getStorageSync(TOKEN_STORAGE_KEY) : ''
}

function defaultGetVisitorToken(wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  return runtimeWx.getStorageSync ? runtimeWx.getStorageSync(VISITOR_TOKEN_STORAGE_KEY) : ''
}

function defaultGetVisitorTokenExpiresAt(wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  return runtimeWx.getStorageSync ? runtimeWx.getStorageSync(VISITOR_TOKEN_EXPIRES_AT_STORAGE_KEY) : ''
}

function clearVisitorTokenStorage(wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  if (!runtimeWx.removeStorageSync) {
    return
  }
  try {
    runtimeWx.removeStorageSync(VISITOR_TOKEN_STORAGE_KEY)
    runtimeWx.removeStorageSync(VISITOR_TOKEN_EXPIRES_AT_STORAGE_KEY)
  } catch (error) {
    // 本地存储清理失败不影响请求降级为无令牌。
  }
}

function isKnownExpiredVisitorToken(expiresAt) {
  const expiresAtMs = Number(expiresAt)
  return Number.isFinite(expiresAtMs) && expiresAtMs > 0 && expiresAtMs <= Date.now()
}

function resolveAuthToken(authMode, getToken, getVisitorToken, getVisitorTokenExpiresAt, clearVisitorToken) {
  if (authMode === AUTH_MODE_NONE) {
    return ''
  }
  if (authMode === AUTH_MODE_VISITOR) {
    const visitorToken = getVisitorToken()
    if (!visitorToken) {
      return ''
    }
    if (isKnownExpiredVisitorToken(getVisitorTokenExpiresAt())) {
      clearVisitorToken()
      return ''
    }
    return visitorToken
  }
  return getToken()
}

function isVisitorAuthMode(authMode) {
  return authMode === AUTH_MODE_VISITOR
}

function joinUrl(baseUrl, url) {
  if (/^https?:\/\//.test(url)) {
    return url
  }
  return `${baseUrl.replace(/\/$/, '')}${url.startsWith('/') ? url : `/${url}`}`
}

function createRequestError(message, extra = {}) {
  const error = new Error(message || '请求失败')
  Object.assign(error, extra)
  return error
}

function buildBackendFailureExtra(body, statusCode) {
  const responseData = body && Object.prototype.hasOwnProperty.call(body, 'data') ? body.data : undefined
  const detailFields = responseData && typeof responseData === 'object' && !Array.isArray(responseData)
    ? responseData
    : {}
  return Object.assign({ statusCode, data: responseData }, detailFields)
}

function isEmptyGetQueryValue(value) {
  return value === undefined || value === null || value === ''
}

function normalizeRequestData(method, data) {
  const requestData = data || {}
  if (String(method).toUpperCase() !== 'GET' || !requestData || typeof requestData !== 'object' || Array.isArray(requestData)) {
    return requestData
  }
  return Object.keys(requestData).reduce((result, key) => {
    const value = requestData[key]
    if (!isEmptyGetQueryValue(value)) {
      result[key] = value
    }
    return result
  }, {})
}

function createRequestClient(options = {}) {
  const baseUrl = options.baseUrl || DEFAULT_BASE_URL
  const wxApi = options.wxApi
  const getToken = options.getToken || (() => defaultGetToken(wxApi))
  const getVisitorToken = options.getVisitorToken || (() => defaultGetVisitorToken(wxApi))
  const getVisitorTokenExpiresAt = options.getVisitorTokenExpiresAt || (() => defaultGetVisitorTokenExpiresAt(wxApi))
  const clearVisitorToken = options.clearVisitorToken || (() => clearVisitorTokenStorage(wxApi))

  function request(requestOptions = {}) {
    const runtimeWx = getRuntimeWx(wxApi)
    const method = requestOptions.method || 'GET'
    const authMode = requestOptions.authMode || AUTH_MODE_MAINTAINER
    const token = resolveAuthToken(authMode, getToken, getVisitorToken, getVisitorTokenExpiresAt, clearVisitorToken)
    const header = Object.assign({
      'content-type': 'application/json'
    }, requestOptions.header || {})

    if (token && !header.Authorization) {
      header.Authorization = `Bearer ${token}`
    }

    return new Promise((resolve, reject) => {
      runtimeWx.request({
        url: joinUrl(baseUrl, requestOptions.url),
        method,
        data: normalizeRequestData(method, requestOptions.data),
        header,
        success(response) {
          const body = response.data || {}
          if (response.statusCode === 401) {
            if (isVisitorAuthMode(authMode)) {
              try {
                clearVisitorToken()
              } catch (error) {
                // 清理本地访客令牌失败不改变本次认证失败结果。
              }
            }
            reject(createRequestError(body.message || '未登录', { authRequired: true, authMode, statusCode: 401 }))
            return
          }
          if (response.statusCode < 200 || response.statusCode >= 300) {
            reject(createRequestError(
              body.message || `请求失败(${response.statusCode})`,
              buildBackendFailureExtra(body, response.statusCode)
            ))
            return
          }
          if (body.success === false) {
            reject(createRequestError(
              body.message || '请求失败',
              buildBackendFailureExtra(body, response.statusCode)
            ))
            return
          }
          resolve(Object.prototype.hasOwnProperty.call(body, 'data') ? body.data : body)
        },
        fail(error) {
          reject(createRequestError(error && error.errMsg ? error.errMsg : '网络请求失败'))
        }
      })
    })
  }

  return {
    request
  }
}

const defaultClient = createRequestClient()

module.exports = {
  DEFAULT_BASE_URL,
  TOKEN_STORAGE_KEY,
  VISITOR_TOKEN_STORAGE_KEY,
  VISITOR_TOKEN_EXPIRES_AT_STORAGE_KEY,
  createRequestClient,
  request: defaultClient.request
}
