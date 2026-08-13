/**
 * 当前状态：业务入口不可达。
 * 原因：只有旧访客作品集分享页作为历史分享兼容入口，当前业务和历史分享均不应跳转本页面。
 * 保留说明：页面注册和重定向实现暂按遗留代码保留，不代表受支持入口；确认无外部依赖后统一清理。
 */

// 遗留重定向仅透传启动参数；在页面清理前禁止增加请求接口、创建会话或查询档期等业务行为。
const TARGET_PAGE = '/pages/portfolios/visitor-schedule/visitor-schedule'
const FORWARDED_KEYS = ['shareCode', 'scene', 'visitorKey']

function buildForwardQuery(options = {}) {
  return FORWARDED_KEYS
    .filter((key) => options[key] !== undefined && options[key] !== '')
    .map((key) => `${encodeURIComponent(key)}=${encodeURIComponent(String(options[key]))}`)
    .join('&')
}

Page({
  onLoad(options = {}) {
    const query = buildForwardQuery(options)
    wx.redirectTo({
      url: `${TARGET_PAGE}${query ? `?${query}` : ''}`,
      fail() {
        wx.showToast({ title: '档期打开失败，请稍后重试', icon: 'none' })
      }
    })
  }
})
