const { createPortfolioOpening } = require('../utils/portfolio-opening')
const {
  MOCK_SCHEDULE_DATA,
  MOCK_WORK_LIBRARY,
  buildMockPortfolioRenderData,
  buildMockCalendarMonth,
  getMockPortfolioDraft,
  getMockMenuComponents,
  switchMockPortfolioMenu,
  showMockLoginRequiredToast
} = require('../utils/mock-experience')

const { createMockAudioController, resolveMockBackgroundAudio } = require('../utils/mock-portfolio-audio')
const { createMockCopyController, resolveMockHyperlinkTarget, getMockHyperlinkTargetUrl } = require('../utils/mock-portfolio-hyperlink')
const { createMockFontSession, collectMockFontNodes } = require('../utils/mock-portfolio-fonts')
const { registerMockTextFont } = require('../utils/mock-portfolio-text')
const { buildMockGridViewModel } = require('../utils/mock-portfolio-text-grid')
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
    mediaActive: true,
    audioPlaying: false,
    audioResource: null,
    copiedComponentKey: '',
    copiedField: '',
    hyperlinkPrompt: '',
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

  onLoad(options = {}) {
    this.mockOpening = createPortfolioOpening(this, { onTimeout: () => {
      this.mockFontFallback = true
      if (this.mockFontSession) this.mockFontSession.destroy()
      this.refreshMockFontRender()
    } })
    this.mockFontSession=createMockFontSession({wxApi:wx})
    registerMockTextFont(wx)
    this.demoTarget = options.demoTarget || ''
    this.copyController = createMockCopyController({wxApi:wx,
      onContactState:field=>this.setData({copiedField:field}),
      onPrompt:prompt=>this.setData({hyperlinkPrompt:prompt}),
      onError:title=>wx.showToast({title,icon:'none'})})
    this.mockAudio = createMockAudioController({wxApi:wx,
      beforePlay:()=>{this.stopActiveSingleWorkVideo();this.handleCloseVideoPreview()},
      onPlaying:playing=>this.setData({audioPlaying:playing}),
      onError:()=>wx.showToast({title:'音频播放失败，请点击重试',icon:'none'})})
  },

  onShow() {
    this.setData({mediaActive:true})
    if(this.copyController) this.copyController.show()
    if(this.mockAudio) this.mockAudio.show()
    if(this.mockOpening) this.mockOpening.show()
    this.stopActiveSingleWorkVideo()
    if (!this.previewLoaded) this.refreshPreview()
  },

  onHide() {
    if(this.mockOpening) this.mockOpening.hide()
    this.setData({mediaActive:false})
    if(this.mockAudio) this.mockAudio.hide()
    if(this.copyController) this.copyController.hide()
    this.handleCloseVideoPreview()
    this.stopActiveSingleWorkVideo()
    this.clearPortfolioMenuTransition()
  },

  onUnload() {
    if(this.mockOpening) this.mockOpening.dispose()
    this.mockOpening = null
    if(this.mockFontSession)this.mockFontSession.destroy()
    if(this.mockAudio) this.mockAudio.destroy()
    if(this.copyController) this.copyController.destroy()
    this.handleCloseVideoPreview()
    this.stopActiveSingleWorkVideo()
    this.clearPortfolioMenuTransition()
  },

  refreshPreview() {
    try {
      let portfolio
      if (this.demoTarget) {
        const target = resolveMockHyperlinkTarget(this.demoTarget)
        if (!target) throw new Error('演示作品集暂不可用')
        this.mockFontConfig=target.config
        portfolio = buildMockPortfolioRenderData(target.config,this.mockFontSession && this.mockFontSession.context(target.config.fonts))
      } else {
        const draft = getMockPortfolioDraft()
        this.mockFontConfig=draft.config
        portfolio = buildMockPortfolioRenderData(draft.config,this.mockFontSession && this.mockFontSession.context(draft.config.fonts))
        if(draft.draftWarning) wx.showToast({title:draft.draftWarning,icon:'none'})
      }
      this.previewLoaded = true
      const resource = resolveMockBackgroundAudio(portfolio.backgroundAudio,MOCK_WORK_LIBRARY.works)
      this.beginMockFontOpening(portfolio.activeMenuKey)
      this.setData({loading:false,errorMessage:'',portfolio,audioResource:resource})
      this.loadMockOpeningFonts()
      if(this.mockAudio) {
        this.mockAudio.setResource(resource)
        if(!this.mockOpening) this.onPortfolioFontsReady()
      }
    } catch (error) {
      if(this.mockOpening) this.mockOpening.cancel()
      this.setData({
        loading: false,
        errorMessage: error && error.message ? error.message : '本地预览加载失败'
      })
    }
  },

  blockPortfolioOpeningTouch() {},

  onPortfolioFontsReady() {
    if(this.mockAudio && !this.audioAttempted && this.data.audioResource) {
      this.audioAttempted=true
      this.mockAudio.play()
    }
  },

  beginMockFontOpening(menuKey) {
    if(!this.mockOpening || !this.mockFontConfig)return
    const config={fonts:this.mockFontConfig.fonts,components:getMockMenuComponents(this.mockFontConfig,menuKey)}
    this.mockOpeningTicket=this.mockOpening.begin(!this.mockFontFallback && collectMockFontNodes(config).length > 0)
  },

  loadMockOpeningFonts() {
    if(!this.mockFontSession || !this.mockFontConfig)return
    const ticket=this.mockOpeningTicket
    const config={fonts:this.mockFontConfig.fonts,components:getMockMenuComponents(this.mockFontConfig,this.data.portfolio.activeMenuKey)}
    this.mockFontSession.load(config).then(()=>{
      if(!this.mockOpening)return
      if(this.mockFontFallback){this.mockOpening.commit(ticket,{});return}
      if(ticket !== this.mockOpeningTicket)return
      this.refreshMockFontRender()
      this.mockOpening.commit(ticket,{})
    })
  },

  refreshMockFontRender() {
    if(!this.mockFontConfig || !this.mockFontSession)return
    const current=this.data.portfolio
    if(!current)return
    const rendered=buildMockPortfolioRenderData(this.mockFontConfig,this.mockFontSession.context(this.mockFontConfig.fonts))
    const components=[...rendered.components,...rendered.bottomNav.items.flatMap(item=>item.components || [])]
    const byKey=new Map(components.filter(item=>['TEXT_SECTION','STRUCTURED_TEXT_SECTION','TEXT_GRID'].includes(item.componentType)).map(item=>[item.componentKey,item]))
    // 字体回调只更新文字节点，不覆盖用户刚切换的菜单、作品分组及播放状态。
    const update=list=>(list || []).map(item=>byKey.has(item.componentKey)?{...item,viewModel:byKey.get(item.componentKey).viewModel,...(item.textSection?{textSection:byKey.get(item.componentKey).textSection}:{})}:item)
    this.setData({portfolio:{...current,components:update(current.components),activeComponents:update(current.activeComponents),
      bottomNav:{...current.bottomNav,items:current.bottomNav.items.map(item=>item.components?{...item,components:update(item.components)}:item)}}})
  },

  handleRetryPreview() {
    this.setData({ loading: true, errorMessage: '' }, () => {
      this.refreshPreview()
    })
  },

  handleWorkTap(event) {
    const data = readMockComponentEventData(event)
    const work = this.findEventWork(data)
    if (!work) return
    if (this.mockAudio) this.mockAudio.pause()
    this.stopActiveSingleWorkVideo()
    if (work.mediaType === 'VIDEO') {
      this.setData({videoPreviewVisible:true,videoPreview:{title:work.title,src:work.mediaUrl,poster:work.coverUrl || ''}})
      return
    }
    if(['IMAGE','ANIMATION'].includes(work.mediaType)) wx.previewImage({current:work.mediaUrl,urls:[work.mediaUrl]})
  },

  handleSingleWorkTap(event) {
    const data = readMockComponentEventData(event)
    const work = this.findEventWork(data)
    if (!work) return false
    if (work.mediaType === 'VIDEO') {
      if(this.mockAudio) this.mockAudio.pause()
      this.handleCloseVideoPreview()
      this.stopActiveSingleWorkVideo()
      this.setData({activeSingleWorkVideoKey:data.componentKey})
      return true
    }
    if(['IMAGE','ANIMATION'].includes(work.mediaType)) wx.previewImage({current:work.mediaUrl,urls:[work.mediaUrl]})
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
    if(this.mockAudio) this.mockAudio.pause()
    if(this.copyController) {this.copyController.destroy();this.copyController=null}
    this.clearPortfolioMenuTransition()
    const items = this.data.portfolio.bottomNav && this.data.portfolio.bottomNav.items
      ? this.data.portfolio.bottomNav.items
      : []
    const currentIndex = items.findIndex((item) => item.key === this.data.portfolio.activeMenuKey)
    const targetIndex = items.findIndex((item) => item.key === menuKey)
    const direction = targetIndex > currentIndex ? 'forward' : 'backward'
    this.beginMockFontOpening(menuKey)
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
      this.loadMockOpeningFonts()
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

  findEventWork(data) {
    const components=this.data.portfolio.activeComponents || []
    const component=components.find(item=>item.componentKey === data.componentKey)
    if(!component) return null
    const works=component.work ? [component.work] : component.works || (component.groups || []).flatMap(group=>group.works || [])
    const work=works.find(item=>Number(item.workId) === Number(data.workId))
    return work || null
  },

  handleToggleAudio() { if(this.mockAudio) this.mockAudio.toggle() },

  handleVideoError() {
    this.handleCloseVideoPreview()
    wx.showToast({title:'视频播放失败，请稍后重试',icon:'none'})
  },

  ensureCopyController() {
    if(!this.copyController) this.copyController=createMockCopyController({wxApi:wx,
      onContactState:field=>this.setData({copiedField:field}),onPrompt:prompt=>this.setData({hyperlinkPrompt:prompt}),onError:title=>wx.showToast({title,icon:'none'})})
    return this.copyController
  },

  handleCopyContact(event) {
    const {componentKey,field}=event.detail
    const component=this.data.portfolio.activeComponents.find(item=>item.componentKey === componentKey && item.componentType === 'CONTACT_INFO')
    if(!component || !['contactPhone','contactWechat'].includes(field)) return
    this.setData({copiedComponentKey:componentKey})
    this.ensureCopyController().copyContact(field,component.config[field])
  },

  handleHyperlink(event) {
    const component=this.data.portfolio.activeComponents.find(item=>item.componentKey === event.detail.componentKey && item.componentType === 'HYPERLINK')
    if(!component) return
    if(component.config.actionType === 'EXTERNAL_LINK') {this.ensureCopyController().copyHyperlink(component.config);return}
    const url=getMockHyperlinkTargetUrl(component.config.targetPortfolioId)
    if(!url) {wx.showToast({title:'演示作品集暂不可用',icon:'none'});return}
    if(this.mockAudio) this.mockAudio.pause()
    this.stopActiveSingleWorkVideo()
    this.handleCloseVideoPreview()
    wx.navigateTo({url})
  },

  handleGridMeasure(event) {
    const {componentKey,widthPx,heightsPx}=event.detail
    const component=this.data.portfolio.activeComponents.find(item=>item.componentKey === componentKey && item.componentType === 'TEXT_GRID')
    if(!component || !widthPx) return
    const info=wx.getWindowInfo ? wx.getWindowInfo() : {windowWidth:375}
    const scale=750/info.windowWidth
    const heights={}
    Object.keys(heightsPx || {}).forEach(key=>{heights[key]=heightsPx[key]*scale})
    try {
      const viewModel=buildMockGridViewModel(component.config,this.data.portfolio.themeMode,widthPx*scale+2*(component.config.horizontalMarginRpx || 0),heights,this.mockFontSession && this.mockFontSession.context(this.mockFontConfig && this.mockFontConfig.fonts))
      const activeComponents=this.data.portfolio.activeComponents.map(item=>item.componentKey === componentKey ? Object.assign({},item,{viewModel}) : item)
      this.setData({portfolio:Object.assign({},this.data.portfolio,{activeComponents})})
    } catch(error) { /* 保留可用的初始布局，编辑时显示具体校验提示。 */ }
  },

  noop() {}
})
