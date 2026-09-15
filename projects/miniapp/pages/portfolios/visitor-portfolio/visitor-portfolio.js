const { portfolioAudioPageMethods } = require('../utils/portfolio-audio-player')
const { createPortfolioVisitActivity } = require('../utils/visit-activity-page')
const { openWorkDetail } = require('../utils/portfolio-work-detail')
const { openVideoPlayer } = require('../utils/portfolio-video-player')
const VIDEO_PLAYER_ROUTE = '/pages/portfolios/video-player/video-player'
const { buildContactLeadPayload, createContactLeadForm, validateContactLeadForm } = require('../utils/contact-lead')
const {
  createActiveContactFormComponent,
  findContactFormComponent
} = require('../utils/portfolio-contact-form')
const {
  normalizeSingleWorkTapDataset,
  normalizeWorkTapDataset,
  readPortfolioRenderEventData
} = require('../utils/portfolio-render-events')
const { clearDisplaySwitchingTimer, markDisplaySwitching } = require('../utils/display-switching')
const {
  clearPortfolioMenuTransitionTimers,
  startPortfolioMenuTransition
} = require('../utils/portfolio-menu-transition')
const {
  buildVisitorEventPayload,
  normalizeVisitorPortfolio,
  switchDisplayGroup,
  switchPortfolioMenu
} = require('../utils/visitor-portfolio')
const { uploadVisitorAvatarProfile } = require('../utils/visitor-profile')
const { createClipboardPromptController } = require('../utils/portfolio-hyperlink')
const { request } = require('../../../utils/request')
const {
  SOURCE_TYPE_PERSONAL_PORTFOLIO,
  SOURCE_TYPE_WECHAT_SHARE_CARD,
  openVisitorSession,
  requestWithVisitorSessionRefresh
} = require('../utils/visitor-session')
const {
  createAnonymousSessionId,
  isWechatTimelineSinglePage
} = require('../utils/single-page-mode')

const VISITOR_PORTFOLIO_API_PREFIX = '/api/visitor/portfolios'
const MEDIA_TYPE_VIDEO = 'VIDEO'
const WORK_VIEWED_EVENT_TYPE = 'WORK_VIEWED'
const VIDEO_PLAYED_EVENT_TYPE = 'VIDEO_PLAYED'
const QR_CODE_INTERACTED_EVENT_TYPE = 'QR_CODE_INTERACTED'
const QR_ACTION_PREVIEW = 'PREVIEW_QR'
const WORK_TITLE_METADATA_KEY = 'workTitle'
const IMAGE_MISSING_MESSAGE = '图片地址缺失'
const VIDEO_MISSING_MESSAGE = '视频地址缺失'
const VISITOR_PROFILE_REQUIRED_MESSAGE = '请完善头像和昵称'
const TIMELINE_SHARE_GUIDE_VALUE = 'timeline'
const SHARE_MENU_ITEMS = Object.freeze(['shareAppMessage', 'shareTimeline'])
const PORTFOLIOS_API_URL = '/api/mine/portfolios'
const SHARE_CHANNEL_WECHAT_TIMELINE = 'WECHAT_TIMELINE'
const SHARE_SCENE_PORTFOLIO_LIST = 'PORTFOLIO_LIST'
const WORK_DETAIL_ROUTE = '/pages/portfolios/work-detail/portfolio-work-detail'

function positiveId(value) {
  const id = Number(value)
  return Number.isInteger(id) && id > 0 ? id : 0
}

function idempotencyKey(prefix) {
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`
}

function hasPreviousPage() {
  const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
  return Array.isArray(pages) && pages.length > 1
}

function buildWorkEventMetadata(work) {
  const workTitle = work && work.title ? String(work.title).trim() : ''
  return workTitle ? { [WORK_TITLE_METADATA_KEY]: workTitle } : null
}

Page({
  ...portfolioAudioPageMethods,
  data: {
    backgroundAudioPlaying: false, backgroundAudioResource: {}, backgroundAudioTop: 76,
    shareCode: '',
    showNavigationBack: false,
    timelineGuideRequested: false,
    timelineGuideVisible: false,
    timelineSharePortfolioId: 0,
    timelineShareRecordEnabled: false,
    visitorKey: '',
    portfolio: normalizeVisitorPortfolio({}),
    contactForm: createContactLeadForm({}),
    contactFormModalVisible: false,
    activeContactFormComponent: createActiveContactFormComponent(),
    videoPreviewVisible: false,
    activeSingleWorkVideoKey: '',
    visitorProfileAuthVisible: false,
    visitorProfileToken: '',
    visitorProfileForm: {
      avatarUrl: '',
      nickname: ''
    },
    visitorProfileAvatarError: false,
    visitorProfileNicknameError: false,
    visitorProfileValidationShaking: false,
    visitorProfileSaving: false,
    displaySwitchingComponentKey: '',
    portfolioMenuSwitching: false,
    portfolioMenuTransitionClass: '',
    portfolioScrollTop: 0,
    clipboardPromptVisible: false,
    clipboardPromptText: ''
  },

  onLoad(options = {}) {
    this.visitPageUnloaded = false
    this.positionBackgroundAudio()
    this.singleWorkPageVisible = true
    this.singleWorkInteractionRevision = 0
    const shareCode = options.shareCode || options.scene || ''
    const showNavigationBack = hasPreviousPage()
    const timelineGuideRequested = options.shareGuide === TIMELINE_SHARE_GUIDE_VALUE
    this.visitorSourceType = options.sourceType === SOURCE_TYPE_PERSONAL_PORTFOLIO
      ? SOURCE_TYPE_PERSONAL_PORTFOLIO
      : SOURCE_TYPE_WECHAT_SHARE_CARD
    this.anonymousSessionId = isWechatTimelineSinglePage()
      ? createAnonymousSessionId()
      : ''
    this.setData({
      shareCode,
      showNavigationBack,
      timelineGuideRequested,
      timelineGuideVisible: false,
      timelineSharePortfolioId: timelineGuideRequested ? positiveId(options.sharePortfolioId) : 0,
      timelineShareRecordEnabled: false,
      visitorKey: ''
    })
    if (typeof wx !== 'undefined' && wx.showShareMenu) {
      wx.showShareMenu({ menus: SHARE_MENU_ITEMS })
    }
    return this.bootstrap()
  },

  async bootstrap() {
    if (!this.data.shareCode || this.visitPageUnloaded) {
      return
    }
    if (!this.browserContext || this.browserContext.isInvalid()) this.browserContext = createPortfolioVisitActivity(this)
    try {
      const response = await openVisitorSession(this.data.shareCode, {
        sourceType: this.visitorSourceType,
        anonymousSessionId: this.anonymousSessionId,
        browserContext: this.browserContext
      })
      this.applyVisitorOpenResponse(response)
    } catch (error) {
      if (this.visitPageUnloaded || error && error.contextDisposed) return
      if (this.browserContext) this.browserContext.setDisplayable(false)
      this.setData({ timelineGuideVisible: false, timelineShareRecordEnabled: false })
      wx.showToast({ title: error.message || '作品集加载失败', icon: 'none' })
    }
  },

  applyVisitorOpenResponse(response, preferredMenuKey = '') {
    const normalized = normalizeVisitorPortfolio(response)
    const portfolio = preferredMenuKey ? switchPortfolioMenu(normalized, preferredMenuKey) : normalized
    this.syncBackgroundAudio(portfolio.backgroundAudio, true)
    const displayable = !portfolio.underMaintenance && portfolio.components.length > 0
    this.setData({
      portfolio,
      visitorKey: portfolio.visitorKey || '',
      visitorProfileToken: portfolio.visitorProfileToken || '',
      timelineGuideVisible: Boolean(this.data.timelineGuideRequested && displayable),
      timelineShareRecordEnabled: Boolean(this.data.timelineSharePortfolioId && displayable),
      visitorProfileAuthVisible: Boolean(
        portfolio.needVisitorProfile && portfolio.visitorProfileToken && !portfolio.underMaintenance
      )
    }, () => {
      if (this.browserContext) {
        this.browserContext.setDisplayable(!portfolio.underMaintenance)
        if (this.singleWorkPageVisible) this.browserContext.show(this)
      }
    })
  },

  requestWithVisitorRefresh(requestOptions) {
    return requestWithVisitorSessionRefresh(requestOptions, {
      shareCode: this.data.shareCode,
      sourceType: this.visitorSourceType,
      anonymousSessionId: this.anonymousSessionId,
      browserContext: this.browserContext,
      onRefresh: this.browserContext ? undefined : (response) => this.applyVisitorOpenResponse(response, this.data.portfolio.activeMenuKey)
    })
  },

  handleContactInput(event) {
    const field = (event.detail && event.detail.field) || (event.currentTarget && event.currentTarget.dataset.field)
    if (!field) {
      return
    }
    const value = event.detail && Object.prototype.hasOwnProperty.call(event.detail, 'value')
      ? event.detail.value
      : ''
    const contactForm = Object.assign({}, this.data.contactForm, { [field]: value })
    this.setData({ contactForm: createContactLeadForm(contactForm) })
  },

  handleSubmitContact() {
    const validation = validateContactLeadForm(this.data.contactForm)
    if (!validation.valid) {
      wx.showToast({ title: validation.message, icon: 'none' })
      return
    }
    this.requestWithVisitorRefresh({
      url: `${VISITOR_PORTFOLIO_API_PREFIX}/${this.data.shareCode}/contact-leads/v2`,
      method: 'POST',
      authMode: 'visitor',
      data: buildContactLeadPayload(this.data.contactForm, {
        visitorKey: this.data.visitorKey,
        visitRecordId: this.data.portfolio.visitRecordId,
        sourceType: this.visitorSourceType,
        idempotencyKey: idempotencyKey('lead')
      })
    }).then(() => {
      wx.showToast({ title: '已提交', icon: 'success' })
      this.setData({ contactForm: createContactLeadForm({}) })
      this.handleCloseContactFormModal()
    }).catch((error) => {
      wx.showToast({ title: error.message || '提交失败', icon: 'none' })
    })
  },

  handleOpenContactFormModal(event) {
    const componentKey = (event.detail && event.detail.componentKey) ||
      (event.currentTarget && event.currentTarget.dataset.componentKey)
    const component = findContactFormComponent(this.data.portfolio, componentKey)
    if (!component) {
      return
    }
    this.setData({
      contactFormModalVisible: true,
      activeContactFormComponent: component
    })
  },

  handleCloseContactFormModal() {
    this.setData({
      contactFormModalVisible: false,
      activeContactFormComponent: createActiveContactFormComponent()
    })
  },

  handlePreviewQr(event) {
    const data = readPortfolioRenderEventData(event)
    const url = data.qrUrl || data.url
    if (!url) {
      return Promise.resolve(false)
    }
    wx.previewImage({ current: url, urls: [url] })
    return this.recordQrEvent()
  },

  ensureClipboardPromptController() {
    if (!this.clipboardPromptController) {
      this.clipboardPromptController = createClipboardPromptController({
        onChange: (state) => {
          this.setData({
            clipboardPromptVisible: state.visible,
            clipboardPromptText: state.text
          })
        }
      })
    }
    return this.clipboardPromptController
  },

  handleHyperlinkTap(event) {
    const hyperlink = readPortfolioRenderEventData(event)
    if (hyperlink.actionType === 'INTERNAL_PORTFOLIO') {
      if (!hyperlink.targetAvailable || !hyperlink.targetShareCode) {
        wx.showToast({ title: '内容暂不可见', icon: 'none' })
        return Promise.resolve(false)
      }
      wx.navigateTo({
        url: `/pages/portfolios/visitor-portfolio/visitor-portfolio?shareCode=${encodeURIComponent(hyperlink.targetShareCode)}&sourceType=${SOURCE_TYPE_PERSONAL_PORTFOLIO}`
      })
      return Promise.resolve(true)
    }
    if (hyperlink.actionType !== 'EXTERNAL_LINK' || typeof hyperlink.externalContent !== 'string' || !hyperlink.externalContent) {
      wx.showToast({ title: '内容暂不可见', icon: 'none' })
      return Promise.resolve(false)
    }
    return new Promise((resolve) => {
      wx.setClipboardData({
        data: hyperlink.externalContent,
        success: () => {
          this.ensureClipboardPromptController().copied(hyperlink.promptText)
          resolve(true)
        },
        fail: () => {
          wx.showToast({ title: '复制失败，请重试', icon: 'none' })
          resolve(false)
        }
      })
    })
  },

  recordQrEvent() {
    if (!this.data.shareCode || !this.data.visitorKey) {
      return Promise.resolve(false)
    }
    return this.requestWithVisitorRefresh({
      url: `${VISITOR_PORTFOLIO_API_PREFIX}/${this.data.shareCode}/events`,
      method: 'POST',
      authMode: 'visitor',
      data: buildVisitorEventPayload({
        visitorKey: this.data.visitorKey,
        eventType: QR_CODE_INTERACTED_EVENT_TYPE,
        metadata: {
          action: QR_ACTION_PREVIEW
        }
      }, idempotencyKey('qr'))
    }).then(() => true).catch(() => false)
  },

  handleDisplayTagTap(event) {
    const data = readPortfolioRenderEventData(event)
    const componentKey = data.componentKey
    const groupKey = data.groupKey
    if (!componentKey) {
      return
    }
    this.setData({
      portfolio: switchDisplayGroup(this.data.portfolio, componentKey, groupKey)
    }, () => {
      markDisplaySwitching(this, componentKey)
    })
  },

  handleBottomNavChange(event) {
    const menuKey = event.detail && event.detail.menuKey
    return startPortfolioMenuTransition(this, menuKey, {
      onBeforeExit: () => {
        clearDisplaySwitchingTimer(this)
        this.invalidateSingleWorkInteraction()
        this.stopActiveSingleWorkVideo()
        this.clearVideoPreview()
      },
      exitPatch: {
        contactFormModalVisible: false,
        activeContactFormComponent: createActiveContactFormComponent(),
        displaySwitchingComponentKey: ''
      },
      switchPortfolio: switchPortfolioMenu
    })
  },

  onUnload() {
    this.videoPlayerDisposed = true
    this.visitPageUnloaded = true
    if (this.browserContext) this.browserContext.dispose()
    this.destroyBackgroundAudio()
    this.singleWorkPageVisible = false
    this.invalidateSingleWorkInteraction()
    clearPortfolioMenuTransitionTimers(this)
    clearDisplaySwitchingTimer(this)
    this.stopActiveSingleWorkVideo()
    if (this.clipboardPromptController) {
      this.clipboardPromptController.dispose()
      this.clipboardPromptController = null
    }
  },

  onHide() {
    if (this.browserContext) this.browserContext.hide(this)
    this.hideBackgroundAudio()
    this.singleWorkPageVisible = false
    this.invalidateSingleWorkInteraction()
    this.stopActiveSingleWorkVideo()
    if (this.clipboardPromptController) {
      this.clipboardPromptController.pause()
    }
  },

  onShow() {
    if (this.browserContext) this.browserContext.show(this)
    this.clearVideoPreview()
    this.showBackgroundAudio()
    this.singleWorkPageVisible = true
    if (this.clipboardPromptController) {
      this.clipboardPromptController.resume()
    }
  },

  onShareAppMessage() {
    return {
      title: this.data.portfolio.share.title || this.data.portfolio.title || '个人作品集',
      path: `/pages/portfolios/visitor-portfolio/visitor-portfolio?shareCode=${encodeURIComponent(this.data.shareCode)}`,
      imageUrl: this.data.portfolio.share.coverUrl
    }
  },

  onShareTimeline() {
    this.recordTimelineShare()
    return {
      title: this.data.portfolio.share.title || this.data.portfolio.title || '个人作品集',
      query: `shareCode=${encodeURIComponent(this.data.shareCode)}`,
      imageUrl: this.data.portfolio.share.coverUrl
    }
  },

  recordTimelineShare() {
    const portfolioId = this.data.timelineShareRecordEnabled
      ? positiveId(this.data.timelineSharePortfolioId)
      : 0
    if (!portfolioId) {
      return
    }
    this.setData({
      timelineSharePortfolioId: 0,
      timelineShareRecordEnabled: false
    })
    request({
      url: `${PORTFOLIOS_API_URL}/${portfolioId}/share-records`,
      method: 'POST',
      data: {
        shareChannel: SHARE_CHANNEL_WECHAT_TIMELINE,
        shareScene: SHARE_SCENE_PORTFOLIO_LIST
      }
    }).catch(() => {})
  },

  handleCloseTimelineGuide() {
    this.setData({
      timelineGuideRequested: false,
      timelineGuideVisible: false
    })
  },

  handleTimelineGuideBack() {
    if (hasPreviousPage()) {
      wx.navigateBack({ delta: 1 })
    }
  },

  handleWorkDetail(event) {
    const portfolio = this.data.portfolio
    const opened = openWorkDetail(this, {
      componentKey: event.detail && event.detail.componentKey,
      components: portfolio.activeComponents || portfolio.components,
      theme: portfolio.style,
      browserContext: this.browserContext,
      onWorkEvent: (work) => this.recordWorkEvent(work),
      route: WORK_DETAIL_ROUTE
    })
    if (opened) { this.pauseBackgroundAudio(); this.stopActiveSingleWorkVideo() }
    return opened
  },

  handleWorkTap(event) {
    const work = normalizeWorkTapDataset(readPortfolioRenderEventData(event))
    if (work.mediaType === MEDIA_TYPE_VIDEO) this.pauseBackgroundAudio()
    if (!work.previewUrl) {
      wx.showToast({
        title: work.mediaType === MEDIA_TYPE_VIDEO ? VIDEO_MISSING_MESSAGE : IMAGE_MISSING_MESSAGE,
        icon: 'none'
      })
      return Promise.resolve(false)
    }
    return this.recordWorkEvent(work)
      .then(() => this.openWorkMedia(work))
      .catch((error) => {
        wx.showToast({ title: error.message || '作品打开失败', icon: 'none' })
        return false
      })
  },

  handleSingleWorkTap(event) {
    const interactionRevision = this.beginSingleWorkInteraction()
    const data = readPortfolioRenderEventData(event)
    const work = normalizeSingleWorkTapDataset(data)
    if (work.mediaType === MEDIA_TYPE_VIDEO) this.pauseBackgroundAudio()
    const componentKey = data.componentKey || ''
    if (!work.previewUrl) {
      wx.showToast({
        title: work.mediaType === MEDIA_TYPE_VIDEO ? VIDEO_MISSING_MESSAGE : IMAGE_MISSING_MESSAGE,
        icon: 'none'
      })
      return Promise.resolve(false)
    }
    return this.recordWorkEvent(work)
      .then(() => {
        if (!this.isCurrentSingleWorkInteraction(interactionRevision)) {
          return false
        }
        return this.openSingleWorkMedia(work, componentKey)
      })
      .catch((error) => {
        if (!this.isCurrentSingleWorkInteraction(interactionRevision)) {
          return false
        }
        wx.showToast({ title: error.message || '作品打开失败', icon: 'none' })
        return false
      })
  },

  beginSingleWorkInteraction() {
    if (typeof this.singleWorkPageVisible !== 'boolean') {
      this.singleWorkPageVisible = true
    }
    this.singleWorkInteractionRevision = Number(this.singleWorkInteractionRevision || 0) + 1
    return this.singleWorkInteractionRevision
  },

  invalidateSingleWorkInteraction() {
    this.singleWorkInteractionRevision = Number(this.singleWorkInteractionRevision || 0) + 1
  },

  isCurrentSingleWorkInteraction(interactionRevision) {
    return this.singleWorkPageVisible !== false && interactionRevision === this.singleWorkInteractionRevision
  },

  openSingleWorkMedia(work, componentKey) {
    if (work.mediaType === MEDIA_TYPE_VIDEO) {
      this.pauseBackgroundAudio()
      this.stopActiveSingleWorkVideo()
      this.setData({ activeSingleWorkVideoKey: componentKey })
      return true
    }
    wx.previewImage({ current: work.previewUrl, urls: [work.previewUrl] })
    return true
  },

  stopActiveSingleWorkVideo() {
    const componentKey = this.data.activeSingleWorkVideoKey
    if (!componentKey) {
      return
    }
    const instances = typeof this.selectAllComponents === 'function'
      ? this.selectAllComponents('.portfolio-single-work-instance')
      : []
    const activeInstance = (instances || []).find((instance) => {
      return instance && instance.data && instance.data.componentKey === componentKey
    })
    if (activeInstance && typeof activeInstance.pauseVideo === 'function') {
      activeInstance.pauseVideo()
    }
    this.setData({ activeSingleWorkVideoKey: '' })
  },

  handleSingleWorkVideoError() {
    this.stopActiveSingleWorkVideo()
    wx.showToast({ title: '视频播放失败，请稍后重试', icon: 'none' })
  },

  recordWorkEvent(work) {
    if (!this.data.shareCode) {
      return Promise.resolve()
    }
    const eventType = work.mediaType === MEDIA_TYPE_VIDEO ? VIDEO_PLAYED_EVENT_TYPE : WORK_VIEWED_EVENT_TYPE
    return this.requestWithVisitorRefresh({
      url: `${VISITOR_PORTFOLIO_API_PREFIX}/${this.data.shareCode}/events`,
      method: 'POST',
      authMode: 'visitor',
      data: buildVisitorEventPayload({
        visitorKey: this.data.visitorKey,
        eventType,
        workId: work.workId,
        mediaType: work.mediaType,
        metadata: buildWorkEventMetadata(work)
      }, idempotencyKey('work'))
    })
  },

  recordVideoCarouselEvent(componentKey, work) {
    if (!this.data.shareCode) return Promise.resolve()
    return this.requestWithVisitorRefresh({
      url: `${VISITOR_PORTFOLIO_API_PREFIX}/${this.data.shareCode}/events`,
      method: 'POST',
      authMode: 'visitor',
      data: buildVisitorEventPayload({
        visitorKey: this.data.visitorKey,
        eventType: VIDEO_PLAYED_EVENT_TYPE,
        componentKey,
        workId: work.workId,
        mediaType: MEDIA_TYPE_VIDEO,
        durationSeconds: 0
      }, idempotencyKey('video-carousel'))
    })
  },

  openWorkMedia(work) {
    if (work.mediaType === MEDIA_TYPE_VIDEO) {
      return this.openVideoPreview(work)
    }
    wx.previewImage({ current: work.previewUrl, urls: [work.previewUrl] })
    return true
  },

  clearVideoPreview() {
    // 返回作品集或打开失败时，解除文字组件的背景视频暂停。
    if (this.data.videoPreviewVisible) this.setData({ videoPreviewVisible: false })
  },

  openVideoPreview(work = {}) {
    if (this.videoPlayerDisposed || this.data.videoPreviewVisible) return false
    this.pauseBackgroundAudio()
    const mediaUrl = work.mediaUrl || work.previewUrl || ''
    if (!mediaUrl) {
      wx.showToast({ title: VIDEO_MISSING_MESSAGE, icon: 'none' })
      return false
    }
    this.stopActiveSingleWorkVideo()
    this.setData({ videoPreviewVisible: true })
    return openVideoPlayer({ url: mediaUrl, poster: work.coverUrl || '', title: work.title || '',
      route: VIDEO_PLAYER_ROUTE, browserContext: this.browserContext || null }, wx)
      .then((opened) => {
        // 导航成功后来源页保持暂停，失败或返回作品集时才恢复背景视频。
        if (!opened && !this.videoPlayerDisposed) this.clearVideoPreview()
        return opened
      })
  },

  handleVideoCarouselPlay(event) {
    this.pauseBackgroundAudio()
    const detail = event && event.detail || {}
    const sourceWork = detail.work || {}
    const work = normalizeWorkTapDataset(sourceWork)
    if (!sourceWork.mediaUrl) {
      wx.showToast({ title: VIDEO_MISSING_MESSAGE, icon: 'none' })
      return Promise.resolve(false)
    }
    return this.recordVideoCarouselEvent(detail.componentKey || '', work)
      .then(() => this.openVideoPreview(Object.assign({}, work, {
        mediaUrl: sourceWork.mediaUrl
      })))
      .catch((error) => {
        wx.showToast({ title: error.message || '作品打开失败', icon: 'none' })
        return false
      })
  },

  handleVisitorProfileMaskTap() {
  },

  handleVisitorProfilePanelTap() {
  },

  handleVisitorProfileMaskTouchMove() {
  },

  handleVisitorAvatarChoose(event) {
    const avatarUrl = event.detail && event.detail.avatarUrl
    if (!avatarUrl) {
      return
    }
    this.setData({
      'visitorProfileForm.avatarUrl': avatarUrl,
      visitorProfileAvatarError: false
    })
  },

  handleVisitorNicknameInput(event) {
    const value = event.detail && Object.prototype.hasOwnProperty.call(event.detail, 'value')
      ? event.detail.value
      : ''
    const patch = {
      'visitorProfileForm.nickname': value
    }
    if (String(value).trim()) {
      patch.visitorProfileNicknameError = false
    }
    this.setData(patch)
  },

  applyVisitorProfileValidation(avatarError, nicknameError) {
    this.setData({
      visitorProfileAvatarError: avatarError,
      visitorProfileNicknameError: nicknameError,
      visitorProfileValidationShaking: false
    }, () => {
      if (avatarError || nicknameError) {
        this.setData({ visitorProfileValidationShaking: true })
      }
    })
  },

  handleVisitorProfileSkip() {
    this.setData({
      visitorProfileAuthVisible: false
    })
  },

  async handleVisitorProfileSubmit() {
    const form = this.data.visitorProfileForm || {}
    const avatarError = !form.avatarUrl
    const nicknameError = !String(form.nickname || '').trim()
    if (avatarError || nicknameError) {
      this.applyVisitorProfileValidation(avatarError, nicknameError)
      wx.showToast({ title: VISITOR_PROFILE_REQUIRED_MESSAGE, icon: 'none' })
      return
    }
    if (!this.data.visitorProfileToken) {
      this.setData({ visitorProfileAuthVisible: false })
      return
    }
    this.setData({ visitorProfileSaving: true })
    try {
      await uploadVisitorAvatarProfile({
        shareCode: this.data.shareCode,
        visitorProfileToken: this.data.visitorProfileToken,
        nickname: form.nickname,
        avatarFilePath: form.avatarUrl
      })
      this.setData({
        visitorProfileAuthVisible: false,
        visitorProfileSaving: false
      })
    } catch (error) {
      this.setData({ visitorProfileSaving: false })
      wx.showToast({ title: error.message || '资料保存失败', icon: 'none' })
    }
  }
})
