const {
  MOCK_PORTFOLIO_LIST,
  getMockTabs,
  showMockLoginRequiredToast
} = require('../utils/mock-experience')

const EDIT_URL = '/pages/mock/portfolio-standard-edit/portfolio-standard-edit'
const PREVIEW_URL = '/pages/mock/portfolio-standard-preview/portfolio-standard-preview'

Page({
  data: {
    tabs: getMockTabs('portfolio'),
    portfolioList: MOCK_PORTFOLIO_LIST
  },

  handlePortfolioTap() {
    wx.navigateTo({
      url: EDIT_URL
    })
  },

  handleActionTap(event) {
    const action = event.currentTarget.dataset.action
    if (action === 'preview') {
      wx.navigateTo({ url: PREVIEW_URL })
      return
    }
    showMockLoginRequiredToast()
  },

  handleLockedAction() {
    showMockLoginRequiredToast()
  }
})
