const {
  MOCK_DASHBOARD,
  getMockTabs
} = require('../utils/mock-experience')

Page({
  data: {
    dashboard: MOCK_DASHBOARD,
    tabs: getMockTabs('mine')
  },

  handleReturnLoginTap() {
    wx.redirectTo({
      url: '/pages/login/login'
    })
  }
})
