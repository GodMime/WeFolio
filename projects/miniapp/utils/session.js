const { TOKEN_STORAGE_KEY, request } = require('./request')

function getRuntimeWx(wxApi) {
  if (wxApi) {
    return wxApi
  }
  if (typeof wx !== 'undefined') {
    return wx
  }
  throw new Error('wx 运行环境不可用')
}

function getToken(wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  return runtimeWx.getStorageSync(TOKEN_STORAGE_KEY) || ''
}

function setToken(token, wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  runtimeWx.setStorageSync(TOKEN_STORAGE_KEY, token || '')
}

function clearToken(wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  runtimeWx.removeStorageSync(TOKEN_STORAGE_KEY)
}

function hasLocalToken(wxApi) {
  return Boolean(getToken(wxApi))
}

function handleAuthRequired(message, wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  clearToken(runtimeWx)
  if (runtimeWx.showToast) {
    runtimeWx.showToast({
      title: message || '未登录，请重新登录',
      icon: 'none'
    })
  }
  if (runtimeWx.redirectTo) {
    runtimeWx.redirectTo({
      url: '/pages/login/login'
    })
  }
}

function ensureSession() {
  return request({
    url: '/api/auth/session',
    requireAuth: false
  })
}

function maintainerWechatLogin(payload) {
  return request({
    url: '/api/auth/maintainer/wechat-login',
    method: 'POST',
    data: payload || {},
    requireAuth: false
  })
}

module.exports = {
  TOKEN_STORAGE_KEY,
  getToken,
  setToken,
  clearToken,
  hasLocalToken,
  handleAuthRequired,
  ensureSession,
  maintainerWechatLogin
}
