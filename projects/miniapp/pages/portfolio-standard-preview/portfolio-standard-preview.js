const { request } = require('../../utils/request')
const { createContactLeadForm } = require('../../utils/contact-lead')
const { normalizeVisitorPortfolio } = require('../../utils/visitor-portfolio')

const PORTFOLIO_API_PREFIX = '/api/mine/portfolios'

Page({
  data: {
    portfolioId: null,
    loading: false,
    errorMessage: '',
    portfolio: normalizeVisitorPortfolio({ renderData: { preview: true } }),
    contactForm: createContactLeadForm({})
  },

  onLoad(options = {}) {
    this.setData({ portfolioId: options.portfolioId || null })
    return this.bootstrap()
  },

  bootstrap() {
    if (!this.data.portfolioId) {
      this.setData({ loading: false, errorMessage: '' })
      return Promise.resolve()
    }
    this.setData({ loading: true, errorMessage: '' })
    return request({ url: `${PORTFOLIO_API_PREFIX}/${this.data.portfolioId}/preview` })
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
  }
})

function switchDisplayGroup(portfolio, componentKey, groupKey) {
  const sourcePortfolio = portfolio || {}
  const components = (Array.isArray(sourcePortfolio.components) ? sourcePortfolio.components : []).map((component) => {
    if (!component || component.componentKey !== componentKey) {
      return component
    }
    const groups = Array.isArray(component.groups) ? component.groups : []
    const activeGroup = groups.find((group) => group.groupKey === groupKey)
      || component.activeGroup
      || groups[0]
      || { groupKey: '', name: '', sortOrder: 0, works: [] }
    const displayTags = Array.isArray(component.displayTags)
      ? component.displayTags
      : groups.map((group) => ({ groupKey: group.groupKey, name: group.name, active: false }))
    return Object.assign({}, component, {
      activeGroupKey: activeGroup.groupKey,
      activeGroup,
      displayTags: displayTags.map((tag) => Object.assign({}, tag, {
        active: tag.groupKey === activeGroup.groupKey
      }))
    })
  })
  return Object.assign({}, sourcePortfolio, { components })
}
