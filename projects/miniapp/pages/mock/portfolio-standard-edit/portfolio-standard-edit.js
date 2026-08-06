const {
  MOCK_COMPONENT_OPTIONS,
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
  PORTFOLIO_TEXT_FONT_OPTIONS,
  buildPortfolioTextFontSizeOptions,
  buildPortfolioTextTypography
} = require('../../../utils/portfolio-text-typography')
const {
  hexToHsv,
  hsvToHex
} = require('../../../utils/portfolio-color')

const PREVIEW_URL = '/pages/mock/portfolio-standard-preview/portfolio-standard-preview'
const DEFAULT_SELECTED_COMPONENT_KEY = 'mock_carousel'
const SWIPE_REVEAL_THRESHOLD = -48
const SWIPE_CLOSE_THRESHOLD = 28
const SWIPE_VERTICAL_TOLERANCE = 26
const BACKGROUND_COLOR_OPTIONS = ['#151515', '#FFFFFF', '#F5F6F8']
const BOTTOM_NAV_COUNT_OPTIONS = [1, 2, 3, 4]
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
  return componentType === 'CAROUSEL' || componentType === 'WORK_GRID' || componentType === 'WORK_LIST' || componentType === 'SINGLE_WORK'
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

function buildWorkOptions(workIds, carouselOnly) {
  const selectedIds = new Set((workIds || []).map(Number))
  return MOCK_WORK_LIBRARY.works
    .filter((work) => !carouselOnly || work.mediaType === 'IMAGE')
    .map((work) => Object.assign({}, work, {
      selected: selectedIds.has(work.id)
    }))
}

function buildFilteredWorkOptions(workIds, carouselOnly, keyword = '', selectedTagId = 0) {
  const normalizedKeyword = trimText(keyword).toLowerCase()
  const normalizedTagId = Number(selectedTagId) || 0
  return buildWorkOptions(workIds, carouselOnly).filter((work) => {
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

function buildState(draft, selectedComponentKey = DEFAULT_SELECTED_COMPONENT_KEY, activeMenuKey) {
  const safeActiveMenuKey = resolveActiveMenuKey(draft, activeMenuKey)
  const activeComponents = getMockMenuComponents(draft.config, safeActiveMenuKey)
  const safeSelectedKey = findComponent(draft, selectedComponentKey, safeActiveMenuKey).componentKey || ''
  const selectedComponent = findComponent(draft, safeSelectedKey, safeActiveMenuKey)
  const selectedWorkIds = getWorkIdsFromComponent(selectedComponent)
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
    scheduleQueryForm: Object.assign({}, findConfig(selectedComponent)),
    contactFormConfig: Object.assign({}, findConfig(selectedComponent)),
    qrContactForm: Object.assign({}, findConfig(selectedComponent)),
    textSectionForm: Object.assign({}, textSectionConfig, textSectionTypography, {
      textAlign: textSectionConfig.alignment || 'LEFT'
    }),
    textSectionTypography,
    textSectionFontOptions: PORTFOLIO_TEXT_FONT_OPTIONS.map((item) => Object.assign({}, item, {
      available: true
    })),
    textSectionSizeOptions: buildPortfolioTextFontSizeOptions(textSectionTypography.fontSizeRpx),
    dividerForm: Object.assign({}, findConfig(selectedComponent)),
    textSectionAlignmentOptions: TEXT_SECTION_ALIGNMENT_OPTIONS,
    dividerColorOptions: DIVIDER_COLOR_OPTIONS,
    selectedWorkIds,
    workFilterKeyword: '',
    selectedWorkFilterTagId: 0,
    workFilterTags: [{ id: 0, name: '全部', color: '#212529' }].concat(MOCK_WORK_LIBRARY.tags),
    singleWorkShowTitle: selectedComponent.componentType === 'SINGLE_WORK'
      ? typeof selectedComponent.config.showTitle === 'boolean' ? selectedComponent.config.showTitle : true
      : true,
    componentOptions: MOCK_COMPONENT_OPTIONS,
    workOptions: buildFilteredWorkOptions(
      isWorkSelectionComponent(selectedComponent.componentType) ? selectedWorkIds : [],
      selectedComponent.componentType === 'CAROUSEL'
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
    backgroundColorSheetVisible: false
  }, buildState(getMockPortfolioDraft())),

  onLoad() {
    this.loadDraft()
  },

  onShow() {
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
          : this.data.activeMenuKey
      ),
      nextExtraData
    ))
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
    const componentKey = event.currentTarget.dataset.key || event.currentTarget.dataset.componentKey
    if (!componentKey) {
      return
    }
    if (this.data.revealedComponentKey === componentKey) {
      this.setData({ revealedComponentKey: '' })
      return
    }
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

  handleScheduleInput(event) {
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
    const fontFamily = event.currentTarget.dataset.value || 'SYSTEM'
    const componentKey = this.data.selectedComponentKey
    if (this.data.selectedComponentType !== 'TEXT_SECTION') {
      return
    }
    const draft = updateComponentConfig(this.data.draft, componentKey, (config) => {
      return Object.assign({}, config, { fontFamily })
    }, this.data.activeMenuKey)
    this.setDraftState(draft, componentKey)
  },

  handleTextSectionFontSizeTap(event) {
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

  handleDividerColorTap(event) {
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
    const workId = Number(event.currentTarget.dataset.id || 0)
    if (!workId || !isWorkSelectionComponent(this.data.selectedComponentType)) {
      return
    }
    const componentKey = this.data.selectedComponentKey
    const currentIds = this.data.selectedWorkIds || []
    if (this.data.selectedComponentType === 'SINGLE_WORK') {
      if (currentIds[0] === workId) {
        return
      }
      const selectedWorkIds = [workId]
      this.setData({
        selectedWorkIds,
        workOptions: buildFilteredWorkOptions(
          selectedWorkIds,
          false,
          this.data.workFilterKeyword,
          this.data.selectedWorkFilterTagId
        )
      })
      return
    }
    const selected = currentIds.includes(workId)
    const nextIds = selected
      ? currentIds.filter((id) => id !== workId)
      : currentIds.concat(workId)
    const draft = updateComponentConfig(this.data.draft, componentKey, (config) => {
      if (this.data.selectedComponentType === 'WORK_GRID' || this.data.selectedComponentType === 'WORK_LIST') {
        return Object.assign({}, config, {
          workIds: nextIds,
          groups: [{
            groupKey: 'g_all',
            name: '全部作品',
            sortOrder: 1000,
            workIds: nextIds
          }]
        })
      }
      return Object.assign({}, config, {
        workIds: nextIds
      })
    }, this.data.activeMenuKey)
    this.setDraftState(draft, componentKey, {
      workFilterKeyword: this.data.workFilterKeyword,
      selectedWorkFilterTagId: this.data.selectedWorkFilterTagId,
      workOptions: buildFilteredWorkOptions(
        nextIds,
        this.data.selectedComponentType === 'CAROUSEL',
        this.data.workFilterKeyword,
        this.data.selectedWorkFilterTagId
      )
    })
  },

  handleWorkFilterInput(event) {
    const workFilterKeyword = event.detail && event.detail.value ? event.detail.value : ''
    this.setData({
      workFilterKeyword,
      workOptions: buildFilteredWorkOptions(
        this.data.selectedWorkIds,
        this.data.selectedComponentType === 'CAROUSEL',
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
        this.data.selectedComponentType === 'CAROUSEL',
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
    const componentType = event.currentTarget.dataset.type
    if (!componentType) {
      return
    }
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
    this.setData({ componentEditSheetVisible: false })
  },

  handleConfirmComponentEditSheet() {
    if (this.data.selectedComponentType === 'SINGLE_WORK') {
      const workId = Number((this.data.selectedWorkIds || [])[0]) || 0
      if (!workId) {
        wx.showToast({ title: '请选择一个作品', icon: 'none' })
        return
      }
      const draft = updateMockSingleWorkConfig(this.data.draft, this.data.selectedComponentKey, {
        workId,
        showTitle: this.data.singleWorkShowTitle
      }, this.data.activeMenuKey)
      this.setDraftState(draft, this.data.selectedComponentKey, {
        componentEditSheetVisible: false
      })
      return
    }
    this.setData({ componentEditSheetVisible: false })
  },

  handlePreview() {
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
    this.setDraftState(resetMockPortfolioDraft(), DEFAULT_SELECTED_COMPONENT_KEY)
  },

  handleSaveDraft() {
    showMockLoginRequiredToast()
  },

  handlePublish() {
    showMockLoginRequiredToast()
  },

  handleLockedAction() {
    showMockLoginRequiredToast()
  }
})
