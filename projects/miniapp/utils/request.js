const DEFAULT_BASE_URL = 'https://api.we-folio.dingchenyong.top'
const TOKEN_STORAGE_KEY = 'wefolio_token'

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

  function request(requestOptions = {}) {
    const runtimeWx = getRuntimeWx(wxApi)
    const method = requestOptions.method || 'GET'
    const token = getToken()
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
            reject(createRequestError(body.message || '未登录', { authRequired: true, statusCode: 401 }))
            return
          }
          if (response.statusCode < 200 || response.statusCode >= 300) {
            reject(createRequestError(body.message || `请求失败(${response.statusCode})`, { statusCode: response.statusCode }))
            return
          }
          if (body.success === false) {
            reject(createRequestError(body.message || '请求失败', { statusCode: response.statusCode }))
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
  createRequestClient,
  request: defaultClient.request
}
