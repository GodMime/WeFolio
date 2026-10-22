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
const DRAFT_STATUS = 'DRAFT_ONLY'
const LEGACY_DRAFT_STATUS = 'DRAFT'
const OWNER_TYPE_TEAM = 'TEAM'
const PUBLICATION_FILTER_OPTIONS = [
  { value: '', label: '全部作品集' },
  { value: PUBLISHED_STATUS, label: '已发布' },
  { value: DRAFT_STATUS, label: '草稿' }
]
const FILTER_MENU_WIDTH = 160
const FILTER_MENU_HEIGHT = 154
const FILTER_MENU_MARGIN = 16
const FILTER_MENU_GAP = 4
const FILTER_LOADING_MIN_MS = 240
const DRAFT_SCOPE = 'draft'
const PUBLISHED_SCOPE = 'published'
const PREVIEW_ANCHOR_PREFIX = 'mock-preview-'
const PREVIEW_OPTIONS = buildPreviewVersionOptions({
  draftDescription: '本地体验草稿',
  publishedDescription: '固定的演示发布内容'
})

/** 仅在本地筛选时兼容体验样例的旧草稿值，不改写样例。 */
function matchesPublicationFilter(item, filter) {
  const status = item.publicationStatus === LEGACY_DRAFT_STATUS ? DRAFT_STATUS : item.publicationStatus
  return !filter || status === filter
}

function withSummary(list) {
  return Object.assign({}, list, { summary: {
    publishedText: `${list.portfolios.filter(item => matchesPublicationFilter(item, PUBLISHED_STATUS)).length} 已发布`,
    draftText: `${list.portfolios.filter(item => matchesPublicationFilter(item, DRAFT_STATUS)).length} 草稿`
  } })
}

Page({
  data: {
    tabs: getMockTabs('portfolio'),
    ownerType: 'USER',
    portfolioList: withSummary(MOCK_PORTFOLIO_LIST),
    teamPortfolioList: withSummary(MOCK_TEAM_PORTFOLIO_LIST),
    displayPortfolios: MOCK_PORTFOLIO_LIST.portfolios,
    teamDisplayPortfolios: MOCK_TEAM_PORTFOLIO_LIST.portfolios,
    publicationFilter: '',
    teamPublicationFilter: '',
    filterLabel: PUBLICATION_FILTER_OPTIONS[0].label,
    teamFilterLabel: PUBLICATION_FILTER_OPTIONS[0].label,
    filterOptions: PUBLICATION_FILTER_OPTIONS,
    filterMenu: null,
    filterLoading: false,
    teamFilterLoading: false,
    previewVersionMenu: null
  },

  onShow() {
    this.previewNavigating = false
    this.handleClosePreviewVersionMenu()
  },

  onHide() {
    this.invalidateFilterRequests()
    this.handleClosePreviewVersionMenu()
  },

  onUnload() {
    this.invalidateFilterRequests()
    this.handleClosePreviewVersionMenu()
  },

  onPullDownRefresh() {
    this.handleClosePreviewVersionMenu()
    if (wx.stopPullDownRefresh) wx.stopPullDownRefresh()
  },

  handleFilterTap() {
    const wasOpen = this.data.filterMenu || this.filterMenuMeasuring
    this.handleClosePreviewVersionMenu()
    if (wasOpen) return
    const ownerType = this.data.ownerType
    const token = this.filterMenuToken
    this.filterMenuMeasuring = true
    try {
      const viewport = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
      const selector = ownerType === OWNER_TYPE_TEAM ? '#team-publication-filter' : '#personal-publication-filter'
      wx.createSelectorQuery().in(this).select(selector).boundingClientRect(rect => {
        if (token !== this.filterMenuToken) return
        this.filterMenuMeasuring = false
        if (!rect || !Number.isFinite(rect.left) || !Number.isFinite(rect.bottom)) return
        const width = Math.min(FILTER_MENU_WIDTH, viewport.windowWidth - FILTER_MENU_MARGIN * 2)
        const left = Math.max(FILTER_MENU_MARGIN, Math.min(rect.left, viewport.windowWidth - width - FILTER_MENU_MARGIN))
        const top = Math.max(FILTER_MENU_MARGIN, Math.min(rect.bottom + FILTER_MENU_GAP, viewport.windowHeight - FILTER_MENU_HEIGHT - FILTER_MENU_MARGIN))
        this.setData({ filterMenu: {
          ownerType,
          selected: ownerType === OWNER_TYPE_TEAM ? this.data.teamPublicationFilter : this.data.publicationFilter,
          style: `left: ${left}px; top: ${top}px; width: ${width}px;`
        } })
      }).exec()
    } catch (error) { this.closeFilterMenu() }
  },

  closeFilterMenu() {
    this.filterMenuToken = (this.filterMenuToken || 0) + 1
    this.filterMenuMeasuring = false
    if (this.data.filterMenu) this.setData({ filterMenu: null })
  },

  /** 接住菜单内的手指微移，避免父层在 tap 前销毁选项。 */
  noop() {},

  async handleFilterSelect(event) {
    const value = event.currentTarget.dataset.value
    const option = PUBLICATION_FILTER_OPTIONS.find(item => item.value === value)
    if (!option) return
    this.handleClosePreviewVersionMenu()
    const team = this.data.ownerType === OWNER_TYPE_TEAM
    const requestKey = team ? 'teamFilterRequestId' : 'personalFilterRequestId'
    const targetKey = team ? 'teamFilterTarget' : 'personalFilterTarget'
    const loadingKey = team ? 'teamFilterLoading' : 'filterLoading'
    const current = team ? this.data.teamPublicationFilter : this.data.publicationFilter
    if (value === (this[targetKey] === undefined ? current : this[targetKey])) return
    const requestId = this[requestKey] = (this[requestKey] || 0) + 1
    this[targetKey] = value
    this.setData({ [loadingKey]: true })
    // 本地数据也展示相同加载过渡，不触达网络；切换或离开后忽略旧结果。
    await new Promise(resolve => setTimeout(resolve, FILTER_LOADING_MIN_MS))
    if (requestId !== this[requestKey]) return
    const list = team ? this.data.teamPortfolioList : this.data.portfolioList
    this[targetKey] = undefined
    this.setData({
      [loadingKey]: false,
      [team ? 'teamPublicationFilter' : 'publicationFilter']: value,
      [team ? 'teamFilterLabel' : 'filterLabel']: option.label,
      [team ? 'teamDisplayPortfolios' : 'displayPortfolios']: list.portfolios.filter(item => matchesPublicationFilter(item, value))
    })
  },

  invalidateFilterRequests() {
    this.personalFilterRequestId = (this.personalFilterRequestId || 0) + 1
    this.teamFilterRequestId = (this.teamFilterRequestId || 0) + 1
    this.personalFilterTarget = undefined
    this.teamFilterTarget = undefined
    this.setData({ filterLoading: false, teamFilterLoading: false })
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
    this.closeFilterMenu()
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
