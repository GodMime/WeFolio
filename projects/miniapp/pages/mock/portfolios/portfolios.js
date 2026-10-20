const {
  MOCK_PORTFOLIO_LIST,
  MOCK_TEAM_PORTFOLIO_LIST,
  getMockTabs,
  showMockLoginRequiredToast
} = require('../utils/mock-experience')
const { buildPreviewVersionOptions, calculatePreviewVersionMenuLayout } = require('../utils/preview-version-menu')

const EDIT_URL = '/pages/mock/portfolio-standard-edit/portfolio-standard-edit'
const PREVIEW_URL = '/pages/mock/portfolio-standard-preview/portfolio-standard-preview'
const PUBLISHED_STATUS = 'PUBLISHED'
const DRAFT_SCOPE = 'draft'
const PUBLISHED_SCOPE = 'published'
const PREVIEW_ANCHOR_PREFIX = 'mock-preview-'
const PREVIEW_OPTIONS = buildPreviewVersionOptions({
  draftDescription: '本地体验草稿',
  publishedDescription: '固定的演示发布内容'
})

Page({
  data: {
    tabs: getMockTabs('portfolio'),
    ownerType: 'USER',
    portfolioList: MOCK_PORTFOLIO_LIST,
    teamPortfolioList: MOCK_TEAM_PORTFOLIO_LIST,
    previewVersionMenu: null
  },

  onShow() {
    this.previewNavigating = false
    this.handleClosePreviewVersionMenu()
  },

  onHide() { this.handleClosePreviewVersionMenu() },

  onUnload() { this.handleClosePreviewVersionMenu() },

  onPullDownRefresh() {
    this.handleClosePreviewVersionMenu()
    if (wx.stopPullDownRefresh) wx.stopPullDownRefresh()
  },

  handleOwnerTypeTap(event) {
    this.handleClosePreviewVersionMenu()
    const ownerType = event.currentTarget.dataset.type === 'TEAM' ? 'TEAM' : 'USER'
    if (ownerType === this.data.ownerType) {
      return
    }
    this.setData({ ownerType })
  },

  handlePortfolioTap(event) {
    if (this.closeOpenPreviewVersionMenu() || this.previewNavigating) return
    const portfolioId = Number(event && event.currentTarget.dataset.id) || MOCK_PORTFOLIO_LIST.portfolios[0].portfolioId
    if (!this.data.portfolioList.portfolios.some(item => item.portfolioId === portfolioId)) return
    wx.navigateTo({
      url: `${EDIT_URL}?portfolioId=${portfolioId}`
    })
  },

  handleActionTap(event) {
    const action = event.currentTarget.dataset.action
    if (action === 'preview') {
      this.handlePortfolioPreview(event, this.data.portfolioList.portfolios)
      return
    }
    if (this.closeOpenPreviewVersionMenu()) return
    showMockLoginRequiredToast()
  },

  handleTeamPortfolioTap() {
    if (this.closeOpenPreviewVersionMenu()) return
    showMockLoginRequiredToast()
  },

  handleTeamActionTap(event) {
    const action = event.currentTarget.dataset.action
    if (action === 'preview') {
      this.handlePortfolioPreview(event, this.data.teamPortfolioList.portfolios)
      return
    }
    if (this.closeOpenPreviewVersionMenu()) return
    showMockLoginRequiredToast()
  },

  handleLockedAction() {
    if (this.closeOpenPreviewVersionMenu()) return
    showMockLoginRequiredToast()
  },

  /** 定位期间再次点击也只取消，防止迟到回调打开旧菜单。 */
  closeOpenPreviewVersionMenu() {
    if (!this.previewMenuTarget && !this.data.previewVersionMenu) return false
    this.handleClosePreviewVersionMenu()
    return true
  },

  handleClosePreviewVersionMenu() {
    this.previewMenuMeasureToken = (this.previewMenuMeasureToken || 0) + 1
    this.previewMenuTarget = null
    if (this.data.previewVersionMenu) this.setData({ previewVersionMenu: null })
  },

  handlePortfolioPreview(event, portfolios) {
    if (this.closeOpenPreviewVersionMenu() || this.previewNavigating) return
    const portfolioId = Number(event.currentTarget.dataset.id)
    const item = portfolios.find(portfolio => portfolio.portfolioId === portfolioId)
    if (!item) return
    if (item.publicationStatus !== PUBLISHED_STATUS) {
      this.navigateMockPreview(portfolioId, DRAFT_SCOPE)
      return
    }
    if (!wx.createSelectorQuery || !wx.getWindowInfo) return
    const target = { portfolioId }
    this.previewMenuTarget = target
    const token = this.previewMenuMeasureToken
    wx.createSelectorQuery().in(this).select(`#${PREVIEW_ANCHOR_PREFIX}${portfolioId}`)
      .boundingClientRect(rect => {
        if (token !== this.previewMenuMeasureToken || this.previewMenuTarget !== target) return
        const layout = calculatePreviewVersionMenuLayout(rect, wx.getWindowInfo(), { preferAbove: false })
        if (!layout) {
          this.handleClosePreviewVersionMenu()
          return
        }
        this.setData({ previewVersionMenu: Object.assign({}, layout, { options: PREVIEW_OPTIONS }) })
      }).exec()
  },

  handlePreviewVersionSelect(event) {
    const target = this.previewMenuTarget
    const scope = event.currentTarget.dataset.scope
    if (!this.data.previewVersionMenu || !target || ![DRAFT_SCOPE, PUBLISHED_SCOPE].includes(scope)) return
    this.handleClosePreviewVersionMenu()
    this.navigateMockPreview(target.portfolioId, scope)
  },

  /** 菜单点选后锁住本次导航，返回列表或失败后再允许下一次预览。 */
  navigateMockPreview(portfolioId, scope) {
    if (this.previewNavigating) return
    this.previewNavigating = true
    wx.navigateTo({
      url: `${PREVIEW_URL}?portfolioId=${portfolioId}&scope=${scope}`,
      fail: () => { this.previewNavigating = false }
    })
  }
})
