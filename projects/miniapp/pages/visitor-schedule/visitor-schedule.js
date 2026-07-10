/**
 * 历史访客档期分享兼容入口。
 * 已发出的旧入口可能固化了当前页面路径，禁止删除或改作业务页面。
 * 本页只负责透传启动参数并跳转到 portfolio 分包，禁止请求接口或查询档期。
 */

// 新访客档期页真实实现位于 portfolio 分包；修改目标路由时必须同步更新兼容测试。
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
