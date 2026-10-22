const PAYMENTS_UNSUPPORTED = '安卓端暂不支持充值'
const PAYMENT_API_PATH = /^\/api\/mine\/recharges(?:\/|$)/
const MINIAPP_AUTH_PATH = /^\/api\/auth\/maintainer\/wechat-(?:login|session)(?:\/|$)/

/** 安卓端仅允许查看充值记录，禁止发起、同步支付及刷新小程序支付会话。 */
function assertRequestAllowed(url, method = 'GET') {
  const pathname = String(url || '').replace(/^https?:\/\/[^/]+/, '').split('?')[0]
  if (PAYMENT_API_PATH.test(pathname) && method.toUpperCase() !== 'GET') {
    throw new Error(PAYMENTS_UNSUPPORTED)
  }
  if (MINIAPP_AUTH_PATH.test(pathname)) {
    throw new Error('安卓端请使用移动应用微信授权登录')
  }
}

module.exports = { PAYMENTS_UNSUPPORTED, assertRequestAllowed }
