const {
  MOCK_COMPONENT_OPTIONS,
  applyMockComponentConfig,
  buildMockPortfolioRenderData,
  MOCK_WORK_LIBRARY,
  addMockComponent,
  getMockPortfolioDraft,
  getMockMenuComponents,
  getWorkIdsFromComponent,
  removeMockNavigationItem,
  removeMockComponent,
  renameMockNavigationItem,
  resetMockPortfolioDraft,
  reorderMockComponent,
  saveMockPortfolioDraft,
  setMockBottomNavigationCount,
  showMockLoginRequiredToast,
  trimText,
  updateComponentConfig,
  updateMockPortfolioStyle,
  updateMockSingleWorkConfig
} = require('../utils/mock-experience')
const {
  PORTFOLIO_TEXT_SELECTION_OPTIONS: PORTFOLIO_TEXT_FONT_OPTIONS,
  applyPortfolioFontSelection,
  LEGACY_TEXT_SECTION_LINE_HEIGHT,
  buildPortfolioTextLineHeightEditor,
  parsePortfolioTextLineHeightInput,
  stepPortfolioTextLineHeight,
  buildPortfolioTextFontSizeOptions,
  buildPortfolioTextTypography
} = require('../../../utils/portfolio-text-typography')
const {
  hexToHsv,
  hsvToHex
} = require('../utils/portfolio-color')

const { selectMockWorksFor } = require('../utils/mock-work-media')
const { createMockAudioController } = require('../utils/mock-portfolio-audio')
const textTools = require('../utils/mock-portfolio-text')
const { createMockFontSession, collectMockFontNodes } = require('../utils/mock-portfolio-fonts')
const gridTools = require('../utils/mock-portfolio-text-grid')
const { MOCK_CONTACT_PROFILE } = require('../utils/mock-portfolio-hyperlink')
const COMPLEX_TYPES = ['VIDEO_CAROUSEL','STRUCTURED_TEXT_SECTION','TEXT_GRID','CONTACT_INFO','HYPERLINK']
const PROFILE_TAG_COLOR_OPTIONS = [
  {
    name: '青绿',
    color: '#0f766e',
    background: '#dcf7f1',
    border: '#a7eadc',
    removeBackground: 'rgba(15, 118, 110, 0.12)'
  },
  {
    name: '湖蓝',
    color: '#2d5f9a',
    background: '#e5effb',
    border: '#bfd7f4',
    removeBackground: 'rgba(45, 95, 154, 0.12)'
  },
  {
    name: '琥珀',
    color: '#8a4b09',
    background: '#fff0d7',
    border: '#f5d29b',
    removeBackground: 'rgba(138, 75, 9, 0.12)'
  },
  {
    name: '玫红',
    color: '#a9354f',
    background: '#fde7ed',
    border: '#f5bfcc',
    removeBackground: 'rgba(169, 53, 79, 0.12)'
  },
  {
    name: '紫藤',
    color: '#6d5bd0',
    background: '#eeeafd',
    border: '#d2c9fa',
    removeBackground: 'rgba(109, 91, 208, 0.12)'
  },
  {
    name: '森绿',
    color: '#3f6f45',
    background: '#e6f3e8',
    border: '#bfdcc4',
    removeBackground: 'rgba(63, 111, 69, 0.12)'
  },
  {
    name: '墨蓝',
    color: '#36516e',
    background: '#e7edf4',
    border: '#c6d3e2',
    removeBackground: 'rgba(54, 81, 110, 0.12)'
  },
  {
    name: '砖红',
    color: '#9a4a35',
    background: '#f8e8e2',
    border: '#e9c2b5',
    removeBackground: 'rgba(154, 74, 53, 0.12)'
  },
  {
    name: '石墨',
    color: '#4b5563',
    background: '#eef2f6',
    border: '#d5dce5',
    removeBackground: 'rgba(75, 85, 99, 0.12)'
  }
].map((item) => Object.assign({}, item, {
  swatchStyle: `background: ${item.color};`,
  choiceStyle: `color: ${item.color}; background: ${item.background}; border-color: ${item.border};`
}))
const PROFILE_FIELDS = [{key:'avatar',label:'头像'},{key:'displayName',label:'姓名 / 艺名'},{key:'profession',label:'职业身份'},{key:'city',label:'服务城市'},{key:'bio',label:'简介'},{key:'tags',label:'标签'},{key:'wechatQr',label:'微信二维码'}]
const PREVIEW_URL = '/pages/mock/portfolio-standard-preview/portfolio-standard-preview'
const DEFAULT_SELECTED_COMPONENT_KEY = 'mock_carousel'
const SWIPE_REVEAL_THRESHOLD = -48
const SWIPE_CLOSE_THRESHOLD = 28
const SWIPE_VERTICAL_TOLERANCE = 26
const BACKGROUND_COLOR_OPTIONS = ['#151515', '#FFFFFF', '#F5F6F8']
const BOTTOM_NAV_COUNT_OPTIONS = [1, 2, 3, 4]
const COMPONENT_SPACING_MIN_RPX = 0
const COMPONENT_SPACING_MAX_RPX = 96
const AUDIO_STYLE_OPTIONS = [
  { value: 'DISC', label: '轻量唱片' },
  { value: 'SLEEVE', label: '封套抽盘' },
  { value: 'MINI_PLAYER', label: '迷你播放器' }
]
const SCHEDULE_QUERY_DISPLAY_MODE_OPTIONS = [
  {value:'MODAL_CALENDAR',label:'弹层显示月历'},
  {value:'INLINE_CALENDAR',label:'直接显示月历'}
]
const CONTACT_FORM_DISPLAY_MODE_OPTIONS = [
  {value:'MODAL_FORM',label:'弹层显示表单'},
  {value:'INLINE_FORM',label:'直接显示表单'}
]
const VIDEO_CAROUSEL_SETTING_HELP = {
  showTitle:{title:'作品标题',content:'在每张视频卡片中展示作品名称。'},
  showDescription:{title:'作品描述',content:'展示视频作品附带的描述文字。'},
  showSwipeHint:{title:'滑动提示',content:'提示访客左右滑动浏览视频。'}
}
const TEXT_SECTION_ALIGNMENT_OPTIONS = [
  { value: 'LEFT', label: '左对齐' },
  { value: 'CENTER', label: '居中' },
  { value: 'RIGHT', label: '右对齐' }
]
const DIVIDER_COLOR_OPTIONS = [
  { value: 'BLACK', label: '黑', colorValue: '#000000' },
  { value: 'WHITE', label: '白', colorValue: '#ffffff' },
  { value: 'GRAY', label: '灰', colorValue: '#eef1f4' },
  { value: 'TRANSPARENT', label: '透明', colorValue: 'transparent' }
]

function getTouchClientX(event) {
  const touch = event && event.changedTouches && event.changedTouches[0]
  if (touch && Number.isFinite(Number(touch.clientX))) {
    return Number(touch.clientX)
  }
  return null
}

function getTouchClientY(event) {
  const touch = event && event.changedTouches && event.changedTouches[0]
  if (touch && Number.isFinite(Number(touch.clientY))) {
    return Number(touch.clientY)
  }
  return null
}

function buildComponentDragStyle(offsetY) {
  return `transform: translateY(${Math.round(offsetY || 0)}px);`
}

function buildBackgroundColorPickerState(backgroundColorHsv = {}) {
  const normalizedHsv = {
    hue: Math.min(359, Math.max(0, Number(backgroundColorHsv.hue) || 0)),
    saturation: Math.min(1, Math.max(0, Number(backgroundColorHsv.saturation) || 0)),
    value: Math.min(1, Math.max(0, Number(backgroundColorHsv.value) || 0))
  }
  return {
    backgroundColorHsv: normalizedHsv,
    customBackgroundColor: hsvToHex(normalizedHsv),
    backgroundHueColor: hsvToHex({
      hue: normalizedHsv.hue,
      saturation: 1,
      value: 1
    }),
    backgroundColorPadDotStyle: `left: ${normalizedHsv.saturation * 100}%; top: ${(1 - normalizedHsv.value) * 100}%;`
  }
}

function isWorkSelectionComponent(componentType) {
  return componentType === 'VIDEO_CAROUSEL' || componentType === 'CAROUSEL' || componentType === 'WORK_GRID' || componentType === 'WORK_LIST' || componentType === 'SINGLE_WORK'
}

function findComponent(draft, componentKey, menuKey) {
  const components = draft && draft.config
    ? getMockMenuComponents(draft.config, menuKey)
    : []
  return components.find((component) => component.componentKey === componentKey) || components[0] || {}
}

function findProfileConfig(component) {
  return component.config && component.config.profile ? component.config.profile : {}
}

function findConfig(component) {
  return component.config || {}
}

function getComponentSummary(component, workIds) {
  if (component.componentType === 'PROFILE') {
    return trimText(component.config && component.config.profile && component.config.profile.displayName) || '个人资料'
  }
  if (isWorkSelectionComponent(component.componentType)) {
    if (component.componentType === 'SINGLE_WORK') {
      const work = MOCK_WORK_LIBRARY.works.find((item) => item.id === workIds[0])
      return work ? work.title : '请选择作品'
    }
    return workIds.length ? `已选 ${workIds.length} 个作品` : '请选择作品'
  }
  if (component.componentType === 'QR_CONTACT') {
    const config = component.config || {}
    return config.qrUrlSource === 'CUSTOM' ? '自定义二维码' : '使用个人资料二维码'
  }
  if (component.componentType === 'TEXT_SECTION') {
    return trimText(component.config && component.config.content) || '服务说明文字'
  }
  if (component.componentType === 'DIVIDER') {
    const config = component.config || {}
    return `${Number(config.heightPx) || 16}px / ${config.color || 'GRAY'}`
  }
  return '可编辑'
}

function buildComponentRows(draft, selectedComponentKey, menuKey) {
  const components = getMockMenuComponents(draft.config, menuKey)
  return components.map((component) => {
    const workIds = getWorkIdsFromComponent(component)
    return Object.assign({}, component, {
      active: component.componentKey === selectedComponentKey,
      summary: getComponentSummary(component, workIds)
    })
  })
}

function buildWorkOptions(workIds, context) {
  const orderedIds = (workIds || []).map(Number)
  const selectedIds = new Set(orderedIds)
  return selectMockWorksFor(context === true ? 'CAROUSEL' : context === false ? 'SINGLE_WORK' : context, MOCK_WORK_LIBRARY.works)
    .map((work) => Object.assign({}, work, {
      selected: selectedIds.has(work.id),
      selectionOrder: selectedIds.has(work.id) ? orderedIds.indexOf(work.id) + 1 : 0
    }))
}

function buildFilteredWorkOptions(workIds, context, keyword = '', selectedTagId = 0) {
  const normalizedKeyword = trimText(keyword).toLowerCase()
  const normalizedTagId = Number(selectedTagId) || 0
  return buildWorkOptions(workIds, context).filter((work) => {
    const tags = Array.isArray(work.tags) ? work.tags : []
    const keywordText = `${trimText(work.title)} ${tags.map((tag) => trimText(tag.name)).join(' ')}`.toLowerCase()
    const keywordMatched = !normalizedKeyword || keywordText.includes(normalizedKeyword)
    const tagMatched = !normalizedTagId || tags.some((tag) => Number(tag.id) === normalizedTagId)
    return keywordMatched && tagMatched
  })
}

function resolveActiveMenuKey(draft, activeMenuKey) {
  const bottomNav = draft.config && draft.config.bottomNav
  const items = bottomNav && bottomNav.enabled && Array.isArray(bottomNav.items)
    ? bottomNav.items
    : []
  return items.some((item) => item.key === activeMenuKey)
    ? activeMenuKey
    : (items[0] && items[0].key) || ''
}

function buildDisplayGroupState(config, requestedKey) {
  const groups = config.groups || []
  const options = [{ groupKey: 'g_all', name: '全部作品', color: '#212529', tagId: 0 }]
    .concat(MOCK_WORK_LIBRARY.tags.map(tag => ({ groupKey: `tag_${tag.id}`, name: tag.name, color: tag.color, tagId: tag.id })))
  for (const group of groups) {
    if (!options.some(option => option.groupKey === group.groupKey)) options.push(Object.assign({ tagId: 0, color: '#212529' }, group))
  }
  const activeDisplayGroupKey = options.some(option => option.groupKey === requestedKey) ? requestedKey : (groups[0] || options[0]).groupKey
  return {
    activeDisplayGroupKey,
    displayGroupOptions: options.map(option => {
      const index = groups.findIndex(group => group.groupKey === option.groupKey)
      return Object.assign({}, option, { active: option.groupKey === activeDisplayGroupKey, selected: index >= 0, selectionOrder: index + 1 })
    })
  }
}

function buildGridPreview(config, draft, metrics, fontContext = {}) {
  try { return gridTools.buildMockGridViewModel(config, draft.renderData.themeMode, metrics ? metrics.widthRpx + 2 * (config.horizontalMarginRpx || 0) : undefined, metrics ? metrics.heights : {}, fontContext) }
  catch (_) { return {cells:[]} }
}

function buildState(draft, selectedComponentKey = DEFAULT_SELECTED_COMPONENT_KEY, activeMenuKey, requestedGroupKey) {
  const safeActiveMenuKey = resolveActiveMenuKey(draft, activeMenuKey)
  const activeComponents = getMockMenuComponents(draft.config, safeActiveMenuKey)
  const safeSelectedKey = findComponent(draft, selectedComponentKey, safeActiveMenuKey).componentKey || ''
  const selectedComponent = findComponent(draft, safeSelectedKey, safeActiveMenuKey)
  const isGroup = ['WORK_GRID', 'WORK_LIST'].includes(selectedComponent.componentType)
  const groupState = buildDisplayGroupState(findConfig(selectedComponent), requestedGroupKey)
  const selectedGroup = (findConfig(selectedComponent).groups || []).find(group => group.groupKey === groupState.activeDisplayGroupKey)
  const groupOption = groupState.displayGroupOptions.find(option => option.active)
  const selectedWorkIds = isGroup ? (selectedGroup ? selectedGroup.workIds : []) : getWorkIdsFromComponent(selectedComponent)
  const bottomNavItems = draft.config.bottomNav && draft.config.bottomNav.enabled
    ? draft.config.bottomNav.items
    : []
  const activeMenu = bottomNavItems.find((item) => item.key === safeActiveMenuKey) || {}
  const backgroundColorPickerState = buildBackgroundColorPickerState(
    hexToHsv(draft.config.style.backgroundColor)
  )
  const textSectionConfig = Object.assign({}, findConfig(selectedComponent))
  const textSectionTypography = buildPortfolioTextTypography(textSectionConfig)
  return {
    draft,
    componentConfig: Object.assign({}, selectedComponent.componentType === 'PROFILE' ? {profileHorizontalMarginRpx:32} : {}, JSON.parse(JSON.stringify(findConfig(selectedComponent)))),
    complexConfig: JSON.parse(JSON.stringify(findConfig(selectedComponent))),
    structuredTextTab: 'content',
    mockFontAvailability: {SYSTEM:true,WECHAT_SANS_SS:textTools.isMockTextFontAvailable('WECHAT_SANS_SS')},
    gridPreviewViewModel: selectedComponent.componentType === 'TEXT_GRID' ? buildGridPreview(findConfig(selectedComponent),draft) : {cells:[]},
    videoTitleCount: Array.from(findConfig(selectedComponent).title || '').length,
    complexError: '',
    gridSelectedKeys: [],
    gridUndoCount: 0,
    profileTagColorOptions: PROFILE_TAG_COLOR_OPTIONS,
    profileFields: PROFILE_FIELDS.map(field=>Object.assign({},field,{visible:(findConfig(selectedComponent).visibleFields || {})[field.key] !== false})),
    localMediaOptions: selectMockWorksFor('TEXT_BACKGROUND', MOCK_WORK_LIBRARY.works),
    hyperlinkWorks: selectMockWorksFor('HYPERLINK', MOCK_WORK_LIBRARY.works),
    hyperlinkSelectedWork: MOCK_WORK_LIBRARY.works.find(work=>work.id === Number(findConfig(selectedComponent).workId)) || null,
    hyperlinkPickerVisible: false,
    audioSelectedWork: MOCK_WORK_LIBRARY.works.find(work=>work.id === draft.config.backgroundAudio.workId) || null,
    qrWorks: selectMockWorksFor('QR_CONTACT', MOCK_WORK_LIBRARY.works),
    audioWorks: selectMockWorksFor('BACKGROUND_AUDIO', MOCK_WORK_LIBRARY.works),
    selectedGroupIndex: 0,
    activeDisplayGroupKey: groupState.activeDisplayGroupKey,
    displayGroupOptions: groupState.displayGroupOptions,
    selectedWork: MOCK_WORK_LIBRARY.works.find(work => work.id === selectedWorkIds[0]) || null,
    singleWorkShowDescription: findConfig(selectedComponent).showDescription === true,
    scheduleQueryDisplayModeOptions: SCHEDULE_QUERY_DISPLAY_MODE_OPTIONS,
    contactFormDisplayModeOptions: CONTACT_FORM_DISPLAY_MODE_OPTIONS,
    backgroundColorOptions: BACKGROUND_COLOR_OPTIONS,
    customBackgroundColor: backgroundColorPickerState.customBackgroundColor,
    backgroundColorHsv: backgroundColorPickerState.backgroundColorHsv,
    backgroundHueColor: backgroundColorPickerState.backgroundHueColor,
    backgroundColorPadDotStyle: backgroundColorPickerState.backgroundColorPadDotStyle,
    bottomNavCountOptions: BOTTOM_NAV_COUNT_OPTIONS,
    bottomNavCount: bottomNavItems.length || 1,
    activeMenuKey: safeActiveMenuKey,
    activeMenuTitle: activeMenu.title || '',
    activeMenuTitleCount: Array.from(activeMenu.title || '').length,
    activeComponents,
    componentRows: buildComponentRows(draft, safeSelectedKey, safeActiveMenuKey),
    selectedComponentKey: safeSelectedKey,
    selectedComponentType: selectedComponent.componentType || '',
    selectedComponentName: selectedComponent.name || '',
    profileForm: Object.assign({}, findProfileConfig(selectedComponent)),
    profileQrUrl: [draft.config.components || []].concat(bottomNavItems.map(item=>item.components || [])).flat()
      .filter(component=>component.componentType === 'PROFILE').map(component=>findProfileConfig(component).wechatQrUrl || '')[0] || '',
    scheduleQueryForm: Object.assign({}, findConfig(selectedComponent)),
    contactFormConfig: Object.assign({}, findConfig(selectedComponent)),
    qrContactForm: Object.assign({}, findConfig(selectedComponent)),
    textSectionForm: Object.assign({}, textSectionConfig, textSectionTypography, {
      textAlign: textSectionConfig.alignment || 'LEFT'
    }),
    textSectionTypography,
    textSectionContentCount: Array.from(textSectionConfig.content || '').length,
    textSectionLineHeightEditor: buildPortfolioTextLineHeightEditor(textSectionConfig.lineHeight, LEGACY_TEXT_SECTION_LINE_HEIGHT),
    textSectionFontOptions: PORTFOLIO_TEXT_FONT_OPTIONS.filter(item=>!item.remote || item.value===textSectionConfig.fontId).map((item) => Object.assign({}, item, {
      available: textTools.isMockTextFontAvailable(item.value)
    })),
    textSectionSizeOptions: buildPortfolioTextFontSizeOptions(textSectionTypography.fontSizeRpx),
    dividerForm: Object.assign({}, findConfig(selectedComponent)),
    dividerColorValue: (DIVIDER_COLOR_OPTIONS.find(option=>option.value === findConfig(selectedComponent).color) || {}).colorValue || findConfig(selectedComponent).color,
    textSectionAlignmentOptions: TEXT_SECTION_ALIGNMENT_OPTIONS,
    dividerColorOptions: DIVIDER_COLOR_OPTIONS,
    selectedWorkIds,
    workFilterKeyword: '',
    selectedWorkFilterTagId: 0,
    workFilterTags: [{ id: 0, name: '全部', color: '#212529' }].concat(MOCK_WORK_LIBRARY.tags),
    singleWorkShowTitle: selectedComponent.componentType === 'SINGLE_WORK'
      ? typeof selectedComponent.config.showTitle === 'boolean' ? selectedComponent.config.showTitle : true
      : true,
    componentOptions: MOCK_COMPONENT_OPTIONS.map(option => Object.assign({}, option, {
      disabled: option.componentType === 'PROFILE' && [draft.config.components || []]
        .concat(bottomNavItems.map(item => item.components || []))
        .some(components => components.some(component => component.componentType === 'PROFILE'))
    })),
    workOptions: buildFilteredWorkOptions(
      isWorkSelectionComponent(selectedComponent.componentType) ? selectedWorkIds : [],
        selectedComponent.componentType, '', isGroup ? groupOption.tagId : 0
    )
  }
}

Page({
  data: Object.assign({
    works: MOCK_WORK_LIBRARY.works,
    componentOptions: MOCK_COMPONENT_OPTIONS,
    componentSheetVisible: false,
    componentEditSheetVisible: false,
    editScrollTop: 0,
    previewReturnPending: false,
    componentTouchStart: null,
    draggingIndex: -1,
    dragTargetIndex: -1,
    componentDragStartY: null,
    componentDragStyle: '',
    revealedComponentKey: '',
    backgroundColorSheetVisible: false,
    backgroundAudioStyles: AUDIO_STYLE_OPTIONS,
    backgroundAudioPickerVisible: false,
    componentSpacingMinRpx: COMPONENT_SPACING_MIN_RPX,
    componentSpacingMaxRpx: COMPONENT_SPACING_MAX_RPX,
    profileTagDialogVisible: false,
    profileNewTag: '',
    profileSelectedTagColor: PROFILE_TAG_COLOR_OPTIONS[0].color,
    profileTagErrorText: '',
    audioPlaying: false
  }, buildState(getMockPortfolioDraft())),

  onLoad() {
    this.mockFontSession = createMockFontSession({wxApi:wx,onChange:()=>this.refreshMockFonts(false)})
    textTools.registerMockTextFont(wx, capability => {
      if(this.mockFontDisposed)return
      this.setData({mockFontAvailability:{SYSTEM:true,WECHAT_SANS_SS:textTools.isMockTextFontAvailable('WECHAT_SANS_SS',capability)}})
      this.refreshMockFonts(false)
    })
    this.loadDraft()
    if(this.data.draft.draftWarning) wx.showToast({title:this.data.draft.draftWarning,icon:'none'})
  },

  onHide() { if (this.mockAudio) this.mockAudio.hide() },

  onUnload() { this.mockFontDisposed=true; if(this.mockFontSession)this.mockFontSession.destroy(); if (this.mockAudio) this.mockAudio.destroy() },

  onShow() {
    if (this.mockAudio) this.mockAudio.show()
    if (!this.data.previewReturnPending) {
      return
    }
    this.loadDraft({
      resetViewport: true,
      extraData: {
        previewReturnPending: false,
        componentSheetVisible: false,
        componentEditSheetVisible: false,
        revealedComponentKey: ''
      }
    })
  },

  loadDraft(options) {
    const nextOptions = options || {}
    this.setData(
      Object.assign(
        buildState(
          getMockPortfolioDraft(),
          this.data.selectedComponentKey,
          this.data.activeMenuKey
        ),
        nextOptions.extraData || {}
      ),
      () => {
        this.mockCandidateVersions = null
        this.refreshMockFonts()
        if (nextOptions.resetViewport) {
          this.resetEditViewport()
        }
      }
    )
  },

  setDraftState(draft, selectedComponentKey, extraData) {
    const nextExtraData = extraData || {}
    this.setData(Object.assign(
      buildState(
        draft,
        selectedComponentKey || this.data.selectedComponentKey,
        Object.prototype.hasOwnProperty.call(nextExtraData, 'activeMenuKey')
          ? nextExtraData.activeMenuKey
          : this.data.activeMenuKey,
        Object.prototype.hasOwnProperty.call(nextExtraData, 'activeDisplayGroupKey')
          ? nextExtraData.activeDisplayGroupKey
          : (!selectedComponentKey || selectedComponentKey === this.data.selectedComponentKey) ? this.data.activeDisplayGroupKey : null
      ),
      nextExtraData
    ))
    this.refreshMockFonts()
  },

  mockFontContext() {
    return this.mockFontSession ? this.mockFontSession.context(this.mockCandidateVersions || this.data.draft.config.fonts || {}) : {}
  },

  mockFontCommitDraft() {
    const draft=JSON.parse(JSON.stringify(this.data.draft))
    const config=this.mockFontCandidateConfig()
    const ids=new Set(collectMockFontNodes(config).map(node=>node.fontId))
    const versions=Object.fromEntries(Object.entries(this.mockCandidateVersions || config.fonts || {}).filter(([id])=>ids.has(id)))
    if(Object.keys(versions).length)draft.config.fonts=versions
    else delete draft.config.fonts
    return draft
  },

  mockFontCandidateConfig() {
    const config=JSON.parse(JSON.stringify(this.data.draft.config))
    config.fonts=this.mockCandidateVersions || config.fonts || {}
    if(this.data.componentEditSheetVisible) {
      const menus=config.bottomNav && config.bottomNav.items || []
      const selectedMenu=menus.find(item=>item.key===this.data.activeMenuKey)
      const list=selectedMenu && menus[0]!==selectedMenu ? selectedMenu.components || [] : config.components || []
      const component=list.find(item=>item.componentKey===this.data.selectedComponentKey)
      if(component)component.config=JSON.parse(JSON.stringify(COMPLEX_TYPES.includes(this.data.selectedComponentType)?this.data.complexConfig:this.data.componentConfig))
    }
    return config
  },

  handleMockFontSelect(event) {
    const fontId=event.detail.fontId
    if(!fontId || !this.mockFontSession || event.detail.previousFontId===fontId)return
    // 网格第一个字体动作前记录原根表，撤销能够恢复旧版本。
    if(this.data.selectedComponentType==='TEXT_GRID'&&!this.gridHistory)this.gridHistory=gridTools.createMockGridHistory(this.data.complexConfig,this.mockCandidateVersions || this.data.draft.config.fonts || {})
    this.mockCandidateVersions=this.mockFontSession.versions({fontId},this.mockCandidateVersions || this.data.draft.config.fonts || {})
  },

  handleMockFontRepair(event) {
    const fontId=event.detail && event.detail.fontId || event.currentTarget && event.currentTarget.dataset.value
    if(!fontId || !this.mockFontSession)return
    const option=this.data.mockFontChoices.find(item=>item.value===fontId)
    const version=(this.mockCandidateVersions || this.data.draft.config.fonts || {})[fontId]
    if(!option || !option.repairable)return
    const next=this.mockFontSession.versions({fontId},this.mockCandidateVersions || this.data.draft.config.fonts || {},{repair:fontId})
    if(version && next[fontId] && version.fontVersion===next[fontId].fontVersion)return
    if(this.data.selectedComponentType==='TEXT_GRID') {
      if(!this.gridHistory)this.gridHistory=gridTools.createMockGridHistory(this.data.complexConfig,this.mockCandidateVersions || this.data.draft.config.fonts || {})
      this.gridHistory.apply(this.data.complexConfig,next)
      this.setData({gridUndoCount:this.gridHistory.size()})
    }
    this.mockCandidateVersions=next
    this.refreshMockFonts()
  },

  handleMoreMockFonts() {this.setData({mockFontsExpanded:!this.data.mockFontsExpanded});this.refreshMockFonts(false)},
  handleMockSampleError(event) {const value=event.currentTarget.dataset.value;this.setData({textSectionFontOptions:this.data.textSectionFontOptions.map(item=>item.value===value?{...item,sampleUrl:''}:item)})},

  /** 候选和已确认配置使用同一页面缓存，版本只在确认时进入本地草稿。 */
  refreshMockFonts(load=true) {
    if(!this.mockFontSession || this.mockFontDisposed || !this.data.draft)return
    const config=this.mockFontCandidateConfig()
    const versions=config.fonts
    const context=this.mockFontSession.context(versions)
    const selected=this.data.textSectionForm && (this.data.textSectionForm.fontId || this.data.textSectionForm.fontFamily)
    const common={builtIn:this.data.mockFontAvailability,versions}
    const renderData=buildMockPortfolioRenderData(this.data.draft.config,context)
    this.setData({mockFontChoices:this.mockFontSession.options({...common,expanded:true}),
      textSectionFontOptions:this.mockFontSession.options({...common,expanded:this.data.mockFontsExpanded===true,selected}),
      mockFontRepairable:this.mockFontSession.options({...common,selected}).some(item=>item.value===selected && item.repairable),
      mockFontContext:context,
      draft:{...this.data.draft,renderData},
      textSectionTypography:buildPortfolioTextTypography(this.data.componentConfig,undefined,context),
      gridPreviewViewModel:this.data.selectedComponentType==='TEXT_GRID'?buildGridPreview(this.data.complexConfig,this.data.draft,this.gridPreviewMetrics,context):this.data.gridPreviewViewModel})
    if(load)this.mockFontSession.load({fonts:versions,components:getMockMenuComponents(config,this.data.activeMenuKey)})
  },

  resetEditViewport() {
    this.setData({ editScrollTop: 1 }, () => {
      this.setData({ editScrollTop: 0 })
    })
  },

  handleBackgroundColorTap(event) {
    const color = event.currentTarget.dataset.color
    this.setDraftState(updateMockPortfolioStyle(this.data.draft, color))
  },

  handleOpenBackgroundColorSheet() {
    this.setData(Object.assign({
      backgroundColorSheetVisible: true,
      customBackgroundColor: this.data.draft.config.style.backgroundColor
    }, buildBackgroundColorPickerState(hexToHsv(this.data.draft.config.style.backgroundColor))))
  },

  handleCloseBackgroundColorSheet() {
    this.setData({ backgroundColorSheetVisible: false })
  },

  handleCustomBackgroundColorInput(event) {
    const customBackgroundColor = trimText(event.detail.value).toUpperCase()
    if (/^#[0-9A-F]{6}$/.test(customBackgroundColor)) {
      this.setData(buildBackgroundColorPickerState(hexToHsv(customBackgroundColor)))
      return
    }
    this.setData({ customBackgroundColor })
  },

  handleBackgroundHueChange(event) {
    this.setData(buildBackgroundColorPickerState(Object.assign({}, this.data.backgroundColorHsv, {
      hue: Number(event.detail.value) || 0
    })))
  },

  handleBackgroundColorPadTouch(event) {
    const touch = event && event.touches && event.touches[0]
    if (!touch) {
      return
    }
    wx.createSelectorQuery()
      .in(this)
      .select('.background-color-pad')
      .boundingClientRect((rect) => {
        if (!rect || !rect.width || !rect.height) {
          return
        }
        const saturation = Math.min(1, Math.max(0, (Number(touch.clientX) - rect.left) / rect.width))
        const value = 1 - Math.min(1, Math.max(0, (Number(touch.clientY) - rect.top) / rect.height))
        this.setData(buildBackgroundColorPickerState(Object.assign({}, this.data.backgroundColorHsv, {
          saturation,
          value
        })))
      })
      .exec()
  },

  handleCustomBackgroundColorBlur() {
    const color = trimText(this.data.customBackgroundColor).toUpperCase()
    if (!/^#[0-9A-F]{6}$/.test(color)) {
      wx.showToast({ title: '请输入正确的颜色值', icon: 'none' })
    }
  },

  handleConfirmBackgroundColor() {
    const color = trimText(this.data.customBackgroundColor).toUpperCase()
    if (!/^#[0-9A-F]{6}$/.test(color)) {
      wx.showToast({ title: '请输入正确的颜色值', icon: 'none' })
      return
    }
    this.setDraftState(updateMockPortfolioStyle(this.data.draft, color), '', {
      backgroundColorSheetVisible: false
    })
  },

  handleBottomNavCountTap(event) {
    const count = Number(event.currentTarget.dataset.count)
    const currentItems = this.data.draft.config.bottomNav.enabled
      ? this.data.draft.config.bottomNav.items
      : []
    const activeMenuIndex = currentItems.findIndex((item) => item.key === this.data.activeMenuKey)
    const applyCount = () => {
      const draft = setMockBottomNavigationCount(this.data.draft, count)
      const items = draft.config.bottomNav.enabled ? draft.config.bottomNav.items : []
      const activeMenuKey = items.some((item) => item.key === this.data.activeMenuKey)
        ? this.data.activeMenuKey
        : ((items[Math.min(Math.max(activeMenuIndex - 1, 0), items.length - 1)] || {}).key || '')
      const selectedComponent = getMockMenuComponents(draft.config, activeMenuKey)[0] || {}
      this.setDraftState(draft, selectedComponent.componentKey || '', {
        activeMenuKey,
        componentEditSheetVisible: false,
        revealedComponentKey: ''
      })
    }
    if (!this.data.draft.config.bottomNav.enabled || count >= currentItems.length) {
      applyCount()
      return
    }
    const removedItems = count < 2 ? currentItems.slice(1) : currentItems.slice(count)
    const removalMessages = removedItems
      .map((item) => ({
        item,
        componentCount: getMockMenuComponents(this.data.draft.config, item.key).length
      }))
      .filter(({ componentCount }) => componentCount > 0)
      .map(({ item, componentCount }) => `删除菜单「${item.title}」将同时删除其下 ${componentCount} 个组件`)
    if (removalMessages.length === 0) {
      applyCount()
      return
    }
    wx.showModal({
      title: count < 2 ? '关闭底部导航？' : '减少底部菜单？',
      content: removalMessages.join('\n'),
      confirmText: '确认',
      confirmColor: '#b55656',
      success: (result) => {
        if (result.confirm) {
          applyCount()
        }
      }
    })
  },

  handleEditorMenuTap(event) {
    const activeMenuKey = event.currentTarget.dataset.key
    if (!activeMenuKey || activeMenuKey === this.data.activeMenuKey) {
      return
    }
    const selectedComponent = getMockMenuComponents(this.data.draft.config, activeMenuKey)[0] || {}
    this.setDraftState(this.data.draft, selectedComponent.componentKey || '', {
      activeMenuKey,
      componentEditSheetVisible: false,
      revealedComponentKey: ''
    })
  },

  handleEditorMenuTitleInput(event) {
    const menuKey = event.currentTarget.dataset.key
    const draft = renameMockNavigationItem(this.data.draft, menuKey, event.detail.value)
    this.setDraftState(draft, this.data.selectedComponentKey, { activeMenuKey: menuKey })
  },

  handleRemoveEditorMenu(event) {
    const menuKey = event.currentTarget.dataset.key
    const currentItems = this.data.draft.config.bottomNav.enabled
      ? this.data.draft.config.bottomNav.items
      : []
    const menuIndex = currentItems.findIndex((item) => item.key === menuKey)
    if (menuIndex < 0) {
      return
    }
    const menu = currentItems[menuIndex]
    const componentCount = getMockMenuComponents(this.data.draft.config, menuKey).length
    const removeMenu = () => {
      const draft = removeMockNavigationItem(this.data.draft, menuKey)
      const items = draft.config.bottomNav.enabled ? draft.config.bottomNav.items : []
      const activeMenuKey = ((currentItems[menuIndex - 1] || currentItems[menuIndex + 1]) || {}).key || ''
      const selectedComponent = getMockMenuComponents(draft.config, activeMenuKey)[0] || {}
      this.setDraftState(draft, selectedComponent.componentKey || '', {
        activeMenuKey,
        componentEditSheetVisible: false,
        revealedComponentKey: ''
      })
    }
    if (menuIndex > 0 && componentCount === 0) {
      removeMenu()
      return
    }
    const content = menuIndex === 0 && currentItems[1]
      ? `删除菜单「${menu.title}」${componentCount > 0 ? `将同时删除其下 ${componentCount} 个组件，` : '，'}「${currentItems[1].title}」将成为第一个菜单，旧版本小程序将展示「${currentItems[1].title}」的内容`
      : `删除菜单「${menu.title}」将同时删除其下 ${componentCount} 个组件`
    wx.showModal({
      title: `删除菜单「${menu.title}」？`,
      content,
      confirmText: '删除',
      confirmColor: '#b55656',
      success: (result) => {
        if (result.confirm) {
          removeMenu()
        }
      }
    })
  },

  handleComponentTap(event) {
    this.gridPreviewMetrics = null
    this.gridHistory = null
    this.gridHasInvalidDraft = false
    const componentKey = event.currentTarget.dataset.key || event.currentTarget.dataset.componentKey
    if (!componentKey) {
      return
    }
    if (this.data.revealedComponentKey === componentKey) {
      this.setData({ revealedComponentKey: '' })
      return
    }
    this.mockCandidateVersions = JSON.parse(JSON.stringify(this.data.draft.config.fonts || {}))
    this.setData({mockFontsExpanded:false})
    this.componentEditSnapshot = JSON.parse(JSON.stringify(this.data.draft))
    this.setDraftState(this.data.draft, componentKey, {
      componentEditSheetVisible: true
    })
  },

  handleShareInput(event) {
    const field = event.currentTarget.dataset.field
    const value = event.detail.value || ''
    if (!field) {
      return
    }
    const draft = Object.assign({}, this.data.draft)
    draft.config = Object.assign({}, draft.config || {})
    draft.config.share = Object.assign({}, draft.config.share || {})
    draft.config.share[field] = value
    this.setDraftState(draft, this.data.selectedComponentKey)
  },

  handleProfileInput(event) {
    if (!this.data.componentEditSheetVisible) return
    const field = event.currentTarget.dataset.field
    const value = event.detail.value || ''
    if (!field) {
      return
    }
    const componentKey = this.data.selectedComponentKey
    if (this.data.selectedComponentType !== 'PROFILE') {
      return
    }
    const draft = updateComponentConfig(this.data.draft, componentKey, (config) => {
      const profile = Object.assign({}, config.profile || {})
      profile[field] = value
      return Object.assign({}, config, { profile })
    }, this.data.activeMenuKey)
    this.setDraftState(draft, componentKey)
  },

  handleOpenProfileTagDialog() {
    this.setData({profileTagDialogVisible:true,profileNewTag:'',profileSelectedTagColor:PROFILE_TAG_COLOR_OPTIONS[0].color,profileTagErrorText:''})
  },

  handleCloseProfileTagDialog() { this.setData({profileTagDialogVisible:false,profileTagErrorText:''}) },
  handleProfileNewTagInput(event) { this.setData({profileNewTag:event.detail.value,profileTagErrorText:''}) },
  handleSelectProfileTagColor(event) {
    const color = event.currentTarget.dataset.color
    if(PROFILE_TAG_COLOR_OPTIONS.some(item=>item.color===color)) this.setData({profileSelectedTagColor:color})
  },

  handleAddProfileTag() {
    const tags = this.data.profileForm.tags || []
    const name = trimText(this.data.profileNewTag)
    const error = !name ? '请输入标签文字' : Array.from(name).length > 10 ? '单个标签最多 10 个字' : tags.length >= 10 ? '最多保留 10 个标签' : tags.some(tag=>tag.name===name) ? '标签不能重复' : ''
    if(error) {this.setData({profileTagErrorText:error});return}
    this.handleProfileInput({currentTarget:{dataset:{field:'tags'}},detail:{value:tags.concat({name,color:this.data.profileSelectedTagColor})}})
    this.handleCloseProfileTagDialog()
  },

  handleRemoveProfileTag(event) {
    const index = Number(event.currentTarget.dataset.index)
    this.handleProfileInput({currentTarget:{dataset:{field:'tags'}},detail:{value:(this.data.profileForm.tags || []).filter((tag,i)=>i!==index)}})
  },

  handleScheduleInput(event) {
    if (!this.data.componentEditSheetVisible) return
    const field = event.currentTarget.dataset.field
    const value = event.detail.value || ''
    if (!field) {
      return
    }
    const componentKey = this.data.selectedComponentKey
    if (this.data.selectedComponentType !== 'SCHEDULE_QUERY') {
      return
    }
    const draft = updateComponentConfig(this.data.draft, componentKey, (config) => {
      const nextConfig = Object.assign({}, config)
      nextConfig[field] = value
      return nextConfig
    }, this.data.activeMenuKey)
    this.setDraftState(draft, componentKey)
  },

  handleContactInput(event) {
    if (!this.data.componentEditSheetVisible) return
    const field = event.currentTarget.dataset.field
    const value = event.detail.value || ''
    if (!field) {
      return
    }
    const componentKey = this.data.selectedComponentKey
    if (this.data.selectedComponentType !== 'CONTACT_FORM') {
      return
    }
    const draft = updateComponentConfig(this.data.draft, componentKey, (config) => {
      const nextConfig = Object.assign({}, config)
      nextConfig[field] = value
      return nextConfig
    }, this.data.activeMenuKey)
    this.setDraftState(draft, componentKey)
  },

  handleTextSectionInput(event) {
    if (!this.data.componentEditSheetVisible) return
    const value = event.detail.value || ''
    const componentKey = this.data.selectedComponentKey
    if (this.data.selectedComponentType !== 'TEXT_SECTION') {
      return
    }
    const draft = updateComponentConfig(this.data.draft, componentKey, (config) => {
      return Object.assign({}, config, {
        content: value
      })
    }, this.data.activeMenuKey)
    this.setDraftState(draft, componentKey)
  },

  handleTextSectionAlignmentTap(event) {
    if (!this.data.componentEditSheetVisible) return
    const value = event.currentTarget.dataset.value || 'LEFT'
    const componentKey = this.data.selectedComponentKey
    if (this.data.selectedComponentType !== 'TEXT_SECTION') {
      return
    }
    const draft = updateComponentConfig(this.data.draft, componentKey, (config) => {
      return Object.assign({}, config, {
        alignment: value
      })
    }, this.data.activeMenuKey)
    this.setDraftState(draft, componentKey)
  },

  handleTextSectionFontTap(event) {
    if (!this.data.componentEditSheetVisible) return
    const fontFamily = event.currentTarget.dataset.value || 'SYSTEM'
    const option=(this.data.textSectionFontOptions||[]).find(item=>item.value===fontFamily)
    if (!option || !option.available) return
    const componentKey = this.data.selectedComponentKey
    if (this.data.selectedComponentType !== 'TEXT_SECTION') {
      return
    }
    this.handleMockFontSelect({detail:{fontId:option.remote?fontFamily:null,previousFontId:this.data.componentConfig.fontId}})
    const draft = updateComponentConfig(this.data.draft, componentKey, (config) => {
      return Object.assign({}, config, applyPortfolioFontSelection(fontFamily))
    }, this.data.activeMenuKey)
    this.setDraftState(draft, componentKey)
  },

  handleTextSectionFontSizeTap(event) {
    if (!this.data.componentEditSheetVisible) return
    const fontSizeRpx = Number(event.currentTarget.dataset.value)
    const componentKey = this.data.selectedComponentKey
    if (this.data.selectedComponentType !== 'TEXT_SECTION' || !fontSizeRpx) {
      return
    }
    const draft = updateComponentConfig(this.data.draft, componentKey, (config) => {
      return Object.assign({}, config, { fontSizeRpx })
    }, this.data.activeMenuKey)
    this.setDraftState(draft, componentKey)
  },

  handleTextSectionLineHeightInput(event) {
    const value = event.detail.value === '' ? '' : parsePortfolioTextLineHeightInput(event.detail.value)
    this.handleConfigInput({currentTarget:{dataset:{field:'lineHeight'}},detail:{value}})
    this.setData({textSectionLineHeightEditor:buildPortfolioTextLineHeightEditor(this.data.componentConfig.lineHeight,LEGACY_TEXT_SECTION_LINE_HEIGHT)})
  },

  handleTextSectionLineHeightStep(event) {
    const value = stepPortfolioTextLineHeight(this.data.componentConfig.lineHeight,Number(event.currentTarget.dataset.delta),LEGACY_TEXT_SECTION_LINE_HEIGHT)
    if (value !== undefined) this.handleTextSectionLineHeightInput({detail:{value:String(value)}})
  },

  handleTextSectionColorPreset(event) {
    this.handleConfigInput({currentTarget:{dataset:{field:'color'}},detail:{value:event.currentTarget.dataset.color}})
  },

  handleTextSectionColorInput(event) {
    this.handleConfigInput({currentTarget:{dataset:{field:'color'}},detail:{value:String(event.detail.value || '').toUpperCase()}})
  },

  handleVideoCarouselSettingHelp(event) {
    const help = VIDEO_CAROUSEL_SETTING_HELP[event.currentTarget.dataset.option]
    if (help) wx.showModal(Object.assign({},help,{showCancel:false}))
  },

  handleDividerColorTap(event) {
    if (!this.data.componentEditSheetVisible) return
    const value = event.currentTarget.dataset.value || 'GRAY'
    const componentKey = this.data.selectedComponentKey
    if (this.data.selectedComponentType !== 'DIVIDER') {
      return
    }
    const draft = updateComponentConfig(this.data.draft, componentKey, (config) => {
      return Object.assign({}, config, {
        color: value
      })
    }, this.data.activeMenuKey)
    this.setDraftState(draft, componentKey)
  },

  handleDividerHeightInput(event) {
    if (!this.data.componentEditSheetVisible) return
    const value = event.detail.value || ''
    const componentKey = this.data.selectedComponentKey
    if (this.data.selectedComponentType !== 'DIVIDER') {
      return
    }
    const draft = updateComponentConfig(this.data.draft, componentKey, (config) => {
      return Object.assign({}, config, {
        heightPx: value
      })
    }, this.data.activeMenuKey)
    this.setDraftState(draft, componentKey)
  },

  handleWorkToggle(event) {
    if (!this.data.componentEditSheetVisible) return
    const workId = Number(event.currentTarget.dataset.id)
    const type = this.data.selectedComponentType
    if (!selectMockWorksFor(type, MOCK_WORK_LIBRARY.works).some(work=>work.id === workId)) return
    const ids = this.data.selectedWorkIds || []
    if (type === 'SINGLE_WORK') {
      this.setData({selectedWorkIds:[workId],selectedWork:MOCK_WORK_LIBRARY.works.find(work=>work.id===workId),workOptions:buildFilteredWorkOptions([workId],type,this.data.workFilterKeyword,this.data.selectedWorkFilterTagId)})
      return
    }
    const next = ids.includes(workId) ? ids.filter(id=>id !== workId) : ids.concat(workId)
    const limit = type === 'CAROUSEL' ? 9 : type === 'VIDEO_CAROUSEL' ? 8 : Infinity
    if (next.length > limit) { wx.showToast({title:`最多选择 ${limit} 个作品`,icon:'none'}); return }
    this.applySelectedWorks(next)
  },

  applySelectedWorks(ids) {
    const type = this.data.selectedComponentType
    if (type === 'VIDEO_CAROUSEL') {
      this.setData({complexConfig:Object.assign({},this.data.complexConfig,{workIds:ids}),selectedWorkIds:ids,workOptions:buildFilteredWorkOptions(ids,type,this.data.workFilterKeyword,this.data.selectedWorkFilterTagId)})
      return
    }
    if (type === 'WORK_GRID' || type === 'WORK_LIST') {
      const option = this.data.displayGroupOptions.find(item => item.active)
      const groups = (this.data.componentConfig.groups || []).map(group => Object.assign({}, group))
      let group = groups.find(item => item.groupKey === option.groupKey)
      if (!group) { group = {groupKey:option.groupKey,name:option.name,sortOrder:(groups.length+1)*1000,workIds:[]}; groups.push(group) }
      group.workIds = ids
      const draft = updateComponentConfig(this.data.draft,this.data.selectedComponentKey,config=>Object.assign({},config,{groups,workIds:[...new Set(groups.flatMap(item=>item.workIds))]}),this.data.activeMenuKey)
      this.setDraftState(draft)
      return
    }
    const groupIndex = this.data.selectedGroupIndex || 0
    const draft = updateComponentConfig(this.data.draft,this.data.selectedComponentKey,config=> {
      if (type === 'WORK_GRID' || type === 'WORK_LIST') {
        const groups = (config.groups || [{groupKey:'g_all',name:'全部作品',sortOrder:1000,workIds:[]}]).map((g,i)=>i === groupIndex ? Object.assign({},g,{workIds:ids}) : g)
        return Object.assign({},config,{groups,workIds:[...new Set(groups.flatMap(g=>g.workIds))]})
      }
      return Object.assign({},config,{workIds:ids})
    },this.data.activeMenuKey)
    this.setDraftState(draft,this.data.selectedComponentKey,{selectedGroupIndex:groupIndex,selectedWorkIds:ids,workFilterKeyword:this.data.workFilterKeyword,selectedWorkFilterTagId:this.data.selectedWorkFilterTagId,workOptions:buildFilteredWorkOptions(ids,type,this.data.workFilterKeyword,this.data.selectedWorkFilterTagId)})
  },

  handleWorkOrder(event) {
    const index = Number(event.currentTarget.dataset.index), offset = Number(event.currentTarget.dataset.offset)
    const ids = this.data.selectedWorkIds.slice(), target = index + offset
    if (target < 0 || target >= ids.length) return
    ;[ids[index],ids[target]] = [ids[target],ids[index]]
    this.applySelectedWorks(ids)
  },

  handleWorkFilterInput(event) {
    const workFilterKeyword = event.detail && event.detail.value ? event.detail.value : ''
    this.setData({
      workFilterKeyword,
      workOptions: buildFilteredWorkOptions(
        this.data.selectedWorkIds,
        this.data.selectedComponentType,
        workFilterKeyword,
        this.data.selectedWorkFilterTagId
      )
    })
  },

  handleWorkFilterTagTap(event) {
    const selectedWorkFilterTagId = Number(event.currentTarget.dataset.tagId) || 0
    this.setData({
      selectedWorkFilterTagId,
      workOptions: buildFilteredWorkOptions(
        this.data.selectedWorkIds,
        this.data.selectedComponentType,
        this.data.workFilterKeyword,
        selectedWorkFilterTagId
      )
    })
  },

  handleSingleWorkShowTitleChange(event) {
    if (this.data.selectedComponentType !== 'SINGLE_WORK') {
      return
    }
    this.setData({ singleWorkShowTitle: Boolean(event.detail && event.detail.value) })
  },

  handleOpenComponentSheet() {
    this.setData({ componentSheetVisible: true })
  },

  handleCloseComponentSheet() {
    this.setData({ componentSheetVisible: false })
  },

  noop() {},

  handleSelectComponent(event) {
    this.gridPreviewMetrics = null
    this.gridHistory = null
    this.gridHasInvalidDraft = false
    const componentType = event.currentTarget.dataset.type
    if (!componentType) {
      return
    }
    const option = this.data.componentOptions.find(item => item.componentType === componentType)
    if (!option || option.disabled) return
    this.mockCandidateVersions = JSON.parse(JSON.stringify(this.data.draft.config.fonts || {}))
    this.setData({mockFontsExpanded:false})
    this.componentEditSnapshot = JSON.parse(JSON.stringify(this.data.draft))
    const currentComponents = getMockMenuComponents(this.data.draft.config, this.data.activeMenuKey)
    const currentKeys = new Set(currentComponents.map((component) => component.componentKey))
    const draft = addMockComponent(this.data.draft, componentType, this.data.activeMenuKey)
    const components = getMockMenuComponents(draft.config, this.data.activeMenuKey)
    const addedComponent = components.find((component) => !currentKeys.has(component.componentKey)) ||
      components[components.length - 1] ||
      {}
    this.setDraftState(draft, addedComponent.componentKey, {
      componentSheetVisible: false,
      componentEditSheetVisible: true,
      revealedComponentKey: ''
    })
  },

  handleComponentTouchStart(event) {
    const clientX = getTouchClientX(event)
    const clientY = getTouchClientY(event)
    this.setData({
      componentTouchStart: {
        key: event.currentTarget.dataset.key || '',
        index: Number(event.currentTarget.dataset.index),
        x: clientX === null ? 0 : clientX,
        y: clientY === null ? 0 : clientY
      }
    })
  },

  handleComponentDragStart(event) {
    const index = Number(event.currentTarget.dataset.index)
    if (!Number.isFinite(index)) {
      return
    }
    const clientY = getTouchClientY(event)
    if (typeof wx !== 'undefined' && wx.createSelectorQuery) {
      wx.createSelectorQuery()
        .in(this)
        .selectAll('.component-row')
        .boundingClientRect((rows = []) => {
          this.componentDragRows = rows
        })
        .exec()
    }
    this.setData({
      draggingIndex: index,
      dragTargetIndex: index,
      componentDragStartY: clientY,
      componentDragStyle: buildComponentDragStyle(0),
      revealedComponentKey: ''
    })
  },

  handleComponentTouchMove(event) {
    if (this.data.draggingIndex < 0) {
      return
    }
    const clientY = getTouchClientY(event)
    const rows = this.componentDragRows || []
    if (clientY === null || rows.length === 0) {
      return
    }
    let targetIndex = rows.findIndex((row) => clientY < row.top + row.height / 2)
    if (targetIndex < 0) {
      targetIndex = rows.length - 1
    }
    if (targetIndex !== this.data.dragTargetIndex) {
      this.setData({ dragTargetIndex: targetIndex })
    }
    const startY = Number(this.data.componentDragStartY)
    const offsetY = Number.isFinite(startY) ? clientY - startY : 0
    this.setData({ componentDragStyle: buildComponentDragStyle(offsetY) })
  },

  handleComponentTouchEnd(event) {
    if (this.data.draggingIndex >= 0) {
      this.handleComponentDragEnd()
      return
    }
    const start = this.data.componentTouchStart
    if (!start || !start.key) {
      return
    }
    const clientX = getTouchClientX(event)
    const clientY = getTouchClientY(event)
    const deltaX = (clientX === null ? start.x : clientX) - start.x
    const deltaY = Math.abs((clientY === null ? start.y : clientY) - start.y)
    if (deltaY <= SWIPE_VERTICAL_TOLERANCE && deltaX < SWIPE_REVEAL_THRESHOLD) {
      this.setData({
        revealedComponentKey: start.key,
        componentTouchStart: null
      })
      return
    }
    if (deltaX > SWIPE_CLOSE_THRESHOLD || Math.abs(deltaX) < 8) {
      this.setData({
        revealedComponentKey: '',
        componentTouchStart: null
      })
      return
    }
    this.setData({ componentTouchStart: null })
  },

  handleComponentTouchCancel() {
    if (this.data.draggingIndex >= 0) {
      this.handleComponentDragEnd()
      return
    }
    this.setData({ componentTouchStart: null })
  },

  handleComponentDragEnd() {
    const { draggingIndex, dragTargetIndex } = this.data
    const resetDragState = {
      draggingIndex: -1,
      dragTargetIndex: -1,
      componentDragStartY: null,
      componentDragStyle: '',
      componentTouchStart: null
    }
    this.componentDragRows = []
    if (draggingIndex >= 0 && dragTargetIndex >= 0 && draggingIndex !== dragTargetIndex) {
      this.setDraftState(
        reorderMockComponent(
          this.data.draft,
          draggingIndex,
          dragTargetIndex,
          this.data.activeMenuKey
        ),
        this.data.selectedComponentKey,
        resetDragState
      )
      return
    }
    this.setData(resetDragState)
  },

  handleRemoveComponent(event) {
    const componentKey = event.currentTarget.dataset.key
    if (!componentKey) {
      return
    }
    const draft = removeMockComponent(this.data.draft, componentKey, this.data.activeMenuKey)
    const components = getMockMenuComponents(draft.config, this.data.activeMenuKey)
    const selectedComponentKey = this.data.selectedComponentKey === componentKey
      ? (components[0] && components[0].componentKey) || ''
      : this.data.selectedComponentKey
    this.setDraftState(draft, selectedComponentKey, {
      componentEditSheetVisible: false,
      revealedComponentKey: ''
    })
  },

  handleCloseComponentEditSheet() {
    this.handleCloseProfileTagDialog()
    this.gridHistory = null
    this.gridHasInvalidDraft = false
    const snapshot = this.componentEditSnapshot
    this.componentEditSnapshot = null
    this.mockCandidateVersions = null
    if (snapshot) this.setDraftState(snapshot, null, { componentEditSheetVisible: false })
    else this.setData({ componentEditSheetVisible: false })
  },

  handleConfirmComponentEditSheet(event) {
    if (!this.data.componentEditSheetVisible) return
    if (event && event.detail && event.detail.config && COMPLEX_TYPES.includes(this.data.selectedComponentType)) this.setData({complexConfig:event.detail.config})
    if (COMPLEX_TYPES.includes(this.data.selectedComponentType)) {
      const result = applyMockComponentConfig(this.mockFontCommitDraft(),this.data.selectedComponentKey,this.data.complexConfig,this.data.activeMenuKey)
      if (!result.valid) { this.setData({complexError:result.message}); return }
      this.gridHistory = null
      this.gridHasInvalidDraft = false
      this.componentEditSnapshot = null
      this.mockCandidateVersions=null
      this.setDraftState(result.draft,this.data.selectedComponentKey,{componentEditSheetVisible:false})
      return
    }
    if (this.data.selectedComponentType === 'SINGLE_WORK') {
      const workId = Number((this.data.selectedWorkIds || [])[0]) || 0
      if (!workId) {
        wx.showToast({ title: '请选择一个作品', icon: 'none' })
        return
      }
      const draft = updateMockSingleWorkConfig(this.data.draft, this.data.selectedComponentKey, {
        workId,
        showTitle: this.data.singleWorkShowTitle,
        showDescription: this.data.singleWorkShowDescription
      }, this.data.activeMenuKey)
      this.componentEditSnapshot = null
      this.setDraftState(draft, this.data.selectedComponentKey, {
        componentEditSheetVisible: false
      })
      return
    }
    const result = applyMockComponentConfig(this.mockFontCommitDraft(),this.data.selectedComponentKey,this.data.componentConfig,this.data.activeMenuKey)
    if (!result.valid) { this.setData({complexError:result.message}); return }
    this.componentEditSnapshot = null
    this.mockCandidateVersions=null
      this.setDraftState(result.draft,this.data.selectedComponentKey,{componentEditSheetVisible:false})
  },

  handlePreview() {
    if (this.data.componentEditSheetVisible) this.handleCloseComponentEditSheet()
    saveMockPortfolioDraft(this.data.draft)
    this.setData({
      previewReturnPending: true,
      componentSheetVisible: false,
      componentEditSheetVisible: false,
      revealedComponentKey: ''
    }, () => {
      wx.navigateTo({
        url: PREVIEW_URL
      })
    })
  },

  handleResetDraft() {
    this.componentEditSnapshot = null
    if(this.mockAudio) this.mockAudio.setResource(null)
    this.setData({audioPlaying:false})
    this.setDraftState(resetMockPortfolioDraft(), DEFAULT_SELECTED_COMPONENT_KEY)
  },

  handleSaveDraft() {
    showMockLoginRequiredToast()
  },

  handlePublish() {
    showMockLoginRequiredToast()
  },

  handleComplexChange(event) {
    if (!this.data.componentEditSheetVisible) return
    this.setData({complexConfig:event.detail.config,complexError:'',hyperlinkSelectedWork:MOCK_WORK_LIBRARY.works.find(work=>work.id === Number(event.detail.config.workId)) || null})
    this.refreshMockFonts()
  },

  handleConfigInput(event) {
    if (!this.data.componentEditSheetVisible) return
    const {field,numeric} = event.currentTarget.dataset
    if (this.data.selectedComponentType === 'PROFILE' && ['profileBorderColor','profileBorderWidthRpx','profileHorizontalMarginRpx','profileVerticalMarginRpx'].includes(field) && !this.data.componentConfig.profileBorder) return
    const value = this.data.selectedComponentType === 'VIDEO_CAROUSEL' && field === 'title'
      ? Array.from(String(event.detail.value || '')).slice(0,10).join('')
      : numeric ? Number(event.detail.value) : event.detail.value
    if (COMPLEX_TYPES.includes(this.data.selectedComponentType)) {
      const complexConfig=Object.assign({},this.data.complexConfig,{[field]:value})
      if(field === 'lineHeight' && event.detail.value === '') delete complexConfig.lineHeight
      this.setData({complexConfig,complexError:'',videoTitleCount:Array.from(complexConfig.title || '').length})
      return
    }
    const componentConfig=Object.assign({},this.data.componentConfig,{[field]:value})
    if(field === 'lineHeight' && event.detail.value === '') delete componentConfig.lineHeight
    if (this.data.selectedComponentType === 'QR_CONTACT' && field === 'qrUrlSource') {
      if (this.data.componentConfig.qrUrlSource !== value) componentConfig.qrUrl = ''
      this.setData({componentConfig,complexError:''})
      return
    }
    const result=applyMockComponentConfig(this.data.draft,this.data.selectedComponentKey,componentConfig,this.data.activeMenuKey)
    if(!result.valid) {this.setData({componentConfig,complexError:result.message});return}
    this.setDraftState(result.draft,this.data.selectedComponentKey)
  },

  handleConfigChoice(event) {
    this.handleConfigInput({currentTarget:event.currentTarget,detail:{value:event.currentTarget.dataset.value}})
  },

  handleColorChange(event) {
    this.handleConfigInput({currentTarget:event.currentTarget,detail:{value:event.detail.color}})
  },

  handleSingleDescription(event) { this.setData({singleWorkShowDescription:Boolean(event.detail.value)}) },

  handleProfileVisibility(event) {
    if (!this.data.componentEditSheetVisible) return
    const key = event.currentTarget.dataset.field
    this.setDraftState(updateComponentConfig(this.data.draft,this.data.selectedComponentKey,config=>Object.assign({},config,{visibleFields:Object.assign({},config.visibleFields,{[key]:event.detail.value})}),this.data.activeMenuKey))
  },

  handleGroupSelect(event) {
    const index = Number(event.currentTarget.dataset.index)
    const group = (this.data.componentConfig.groups || [])[index]
    if (!group) return
    this.setData({selectedGroupIndex:index,selectedWorkIds:group.workIds,workOptions:buildFilteredWorkOptions(group.workIds,this.data.selectedComponentType,this.data.workFilterKeyword,this.data.selectedWorkFilterTagId)})
  },

  handleDisplayGroupSelect(event) {
    this.setDraftState(this.data.draft, null, { activeDisplayGroupKey: event.currentTarget.dataset.key })
  },

  handleDisplayGroupToggle(event) {
    if (!this.data.componentEditSheetVisible) return
    const key = event.currentTarget.dataset.key
    const option = this.data.displayGroupOptions.find(item => item.groupKey === key)
    if (!option) return
    let groups = this.data.componentConfig.groups || []
    if (groups.some(group => group.groupKey === key)) groups = groups.filter(group => group.groupKey !== key)
    else groups = groups.concat({ groupKey:key, name:option.name, sortOrder:(groups.length+1)*1000,
      workIds:buildFilteredWorkOptions([],this.data.selectedComponentType,'',option.tagId).map(work=>work.id) })
    groups = groups.map((group,index)=>Object.assign({},group,{sortOrder:(index+1)*1000}))
    const draft = updateComponentConfig(this.data.draft,this.data.selectedComponentKey,config=>Object.assign({},config,{groups,workIds:[...new Set(groups.flatMap(group=>group.workIds))]}),this.data.activeMenuKey)
    this.setDraftState(draft, null, { activeDisplayGroupKey: key })
  },

  handleGroupOperation(event) {
    if (!this.data.componentEditSheetVisible) return
    const {action,index,offset} = event.currentTarget.dataset
    const config = JSON.parse(JSON.stringify(this.data.componentConfig))
    const groups = config.groups || []
    const i = Number(index)
    if (action === 'add') groups.push({groupKey:`mock_group_${Date.now()}`,name:'新分组',sortOrder:(groups.length+1)*1000,workIds:[]})
    if (action === 'delete') { if(groups.length <= 1) {wx.showToast({title:'至少保留一个分组',icon:'none'});return} groups.splice(i,1) }
    if (action === 'move') { const target=i+Number(offset); if(target<0 || target>=groups.length)return; [groups[i],groups[target]]=[groups[target],groups[i]] }
    if (action === 'rename') groups[i].name = event.detail.value
    config.groups=groups.map((g,n)=>Object.assign({},g,{sortOrder:(n+1)*1000}))
    config.workIds=[...new Set(groups.flatMap(g=>g.workIds))]
    this.setDraftState(updateComponentConfig(this.data.draft,this.data.selectedComponentKey,()=>config,this.data.activeMenuKey))
  },

  handleQrWork(event) {
    if (!this.data.componentEditSheetVisible) return
    const work=MOCK_WORK_LIBRARY.works.find(w=>w.id === Number(event.currentTarget.dataset.id) && w.mediaType === 'IMAGE')
    if (!work) return
    this.setDraftState(updateComponentConfig(this.data.draft,this.data.selectedComponentKey,c=>Object.assign({},c,{qrUrlSource:'CUSTOM',qrUrl:work.mediaUrl}),this.data.activeMenuKey))
  },

  handleToggleHyperlinkPicker() { this.setData({hyperlinkPickerVisible:!this.data.hyperlinkPickerVisible}) },

  handleHyperlinkWork(event) {
    if (!this.data.componentEditSheetVisible) return
    const workId=Number(event.currentTarget.dataset.id)
    const work=selectMockWorksFor('HYPERLINK',MOCK_WORK_LIBRARY.works).find(item=>item.id === workId)
    if(!work) return
    this.setData({complexConfig:Object.assign({},this.data.complexConfig,{workId}),hyperlinkSelectedWork:work,hyperlinkPickerVisible:false})
  },

  handleFillContact() { this.setData({complexConfig:Object.assign({},this.data.complexConfig,MOCK_CONTACT_PROFILE)}) },

  handleTextBackgroundChange(event) {
    if (!this.data.componentEditSheetVisible) return
    if(this.data.selectedComponentType === 'STRUCTURED_TEXT_SECTION') this.handleComplexChange(event)
    else {
      const componentConfig=event.detail.config
      const result=applyMockComponentConfig(this.data.draft,this.data.selectedComponentKey,componentConfig,this.data.activeMenuKey)
      if(!result.valid) {this.setData({componentConfig,complexError:result.message});return}
      this.setDraftState(result.draft,this.data.selectedComponentKey)
    }
  },

  handleStructuredOperation(event) {
    if (!this.data.componentEditSheetVisible) return
    const op=event.detail, config=JSON.parse(JSON.stringify(op.config || this.data.complexConfig)), blocks=config.blocks || []
    if(op.type === 'addBlock' && blocks.length < 20) blocks.push(textTools.createMockStructuredBlock(op.blockType,`block_${Date.now()}`))
    if(op.type === 'removeBlock') blocks.splice(op.index,1)
    if(op.type === 'moveBlock') {const next=Number(op.index)+Number(op.offset);if(next>=0&&next<blocks.length) [blocks[op.index],blocks[next]]=[blocks[next],blocks[op.index]]}
    if(op.type === 'addListItem' && blocks[op.index].items.length<10) blocks[op.index].items.push('')
    if(op.type === 'removeListItem' && blocks[op.index].items.length>1) blocks[op.index].items.splice(op.item,1)
    this.setData({complexConfig:Object.assign(config,{blocks}),complexError:''})
    this.refreshMockFonts()
  },

  handleGridChange(event) {
    if (!this.data.componentEditSheetVisible) return
    try {
      if(!this.gridHistory) this.gridHistory=gridTools.createMockGridHistory(this.data.complexConfig,this.mockCandidateVersions || this.data.draft.config.fonts || {})
      this.gridHistory.apply(event.detail.config,this.mockCandidateVersions || {})
      this.gridHasInvalidDraft=false
      this.setData({complexConfig:event.detail.config,gridPreviewViewModel:buildGridPreview(event.detail.config,this.data.draft,this.gridPreviewMetrics),gridUndoCount:this.gridHistory.size(),complexError:''});this.refreshMockFonts()
    } catch(error) {
      this.gridHasInvalidDraft=true
      this.setData({complexConfig:event.detail.config,complexError:error.message})
    }
  },

  handleStructuredTextTabChange(event) {
    if (['content','background'].includes(event.detail.tab)) this.setData({structuredTextTab:event.detail.tab})
  },

  handleGridPreviewMeasure(event) {
    if (!this.data.componentEditSheetVisible || this.data.selectedComponentType !== 'TEXT_GRID') return
    const {widthPx,heightsPx} = event.detail
    if (!(widthPx > 0)) return
    const info = wx.getWindowInfo ? wx.getWindowInfo() : {windowWidth:375}
    const scale = 750 / (info.windowWidth || 375)
    this.gridPreviewMetrics = {widthRpx:widthPx*scale,heights:Object.fromEntries(Object.entries(heightsPx || {}).map(([key,height])=>[key,height*scale]))}
    this.setData({gridPreviewViewModel:buildGridPreview(this.data.complexConfig,this.data.draft,this.gridPreviewMetrics,this.mockFontContext())})
  },

  handleGridOperation(event) {
    if (!this.data.componentEditSheetVisible) return
    const op=event.detail
    try {
      if(!this.gridHistory) this.gridHistory=gridTools.createMockGridHistory(this.data.complexConfig,this.mockCandidateVersions || this.data.draft.config.fonts || {})
      let config=JSON.parse(JSON.stringify(op.config || this.data.complexConfig))
      if(op.type === 'undo' && this.gridHasInvalidDraft) {
        this.gridHasInvalidDraft=false
        const restored = this.gridHistory.get()
        this.mockCandidateVersions=this.gridHistory.getFonts()
        this.setData({complexConfig:restored,gridPreviewViewModel:buildGridPreview(restored,this.data.draft,this.gridPreviewMetrics),gridUndoCount:this.gridHistory.size(),complexError:''});this.refreshMockFonts()
        return
      }
      if(op.type === 'undo') {
        const restored = this.gridHistory.undo()
        this.mockCandidateVersions=this.gridHistory.getFonts()
        this.setData({complexConfig:restored,gridPreviewViewModel:buildGridPreview(restored,this.data.draft,this.gridPreviewMetrics),gridUndoCount:this.gridHistory.size(),complexError:''});this.refreshMockFonts();return
      }
      if(op.type === 'merge') config=gridTools.mergeMockGridCells(config,op.selectedKeys)
      if(op.type === 'split') config=gridTools.splitMockGridCell(config,op.cellKey || op.selectedKeys[0])
      if(op.type === 'resize') config=gridTools.resizeMockGrid(config,Number(op.rows),Number(op.columns))
      const cell=config.cells[op.cellIndex]
      if(op.type === 'addParagraph') {if(cell.blocks.length>=8)throw new Error('每格最多 8 段');cell.blocks.push(gridTools.createMockGridBlock())}
      if(op.type === 'removeParagraph') cell.blocks.splice(op.blockIndex,1)
      if(op.type === 'addRun') {if(cell.blocks[op.blockIndex].runs.length>=8)throw new Error('每段最多 8 个片段');cell.blocks[op.blockIndex].runs.push(gridTools.createMockGridRun())}
      if(op.type === 'removeRun') cell.blocks[op.blockIndex].runs.splice(op.runIndex,1)
      this.gridHistory.apply(config,this.mockCandidateVersions || {})
      this.gridHasInvalidDraft=false
      this.setData({complexConfig:config,gridPreviewViewModel:buildGridPreview(config,this.data.draft,this.gridPreviewMetrics),gridUndoCount:this.gridHistory.size(),complexError:''});this.refreshMockFonts()
    } catch(error) {this.setData({complexError:error.message})}
  },

  handleSpacingChange(event) {
    const value = Number(event.detail.value)
    if (!Number.isInteger(value) || value < COMPONENT_SPACING_MIN_RPX || value > COMPONENT_SPACING_MAX_RPX) return
    const draft=JSON.parse(JSON.stringify(this.data.draft))
    draft.config.style.componentSpacingRpx=value
    draft.renderData=buildMockPortfolioRenderData(draft.config)
    this.setDraftState(draft)
  },

  handleAudioSetting(event) {
    const {field,value} = event.currentTarget.dataset
    const next=event.detail && event.detail.value !== undefined ? event.detail.value : value
    if (field === 'displayStyle' && !AUDIO_STYLE_OPTIONS.some(item => item.value === next)) return
    if (field === 'workId' && !this.data.audioWorks.some(item => item.id === Number(next))) return
    if (!['enabled', 'workId', 'displayStyle', 'remove'].includes(field)) return
    const draft=JSON.parse(JSON.stringify(this.data.draft))
    draft.config.backgroundAudio=Object.assign({},draft.config.backgroundAudio,{[field]:next})
    if(field === 'enabled') draft.config.backgroundAudio.enabled=next === true
    if(field === 'workId') draft.config.backgroundAudio.workId=Number(next) || null
    if(field === 'remove') { delete draft.config.backgroundAudio.remove; draft.config.backgroundAudio.enabled=false; draft.config.backgroundAudio.workId=null }
    const closePicker = field === 'workId' || field === 'remove' || (field === 'enabled' && next !== true)
    if(closePicker && this.mockAudio) this.mockAudio.pause()
    draft.renderData=buildMockPortfolioRenderData(draft.config)
    this.setDraftState(draft, null, closePicker ? { backgroundAudioPickerVisible: false } : {})
  },

  handleChooseBackgroundAudio() {
    if (this.data.draft.config.backgroundAudio.enabled) this.setData({ backgroundAudioPickerVisible: true })
  },

  handleCloseBackgroundAudioPicker() {
    this.setData({ backgroundAudioPickerVisible: false })
  },

  handleAudioAudition() {
    const work=MOCK_WORK_LIBRARY.works.find(w=>w.id === this.data.draft.config.backgroundAudio.workId && w.mediaType === 'AUDIO')
    if(!work) {wx.showToast({title:'请先选择音频',icon:'none'});return}
    if(!this.mockAudio) this.mockAudio=createMockAudioController({wxApi:wx,onPlaying:playing=>this.setData({audioPlaying:playing}),onError:()=>wx.showToast({title:'音频播放失败，请点击重试',icon:'none'})})
    this.mockAudio.setResource({workId:work.id,mediaUrl:work.mediaUrl,enabled:true})
    this.mockAudio.toggle()
  },

  handleLockedAction() {
    showMockLoginRequiredToast()
  }
})
