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
const { buildVisitorEventPayload, normalizeVisitorPortfolio, switchDisplayGroup } = require('../../../utils/visitor-portfolio')
const { uploadVisitorAvatarProfile } = require('../utils/visitor-profile')
const { request } = require('../../../utils/request')
const {
  SOURCE_TYPE_WECHAT_SHARE_CARD,
  openVisitorSession,
  requestWithVisitorSessionRefresh
} = require('../../../utils/visitor-session')
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
const DEFAULT_VIDEO_TITLE = '视频作品'
const VISITOR_PROFILE_REQUIRED_MESSAGE = '请完善头像和昵称'
const TIMELINE_SHARE_GUIDE_VALUE = 'timeline'
const SHARE_MENU_ITEMS = Object.freeze(['shareAppMessage', 'shareTimeline'])
const PORTFOLIOS_API_URL = '/api/mine/portfolios'
const SHARE_CHANNEL_WECHAT_TIMELINE = 'WECHAT_TIMELINE'
const SHARE_SCENE_PORTFOLIO_LIST = 'PORTFOLIO_LIST'

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
  data: {
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
    videoPreview: null,
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
    displaySwitchingComponentKey: ''
  },

  onLoad(options = {}) {
    this.singleWorkPageVisible = true
    this.singleWorkInteractionRevision = 0
    const shareCode = options.shareCode || options.scene || ''
    const showNavigationBack = hasPreviousPage()
    const timelineGuideRequested = options.shareGuide === TIMELINE_SHARE_GUIDE_VALUE
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
    if (!this.data.shareCode) {
      return
    }
    try {
      const response = await openVisitorSession(this.data.shareCode, {
        sourceType: SOURCE_TYPE_WECHAT_SHARE_CARD,
        anonymousSessionId: this.anonymousSessionId
      })
      this.applyVisitorOpenResponse(response)
    } catch (error) {
      this.setData({ timelineGuideVisible: false, timelineShareRecordEnabled: false })
      wx.showToast({ title: error.message || '作品集加载失败', icon: 'none' })
    }
  },

  applyVisitorOpenResponse(response) {
    const portfolio = normalizeVisitorPortfolio(response)
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
    })
  },

  requestWithVisitorRefresh(requestOptions) {
    return requestWithVisitorSessionRefresh(requestOptions, {
      shareCode: this.data.shareCode,
      sourceType: SOURCE_TYPE_WECHAT_SHARE_CARD,
      anonymousSessionId: this.anonymousSessionId,
      onRefresh: (response) => this.applyVisitorOpenResponse(response)
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
      url: `${VISITOR_PORTFOLIO_API_PREFIX}/${this.data.shareCode}/contact-leads`,
      method: 'POST',
      authMode: 'visitor',
      data: buildContactLeadPayload(this.data.contactForm, {
        visitorKey: this.data.visitorKey,
        visitRecordId: this.data.portfolio.visitRecordId,
        sourceType: SOURCE_TYPE_WECHAT_SHARE_CARD,
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

  onUnload() {
    this.singleWorkPageVisible = false
    this.invalidateSingleWorkInteraction()
    clearDisplaySwitchingTimer(this)
    this.stopActiveSingleWorkVideo()
  },

  onHide() {
    this.singleWorkPageVisible = false
    this.invalidateSingleWorkInteraction()
    this.stopActiveSingleWorkVideo()
  },

  onShow() {
    this.singleWorkPageVisible = true
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

  handleWorkTap(event) {
    const work = normalizeWorkTapDataset(readPortfolioRenderEventData(event))
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

  openWorkMedia(work) {
    if (work.mediaType === MEDIA_TYPE_VIDEO) {
      this.setData({
        videoPreviewVisible: true,
        videoPreview: {
          src: work.previewUrl,
          poster: work.coverUrl,
          title: work.title || DEFAULT_VIDEO_TITLE
        }
      })
      return true
    }
    wx.previewImage({ current: work.previewUrl, urls: [work.previewUrl] })
    return true
  },

  handleCloseVideoPreview() {
    this.setData({
      videoPreviewVisible: false,
      videoPreview: null
    })
  },

  handleVideoPreviewPanelTap() {
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
