const {
  getMockPortfolioDraft,
  switchMockPortfolioMenu,
  showMockLoginRequiredToast
} = require('../utils/mock-experience')

function readMockComponentEventData(event = {}) {
  const dataset = event.currentTarget && event.currentTarget.dataset
  const detail = event.detail
  return Object.assign(
    {},
    dataset && typeof dataset === 'object' ? dataset : {},
    detail && typeof detail === 'object' ? detail : {}
  )
}

function collectImageUrls(portfolio) {
  const components = portfolio && Array.isArray(portfolio.activeComponents)
    ? portfolio.activeComponents
    : []
  return components.reduce((result, component) => {
    if (component.componentType === 'CAROUSEL') {
      component.works.forEach((work) => {
        if (work.mediaType === 'IMAGE') {
          result.push(work.mediaUrl)
        }
      })
    }
    if (component.componentType === 'WORK_GRID' || component.componentType === 'WORK_LIST') {
      component.groups.forEach((group) => {
        group.works.forEach((work) => {
          if (work.mediaType === 'IMAGE') {
            result.push(work.mediaUrl)
          }
        })
      })
    }
    return result
  }, [])
}

Page({
  data: {
    portfolio: getMockPortfolioDraft().renderData,
    videoPreviewVisible: false,
    videoPreview: {
      title: '',
      src: '',
      poster: ''
    },
    activeSingleWorkVideoKey: ''
    ,
    portfolioScrollTop: 0
  },

  onShow() {
    this.stopActiveSingleWorkVideo()
    this.setData({
      portfolio: getMockPortfolioDraft().renderData
    })
  },

  onHide() {
    this.stopActiveSingleWorkVideo()
  },

  onUnload() {
    this.stopActiveSingleWorkVideo()
  },

  handleWorkTap(event) {
    const eventData = readMockComponentEventData(event)
    const mediaType = eventData.mediaType
    const mediaUrl = eventData.mediaUrl
    const coverUrl = eventData.coverUrl
    const title = eventData.title || ''
    if (!mediaUrl) {
      return
    }
    if (mediaType === 'VIDEO') {
      this.setData({
        videoPreviewVisible: true,
        videoPreview: {
          title,
          src: mediaUrl,
          poster: coverUrl
        }
      })
      return
    }
    wx.previewImage({
      current: mediaUrl,
      urls: collectImageUrls(this.data.portfolio)
    })
  },

  handleSingleWorkTap(event) {
    const eventData = readMockComponentEventData(event)
    const componentKey = eventData.componentKey || ''
    const mediaType = eventData.mediaType
    const mediaUrl = eventData.mediaUrl
    if (!mediaUrl) {
      return false
    }
    if (mediaType === 'VIDEO') {
      this.stopActiveSingleWorkVideo()
      this.setData({ activeSingleWorkVideoKey: componentKey })
      return true
    }
    wx.previewImage({
      current: mediaUrl,
      urls: [mediaUrl]
    })
    return true
  },

  stopActiveSingleWorkVideo() {
    const componentKey = this.data.activeSingleWorkVideoKey
    if (!componentKey) {
      return
    }
    const videoContext = wx.createVideoContext && wx.createVideoContext(`singleWorkVideo-${componentKey}`, this)
    if (videoContext && videoContext.pause) {
      videoContext.pause()
    }
    this.setData({ activeSingleWorkVideoKey: '' })
  },

  handleSingleWorkVideoError() {
    this.stopActiveSingleWorkVideo()
    wx.showToast({ title: '视频播放失败，请稍后重试', icon: 'none' })
  },

  handleCloseVideoPreview() {
    this.setData({
      videoPreviewVisible: false,
      videoPreview: {
        title: '',
        src: '',
        poster: ''
      }
    })
  },

  handleDisplayTagTap(event) {
    const eventData = readMockComponentEventData(event)
    const componentKey = eventData.componentKey
    const groupKey = eventData.groupKey
    const components = this.data.portfolio.activeComponents.map((component) => {
      if (component.componentKey !== componentKey || !Array.isArray(component.groups)) {
        return component
      }
      const activeGroup = component.groups.find((group) => group.groupKey === groupKey) || component.activeGroup
      return Object.assign({}, component, {
        activeGroup,
        displayTags: component.displayTags.map((tag) => Object.assign({}, tag, {
          active: tag.groupKey === groupKey
        }))
      })
    })
    const portfolio = Object.assign({}, this.data.portfolio, { activeComponents: components })
    if (portfolio.bottomNav.enabled && portfolio.activeMenuKey !== portfolio.bottomNav.items[0].key) {
      portfolio.bottomNav = Object.assign({}, portfolio.bottomNav, {
        items: portfolio.bottomNav.items.map((item) => item.key === portfolio.activeMenuKey
          ? Object.assign({}, item, { components })
          : item)
      })
    } else {
      portfolio.components = components
    }
    this.setData({ portfolio })
  },

  handlePortfolioMenuChange(event) {
    const menuKey = event && event.detail && event.detail.menuKey
    if (!menuKey || menuKey === this.data.portfolio.activeMenuKey) {
      return
    }
    this.stopActiveSingleWorkVideo()
    this.setData({
      portfolio: switchMockPortfolioMenu(this.data.portfolio, menuKey),
      portfolioScrollTop: 1,
      videoPreviewVisible: false,
      videoPreview: {
        title: '',
        src: '',
        poster: ''
      }
    }, () => {
      this.setData({ portfolioScrollTop: 0 })
    })
  },

  handleSubmitContact() {
    showMockLoginRequiredToast()
  },

  handleOpenScheduleQuery() {
    showMockLoginRequiredToast()
  },

  handlePreviewQr(event) {
    const eventData = readMockComponentEventData(event)
    const url = eventData.qrUrl || eventData.url
    if (!url) {
      return
    }
    wx.previewImage({
      current: url,
      urls: [url]
    })
  },

  noop() {}
})
