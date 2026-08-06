const {
  MOCK_SCHEDULE_DATA,
  buildMockCalendarMonth,
  getMockPortfolioDraft,
  switchMockPortfolioMenu,
  showMockLoginRequiredToast
} = require('../utils/mock-experience')

const CONTACT_FORM_COMPONENT_TYPE = 'CONTACT_FORM'
const CALENDAR_DAY_FILLED_CLASS = 'filled'

/**
 * 隐藏预览月历中的真实档期标记，保持与访客页一致。
 *
 * @param {Object} calendarMonth 本地月历数据
 * @returns {Object} 不含档期标记的月历数据
 */
function hideMockScheduleHints(calendarMonth = {}) {
  const days = Array.isArray(calendarMonth.days) ? calendarMonth.days : []
  return Object.assign({}, calendarMonth, {
    days: days.map((day) => Object.assign({}, day, {
      colors: [],
      statusColors: [],
      markerColors: [],
      count: 0,
      countText: '',
      dayClass: String(day.dayClass || '')
        .split(/\s+/)
        .filter((className) => className && className !== CALENDAR_DAY_FILLED_CLASS)
        .join(' ')
    }))
  })
}

/**
 * 创建空的联系表单组件状态。
 *
 * @returns {{componentKey: string, contactForm: Object}} 空组件
 */
function createEmptyContactFormComponent() {
  return {
    componentKey: '',
    contactForm: {}
  }
}

/**
 * 从当前菜单的本地渲染组件中查找联系表单。
 *
 * @param {Object} portfolio Mock 作品集渲染数据
 * @param {string} componentKey 组件键
 * @returns {Object|null} 联系表单组件
 */
function findContactFormComponent(portfolio, componentKey) {
  const components = portfolio && Array.isArray(portfolio.activeComponents)
    ? portfolio.activeComponents
    : []
  return components.find((component) => {
    return component.componentType === CONTACT_FORM_COMPONENT_TYPE && component.componentKey === componentKey
  }) || null
}

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
    loading: false,
    errorMessage: '',
    portfolio: getMockPortfolioDraft().renderData,
    portfolioMenuTransitionClass: '',
    contactFormModalVisible: false,
    activeContactFormComponent: createEmptyContactFormComponent(),
    mockScheduleMonth: hideMockScheduleHints(buildMockCalendarMonth('', '')),
    mockSlotDefinitions: MOCK_SCHEDULE_DATA.slotDefinitions,
    contactForm: {
      contactName: '',
      phone: '',
      wechat: '',
      desiredSchedule: '',
      needs: ''
    },
    videoPreviewVisible: false,
    videoPreview: {
      title: '',
      src: '',
      poster: ''
    },
    activeSingleWorkVideoKey: '',
    portfolioScrollTop: 0
  },

  onShow() {
    this.stopActiveSingleWorkVideo()
    this.refreshPreview()
  },

  onHide() {
    this.stopActiveSingleWorkVideo()
    this.clearPortfolioMenuTransition()
  },

  onUnload() {
    this.stopActiveSingleWorkVideo()
    this.clearPortfolioMenuTransition()
  },

  refreshPreview() {
    try {
      this.setData({
        loading: false,
        errorMessage: '',
        portfolio: getMockPortfolioDraft().renderData
      })
    } catch (error) {
      this.setData({
        loading: false,
        errorMessage: error && error.message ? error.message : '本地预览加载失败'
      })
    }
  },

  handleRetryPreview() {
    this.setData({ loading: true, errorMessage: '' }, () => {
      this.refreshPreview()
    })
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
    this.clearPortfolioMenuTransition()
    const items = this.data.portfolio.bottomNav && this.data.portfolio.bottomNav.items
      ? this.data.portfolio.bottomNav.items
      : []
    const currentIndex = items.findIndex((item) => item.key === this.data.portfolio.activeMenuKey)
    const targetIndex = items.findIndex((item) => item.key === menuKey)
    const direction = targetIndex > currentIndex ? 'forward' : 'backward'
    this.setData({
      portfolio: switchMockPortfolioMenu(this.data.portfolio, menuKey),
      portfolioMenuTransitionClass: `portfolio-menu-enter-${direction}`,
      portfolioScrollTop: 1,
      contactFormModalVisible: false,
      activeContactFormComponent: createEmptyContactFormComponent(),
      videoPreviewVisible: false,
      videoPreview: {
        title: '',
        src: '',
        poster: ''
      }
    }, () => {
      this.setData({ portfolioScrollTop: 0 })
      this.portfolioMenuTransitionTimer = setTimeout(() => {
        this.portfolioMenuTransitionTimer = null
        this.setData({ portfolioMenuTransitionClass: '' })
      }, 220)
    })
  },

  clearPortfolioMenuTransition() {
    if (this.portfolioMenuTransitionTimer) {
      clearTimeout(this.portfolioMenuTransitionTimer)
      this.portfolioMenuTransitionTimer = null
    }
  },

  handleSubmitContact() {
    showMockLoginRequiredToast()
    this.setData({
      contactFormModalVisible: false,
      activeContactFormComponent: createEmptyContactFormComponent()
    })
  },

  handleMockScheduleMonthChange(event) {
    const yearMonth = event.detail && event.detail.yearMonth
    if (!yearMonth) {
      return
    }
    this.setData({
      mockScheduleMonth: hideMockScheduleHints(buildMockCalendarMonth(yearMonth, ''))
    })
  },

  handleOpenContactFormModal(event) {
    const componentKey = event.detail && event.detail.componentKey
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
      activeContactFormComponent: createEmptyContactFormComponent()
    })
  },

  handleContactInput(event) {
    const field = (event.detail && event.detail.field) ||
      (event.currentTarget && event.currentTarget.dataset.field)
    if (!Object.prototype.hasOwnProperty.call(this.data.contactForm, field)) {
      return
    }
    const value = event.detail && Object.prototype.hasOwnProperty.call(event.detail, 'value')
      ? event.detail.value
      : ''
    this.setData({ contactForm: Object.assign({}, this.data.contactForm, { [field]: value }) })
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
