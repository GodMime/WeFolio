const { request } = require('../../utils/request')
const { createContactLeadForm } = require('../../utils/contact-lead')
const { normalizeVisitorPortfolio, switchDisplayGroup } = require('../../utils/visitor-portfolio')

const PORTFOLIO_API_PREFIX = '/api/mine/portfolios'
const PUBLISHED_PREVIEW_SCOPE = 'published'
const MEDIA_TYPE_VIDEO = 'VIDEO'
const IMAGE_MISSING_MESSAGE = '图片地址缺失'
const VIDEO_MISSING_MESSAGE = '视频地址缺失'
const DEFAULT_VIDEO_TITLE = '视频作品'

Page({
  data: {
    portfolioId: null,
    previewScope: '',
    loading: false,
    errorMessage: '',
    portfolio: normalizeVisitorPortfolio({ renderData: { preview: true } }),
    contactForm: createContactLeadForm({}),
    videoPreviewVisible: false,
    videoPreview: null
  },

  onLoad(options = {}) {
    this.setData({
      portfolioId: options.portfolioId || null,
      previewScope: options.scope || ''
    })
    return this.bootstrap()
  },

  bootstrap() {
    if (!this.data.portfolioId) {
      this.setData({ loading: false, errorMessage: '' })
      return Promise.resolve()
    }
    this.setData({ loading: true, errorMessage: '' })
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
    const url = event.currentTarget.dataset.url
    if (!url) {
      return
    }
    wx.previewImage({ current: url, urls: [url] })
  },

  handleDisplayTagTap(event) {
    const componentKey = event.currentTarget.dataset.componentKey
    const groupKey = event.currentTarget.dataset.groupKey
    this.setData({
      portfolio: switchDisplayGroup(this.data.portfolio, componentKey, groupKey)
    })
  },

  handleContactInput(event) {
    const field = event.currentTarget.dataset.field
    const contactForm = Object.assign({}, this.data.contactForm, { [field]: event.detail.value })
    this.setData({ contactForm: createContactLeadForm(contactForm) })
  },

  handleSubmitContact() {
    wx.showToast({ title: '预览模式不提交', icon: 'none' })
  },

  handleWorkTap(event) {
    const work = normalizeWorkTapDataset(event.currentTarget.dataset)
    return this.openWorkMedia(work)
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

function normalizeWorkTapDataset(dataset = {}) {
  const previewUrl = dataset.mediaUrl || dataset.previewUrl || dataset.coverUrl || ''
  return {
    workId: Number(dataset.workId),
    mediaType: dataset.mediaType || '',
    previewUrl,
    coverUrl: dataset.coverUrl || '',
    title: dataset.title || ''
  }
}
