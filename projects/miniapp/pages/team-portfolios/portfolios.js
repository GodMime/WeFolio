/**
 * 历史团队作品集列表兼容入口。
 * 页面只负责跳转到统一作品集列表的团队面板，禁止请求接口或承载列表业务。
 */
const UNIFIED_TEAM_PORTFOLIO_LIST_URL = '/pages/portfolios/portfolios?ownerType=TEAM'

Page({
  data: {},

  onLoad() {
    wx.redirectTo({ url: UNIFIED_TEAM_PORTFOLIO_LIST_URL })
  }
})
