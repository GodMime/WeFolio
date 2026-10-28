const { advancedEditorPageMethods, advancedEndpoint, editorRequest } = require('../utils/advanced-editor-page')
const { portfolioOpeningPageMethods } = require('../utils/portfolio-opening')
const { installRemoteFontPage } = require('../utils/portfolio-remote-font-page')
const { openWorkDetail } = require('../utils/portfolio-work-detail')
const { openVideoPlayer } = require('../utils/portfolio-video-player')
const VIDEO_PLAYER_ROUTE = '/pages/team-portfolios/video-player/video-player'
const { portfolioAudioPageMethods } = require('../utils/portfolio-audio-player')
const { request } = require('../../../utils/request.js')
const {
  handleTeamMaintainerAuthError,
  previewTeamPortfolio,
  showTeamPortfolioUnavailableToast
} = require('../utils/team-portfolios.js')
const {
  normalizeTeamVisitorPortfolio,
  switchTeamPortfolioMenu
} = require('../utils/team-visitor-portfolio.js')
const {
  captureTeamPortfolioMenuInteraction,
  clearTeamPortfolioMenuComponentState,
  clearTeamPortfolioMenuTransitionTimers,
  isTeamPortfolioMenuInteractionCurrent,
  startTeamPortfolioMenuTransition
} = require('../utils/team-portfolio-menu-transition.js')

const TYPE_BUCKETS = Object.freeze({
  TEAM_PROFILE: 'teamProfile',
  CAROUSEL: 'carousel',
  VIDEO_CAROUSEL: 'videoCarousel',
  SINGLE_WORK: 'singleWork',
  DIVIDER: 'divider',
  MEMBER_PORTFOLIO_GRID: 'grid',
  MEMBER_PORTFOLIO_LIST: 'list',
  TEXT_SECTION: 'text',
  STRUCTURED_TEXT_SECTION: 'structuredText', TEXT_GRID: 'textGrid', TIMELINE: 'timeline', CONTACT_INFO: 'contactInfo',
  SCHEDULE_QUERY: 'schedule',
  CONTACT_FORM: 'contact',
  QR_CONTACT: 'qr'
})
const PERSONAL_PREVIEW_URL = '/pages' + '/portfolios/standard-preview/portfolio-standard-preview'
const LIGHT_LOGO_URL = '/assets/system/folio-logo-stack-bold-small-50kb.png'
const DARK_LOGO_URL = '/assets/system/folio-logo-stack-bold-dark-50kb.png'
const PREVIEW_QUERY_UNSUPPORTED_MESSAGE = '预览模式不提交档期查询'
const CANDIDATE_QUERY_UNSUPPORTED_MESSAGE = '采用并保存后可预览档期'
const ADVANCED_PREVIEW_QUERY_FAILED_MESSAGE = '档期预览查询失败，请重试'

function buckets(items) {
  const value = {
    teamProfile: [], carousel: [], videoCarousel: [], singleWork: [], divider: [], grid: [],
    list: [], text: [], structuredText: [], textGrid: [], timeline: [], contactInfo: [], schedule: [], contact: [], qr: []
  }
  ;(Array.isArray(items) ? items : []).forEach((item) => {
    const key = TYPE_BUCKETS[item.componentType]
    if (key) value[key].push(item)
  })
  return value
}

Page({
  ...advancedEditorPageMethods,
  advancedRequest(options) { return editorRequest(this, options, request) },
  ...portfolioAudioPageMethods,
  ...portfolioOpeningPageMethods,
  data: {
    backgroundAudioPlaying: false, backgroundAudioResource: {}, backgroundAudioTop: 76,
    portfolioId: 0,
    scope: 'draft',
    loading: false,
    errorMessage: '',
    portfolio: normalizeTeamVisitorPortfolio({ renderData: { preview: true } }),
    componentBuckets: buckets([]),
    empty: true,
    previewMode: true,
    themeMode: 'light',
    backgroundColor: '#FFFFFF',
    navigationColor: '#212529',
    brandLogoUrl: LIGHT_LOGO_URL,
    portfolioScrollTop: 0,
    teamPortfolioMenuSwitching: false,
    teamPortfolioMenuTransitionClass: '',
    activeSingleWorkVideoKey: '',
    videoPreviewVisible: false,
    scheduleResults: {},
    contactForms: {},
    contactModalVisible: {}
  },
  onLoad(options = {}) {
    this.remoteFontPage = installRemoteFontPage(this, { editor: false, team: true })
    this.positionBackgroundAudio()
    this.setData({ advancedEditing: options.templateType === 'ADVANCED', candidatePreview: options.candidate === '1' })
    if (this.data.candidatePreview) {
      const channel = this.getOpenerEventChannel && this.getOpenerEventChannel()
      if (channel && channel.on) channel.on('candidatePreview', payload => { this.applyPortfolio(normalizeTeamVisitorPortfolio(payload)); this.setData({ loading: false, errorMessage: '' }) })
      return
    }
    const portfolioId = Number(options.portfolioId)
    const scope = options.scope === 'published' ? 'published' : 'draft'
    if (!portfolioId) {
      this.setData({ errorMessage: '作品集参数无效' })
      return
    }
    this.setData({ portfolioId, scope })
    this.bootstrap()
  },
  async bootstrap() {
    if (this.data.loading) return
    this.setData({ loading: true, errorMessage: '' })
    try {
      const detail = await previewTeamPortfolio(
        options => this.advancedRequest(options),
        this.data.portfolioId,
        this.data.scope === 'published'
      )
      const portfolio = normalizeTeamVisitorPortfolio({
        portfolioId: this.data.portfolioId,
        renderData: detail.renderData || {}
      })
      this.applyPortfolio(portfolio)
    } catch (error) {
      if (handleTeamMaintainerAuthError(error)) return
      if (showTeamPortfolioUnavailableToast(error)) return
      this.setData({ errorMessage: '预览加载失败，请重试' })
    } finally {
      this.setData({ loading: false })
    }
  },
  applyPortfolio(portfolio) {
    const activeComponents = Array.isArray(portfolio.activeComponents)
      ? portfolio.activeComponents
      : []
    const themeMode = portfolio.themeMode === 'dark' ? 'dark' : 'light'
    this.setData({
      portfolio,
      componentBuckets: buckets(activeComponents),
      empty: activeComponents.length === 0,
      themeMode,
      backgroundColor: portfolio.style.backgroundColor,
      navigationColor: themeMode === 'dark' ? '#F8F9FA' : '#212529',
      brandLogoUrl: themeMode === 'dark' ? DARK_LOGO_URL : LIGHT_LOGO_URL
    })
  },
  handleMenuTap(event) {
    const menuKey = (event.detail && event.detail.menuKey) ||
      (event.currentTarget && event.currentTarget.dataset.key)
    const leavingComponents = this.data.portfolio.activeComponents
    startTeamPortfolioMenuTransition(this, menuKey, {
      onBeforeExit: () => { this.stopSingleWorkVideos(); this.clearVideoPreview() },
      switchPortfolio: switchTeamPortfolioMenu,
      buildSwitchPatch: (portfolio) => ({
        componentBuckets: buckets(portfolio.activeComponents),
        empty: portfolio.activeComponents.length === 0,
        scheduleResults: clearTeamPortfolioMenuComponentState(
          this.data.scheduleResults,
          leavingComponents
        ),
        contactForms: clearTeamPortfolioMenuComponentState(
          this.data.contactForms,
          leavingComponents
        ),
        contactModalVisible: clearTeamPortfolioMenuComponentState(
          this.data.contactModalVisible,
          leavingComponents
        )
      })
    })
  },
  handleRetry() { this.bootstrap() },
  handleImagePreview(event) {
    const item = event.detail && event.detail.item
    if (item && (item.mediaUrl || item.coverUrl)) {
      wx.previewImage({
        current: item.mediaUrl || item.coverUrl,
        urls: [item.mediaUrl || item.coverUrl]
      })
    }
  },
  handleWorkDetail(event) {
    return openWorkDetail(this, {
      componentKey: event.detail && event.detail.componentKey,
      components: this.data.portfolio.activeComponents || [],
      theme: { backgroundColor: this.data.portfolio.style && this.data.portfolio.style.backgroundColor, themeMode: this.data.portfolio.themeMode },
      preview: true,
      route: '/pages/team-portfolios/work-detail/team-portfolio-work-detail'
    })
  },
  handleSingleWorkPreview(event) {
    const work = event.detail && event.detail.work
    if (work && work.mediaUrl) {
      wx.previewImage({ current: work.mediaUrl, urls: [work.mediaUrl] })
    }
  },
  handleSingleWorkActivate(event) {
    this.pauseBackgroundAudio()
    const componentKey = event.detail && event.detail.componentKey
    if (!componentKey) return
    this.pauseSingleWorkVideos(componentKey)
    this.setData({ activeSingleWorkVideoKey: componentKey })
  },
  handleSingleWorkVideoError() {
    this.stopSingleWorkVideos()
    wx.showToast({ title: '视频播放失败，请重试', icon: 'none' })
  },
  pauseSingleWorkVideos(exceptKey) {
    const children = this.selectAllComponents
      ? this.selectAllComponents('.team-single-work-instance')
      : []
    ;(children || []).forEach((child) => {
      if (!exceptKey || child.properties.componentKey !== exceptKey) {
        if (child.pauseVideo) child.pauseVideo()
      }
    })
  },
  stopSingleWorkVideos() {
    this.pauseSingleWorkVideos('')
    this.setData({ activeSingleWorkVideoKey: '' })
  },
  clearVideoPreview() {
    // 返回作品集或打开失败时，解除文字组件的背景视频暂停。
    if (this.data.videoPreviewVisible) this.setData({ videoPreviewVisible: false })
  },

  openVideoPreview(work = {}) {
    if (this.videoPlayerDisposed || this.data.videoPreviewVisible) return false
    this.pauseBackgroundAudio()
    const mediaUrl = String(work.mediaUrl || '')
    if (!mediaUrl) {
      wx.showToast({ title: '视频地址缺失', icon: 'none' })
      return false
    }
    this.stopSingleWorkVideos()
    this.setData({ videoPreviewVisible: true })
    return openVideoPlayer({ url: mediaUrl, poster: work.coverUrl || '', title: work.title || '',
      route: VIDEO_PLAYER_ROUTE, browserContext: this.browserContext || null }, wx)
      .then((opened) => {
        // 导航成功后来源页保持暂停，失败或返回作品集时才恢复背景视频。
        if (!opened && !this.videoPlayerDisposed) this.clearVideoPreview()
        return opened
      })
  },

  handleVideoCarouselPlay(event) { return this.openVideoPreview(event && event.detail && event.detail.work) },
  onShow() {
    this.clearVideoPreview()
    this.showBackgroundAudio()
    if (this.remoteFontPage) this.remoteFontPage.show()
  },
  onHide() {
    if (this.remoteFontPage) this.remoteFontPage.hide()
    this.hideBackgroundAudio(); this.stopSingleWorkVideos() },
  onUnload() {
    if (this.remoteFontPage) this.remoteFontPage.dispose()
    this.videoPlayerDisposed = true
    this.destroyBackgroundAudio()
    this.stopSingleWorkVideos()
    clearTeamPortfolioMenuTransitionTimers(this)
  },
  handleQrPreview() {},
  handleMemberPortfolio(event) {
    if (this.data.candidatePreview) { wx.showToast({ title: '采用并保存后可预览成员作品集', icon: 'none' }); return }
    const memberPortfolioId = Number(event.detail && event.detail.portfolioId)
    if (memberPortfolioId) {
      wx.navigateTo({
        url: `${PERSONAL_PREVIEW_URL}?portfolioId=${memberPortfolioId}` +
          `&teamPortfolioId=${this.data.portfolioId}&teamScope=${this.data.scope}${this.data.advancedEditing ? '&templateType=ADVANCED' : ''}`
      })
    }
  },
  handleScheduleQuery(event) {
    const detail = event.detail || {}
    const child = this.selectComponent(`#schedule-${detail.componentKey}`)
    if (this.data.advancedEditing && !this.data.candidatePreview && this.data.portfolioId) {
      return this.queryAdvancedPreviewSchedule(detail, child)
    }
    const message = this.data.candidatePreview ? CANDIDATE_QUERY_UNSUPPORTED_MESSAGE : PREVIEW_QUERY_UNSUPPORTED_MESSAGE
    if (child && child.rejectQuery) {
      child.rejectQuery({
        detail: {
          message,
          clearPendingIdempotencyKey: true
        }
      })
    }
    wx.showToast({ title: message, icon: 'none' })
    return false
  },
  /** 已保存高级配置只读查档；候选和普通预览保持各自提交边界。 */
  async queryAdvancedPreviewSchedule(detail, child) {
    const menuInteraction = captureTeamPortfolioMenuInteraction(this)
    try {
      const result = await this.advancedRequest({
        url: `${advancedEndpoint(this.data.portfolioId, '/schedule-query-preview')}?scope=${encodeURIComponent(this.data.scope)}`,
        method: 'POST', data: { componentKey: detail.componentKey, queriedDate: detail.queriedDate, idempotencyKey: detail.idempotencyKey }
      })
      if (!isTeamPortfolioMenuInteractionCurrent(this, menuInteraction)) return false
      this.setData({ scheduleResults: { ...this.data.scheduleResults, [detail.componentKey]: result } })
      if (child && child.resolveQuery) child.resolveQuery({ detail: result })
      return true
    } catch (error) {
      if (!isTeamPortfolioMenuInteractionCurrent(this, menuInteraction)) return false
      if (child && child.rejectQuery) child.rejectQuery({ detail: { message: error.message || ADVANCED_PREVIEW_QUERY_FAILED_MESSAGE,
        clearPendingIdempotencyKey: Number(error.statusCode) >= 400 && Number(error.statusCode) < 500 } })
      if (handleTeamMaintainerAuthError(error) || showTeamPortfolioUnavailableToast(error)) return false
      wx.showToast({ title: error.message || ADVANCED_PREVIEW_QUERY_FAILED_MESSAGE, icon: 'none' })
      return false
    }
  },
  handleContactInput(event) {
    const componentKey = event.currentTarget.dataset.key
    this.setData({
      contactForms: Object.assign({}, this.data.contactForms, {
        [componentKey]: event.detail.form
      })
    })
  },
  handleContactOpen(event) {
    const componentKey = event.currentTarget.dataset.key
    this.setData({
      contactModalVisible: Object.assign({}, this.data.contactModalVisible, {
        [componentKey]: true
      })
    })
  },
  handleContactClose(event) {
    const componentKey = event.currentTarget.dataset.key
    this.setData({
      contactModalVisible: Object.assign({}, this.data.contactModalVisible, {
        [componentKey]: false
      })
    })
  },
  handleContactSubmit() {
    wx.showToast({ title: '预览模式不提交', icon: 'none' })
  }
})
