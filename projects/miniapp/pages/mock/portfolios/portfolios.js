const {
  MOCK_PORTFOLIO_LIST,
  MOCK_TEAM_PORTFOLIO_LIST,
  getMockTabs,
  showMockLoginRequiredToast
} = require('../utils/mock-experience')

const EDIT_URL = '/pages/mock/portfolio-standard-edit/portfolio-standard-edit'
const PREVIEW_URL = '/pages/mock/portfolio-standard-preview/portfolio-standard-preview'

Page({
  data: {
    tabs: getMockTabs('portfolio'),
    ownerType: 'USER',
    portfolioList: MOCK_PORTFOLIO_LIST,
    teamPortfolioList: MOCK_TEAM_PORTFOLIO_LIST
  },

  handleOwnerTypeTap(event) {
    const ownerType = event.currentTarget.dataset.type === 'TEAM' ? 'TEAM' : 'USER'
    if (ownerType === this.data.ownerType) {
      return
    }
    this.setData({ ownerType })
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

  handleTeamPortfolioTap() {
    showMockLoginRequiredToast()
  },

  handleTeamActionTap(event) {
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
