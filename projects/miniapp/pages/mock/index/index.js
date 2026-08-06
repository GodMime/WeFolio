const {
  MOCK_DASHBOARD,
  getMockTabs,
  showMockLoginRequiredToast
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
  },

  handleEntryTap() {
    showMockLoginRequiredToast()
  },

  handleLockedAction() {
    showMockLoginRequiredToast()
  }
})
