const {
  getMockPortfolioDraft,
  showMockLoginRequiredToast
} = require('../utils/mock-experience')

function collectImageUrls(portfolio) {
  const components = portfolio && Array.isArray(portfolio.components) ? portfolio.components : []
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
    const mediaType = event.currentTarget.dataset.mediaType
    const mediaUrl = event.currentTarget.dataset.mediaUrl
    const coverUrl = event.currentTarget.dataset.coverUrl
    const title = event.currentTarget.dataset.title || ''
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
    const componentKey = event.currentTarget.dataset.componentKey || ''
    const mediaType = event.currentTarget.dataset.mediaType
    const mediaUrl = event.currentTarget.dataset.mediaUrl
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
    const componentKey = event.currentTarget.dataset.componentKey
    const groupKey = event.currentTarget.dataset.groupKey
    const components = this.data.portfolio.components.map((component) => {
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
    this.setData({
      portfolio: Object.assign({}, this.data.portfolio, { components })
    })
  },

  handleSubmitContact() {
    showMockLoginRequiredToast()
  },

  handleOpenScheduleQuery() {
    showMockLoginRequiredToast()
  },

  handlePreviewQr(event) {
    const url = event.currentTarget.dataset.url
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
