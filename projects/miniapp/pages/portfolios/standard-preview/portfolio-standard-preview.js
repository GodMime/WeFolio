const { request } = require('../../../utils/request')
const { createContactLeadForm } = require('../utils/contact-lead')
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
const { normalizeVisitorPortfolio, switchDisplayGroup } = require('../../../utils/visitor-portfolio')

const PORTFOLIO_API_PREFIX = '/api/mine/portfolios'
const TEAM_PORTFOLIO_API_PREFIX = '/api/mine/team-portfolios'
const PUBLISHED_PREVIEW_SCOPE = 'published'
const DRAFT_PREVIEW_SCOPE = 'draft'
const MEDIA_TYPE_VIDEO = 'VIDEO'
const IMAGE_MISSING_MESSAGE = '图片地址缺失'
const VIDEO_MISSING_MESSAGE = '视频地址缺失'
const DEFAULT_VIDEO_TITLE = '视频作品'

Page({
  data: {
    portfolioId: null,
    previewScope: '',
    teamPortfolioId: 0,
    teamPreviewScope: '',
    loading: false,
    errorMessage: '',
    portfolio: normalizeVisitorPortfolio({ renderData: { preview: true } }),
    contactForm: createContactLeadForm({}),
    contactFormModalVisible: false,
    activeContactFormComponent: createActiveContactFormComponent(),
    videoPreviewVisible: false,
    videoPreview: null,
    activeSingleWorkVideoKey: '',
    displaySwitchingComponentKey: ''
  },

  onLoad(options = {}) {
    const teamPortfolioId = Number(options.teamPortfolioId) || 0
    const teamPreviewScope = teamPortfolioId && options.teamScope === PUBLISHED_PREVIEW_SCOPE
      ? PUBLISHED_PREVIEW_SCOPE
      : teamPortfolioId ? DRAFT_PREVIEW_SCOPE : ''
    this.setData({
      portfolioId: options.portfolioId || null,
      previewScope: teamPortfolioId ? PUBLISHED_PREVIEW_SCOPE : options.scope || '',
      teamPortfolioId,
      teamPreviewScope
    })
    return this.bootstrap()
  },

  bootstrap() {
    if (!this.data.portfolioId) {
      this.setData({ loading: false, errorMessage: '' })
      return Promise.resolve()
    }
    this.setData({ loading: true, errorMessage: '' })
    if (this.data.teamPortfolioId) {
      return request({
        url: `${TEAM_PORTFOLIO_API_PREFIX}/${this.data.teamPortfolioId}/member-portfolios/${this.data.portfolioId}/published-preview`,
        data: { scope: this.data.teamPreviewScope }
      }).then((response) => {
        this.setData({
          portfolio: normalizeVisitorPortfolio(response),
          loading: false,
          errorMessage: ''
        })
      }).catch((error) => {
        this.setData({
          loading: false,
          errorMessage: error.message || '预览加载失败'
        })
      })
    }
    const previewPath = this.data.previewScope === PUBLISHED_PREVIEW_SCOPE ? 'published-preview' : 'preview'
    return request({ url: `${PORTFOLIO_API_PREFIX}/${this.data.portfolioId}/${previewPath}` })
      .then((response) => {
        this.setData({
          portfolio: normalizeVisitorPortfolio(response),
          loading: false,
          errorMessage: ''
        })
      })
      .catch((error) => {
        this.setData({
          loading: false,
          errorMessage: error.message || '预览加载失败'
        })
      })
  },

  handleRetryPreview() {
    return this.bootstrap()
  },

  handlePreviewQr(event) {
    const data = readPortfolioRenderEventData(event)
    const url = data.qrUrl || data.url
    if (!url) {
      return
    }
    wx.previewImage({ current: url, urls: [url] })
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
    clearDisplaySwitchingTimer(this)
    this.stopActiveSingleWorkVideo()
  },

  onHide() {
    this.stopActiveSingleWorkVideo()
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
    wx.showToast({ title: '预览模式不提交', icon: 'none' })
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

  handleWorkTap(event) {
    const work = normalizeWorkTapDataset(readPortfolioRenderEventData(event))
    return this.openWorkMedia(work)
  },

  handleSingleWorkTap(event) {
    const data = readPortfolioRenderEventData(event)
    const work = normalizeSingleWorkTapDataset(data)
    const componentKey = data.componentKey || ''
    if (work.mediaType !== MEDIA_TYPE_VIDEO) {
      return this.openSingleWorkImage(work)
    }
    if (!work.previewUrl) {
      wx.showToast({ title: VIDEO_MISSING_MESSAGE, icon: 'none' })
      return false
    }
    this.stopActiveSingleWorkVideo()
    this.setData({ activeSingleWorkVideoKey: componentKey })
    return true
  },

  openSingleWorkImage(work) {
    if (!work.previewUrl) {
      wx.showToast({ title: IMAGE_MISSING_MESSAGE, icon: 'none' })
      return false
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

  openWorkMedia(work) {
    if (work.mediaType === MEDIA_TYPE_VIDEO) {
      if (!work.previewUrl) {
        wx.showToast({ title: VIDEO_MISSING_MESSAGE, icon: 'none' })
        return Promise.resolve(false)
      }
      this.setData({
        videoPreviewVisible: true,
        videoPreview: {
          src: work.previewUrl,
          poster: work.coverUrl,
          title: work.title || DEFAULT_VIDEO_TITLE
        }
      })
      return Promise.resolve(true)
    }
    if (!work.previewUrl) {
      wx.showToast({ title: IMAGE_MISSING_MESSAGE, icon: 'none' })
      return Promise.resolve(false)
    }
    wx.previewImage({ current: work.previewUrl, urls: [work.previewUrl] })
    return Promise.resolve(true)
  },

  handleCloseVideoPreview() {
    this.setData({
      videoPreviewVisible: false,
      videoPreview: null
    })
  },

  handleVideoPreviewPanelTap() {
  }
})
