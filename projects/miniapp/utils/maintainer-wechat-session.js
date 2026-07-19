const { hasLocalToken, refreshMaintainerWechatSession } = require('./session')

const DEFAULT_CHECK_INTERVAL_SECONDS = 300

function getRuntimeWx(wxApi) {
  if (wxApi) return wxApi
  if (typeof wx !== 'undefined') return wx
  throw new Error('wx 运行环境不可用')
}

function normalizeIntervalSeconds(value) {
  const seconds = Number(value)
  return Number.isFinite(seconds) && seconds > 0
    ? Math.floor(seconds)
    : DEFAULT_CHECK_INTERVAL_SECONDS
}

function createMaintainerWechatSessionController(options = {}) {
  const runtime = () => getRuntimeWx(options.wxApi)
  const hasToken = options.hasToken || (() => hasLocalToken(runtime()))
  const refreshRequest = options.refreshRequest || refreshMaintainerWechatSession
  const setIntervalFn = options.setIntervalFn || setInterval
  const clearIntervalFn = options.clearIntervalFn || clearInterval
  let timer = null
  let inFlight = null

  function loginAndRefresh() {
    return new Promise((resolve, reject) => {
      runtime().login({
        success(result) {
          if (!result || !result.code) {
            reject(new Error('微信登录未返回有效 code'))
            return
          }
          Promise.resolve(refreshRequest(result.code)).then(resolve, reject)
        },
        fail(error) {
          reject(new Error(error && error.errMsg ? error.errMsg : '微信登录失败'))
        }
      })
    })
  }

  function checkSession() {
    return new Promise((resolve, reject) => {
      runtime().checkSession({
        success: resolve,
        fail: reject
      })
    })
  }

  function checkNow() {
    if (!hasToken()) return Promise.resolve(false)
    if (inFlight) return inFlight
    const current = checkSession()
      .catch(() => loginAndRefresh())
      .then(() => true)
      .finally(() => {
        if (inFlight === current) inFlight = null
      })
    inFlight = current
    return current
  }

  function onShow(intervalSeconds) {
    if (!hasToken()) return Promise.resolve(false)
    if (timer !== null) {
      clearIntervalFn(timer)
      timer = null
    }
    const immediate = checkNow()
    const delay = normalizeIntervalSeconds(intervalSeconds) * 1000
    timer = setIntervalFn(() => {
      checkNow().catch((error) => {
        console.warn('维护者微信会话检查失败', error)
      })
    }, delay)
    return immediate
  }

  function onHide() {
    if (timer === null) return
    clearIntervalFn(timer)
    timer = null
  }

  return {
    checkNow,
    onShow,
    onHide,
    getTimerForTest: () => timer
  }
}

module.exports = {
  DEFAULT_CHECK_INTERVAL_SECONDS,
  createMaintainerWechatSessionController
}
