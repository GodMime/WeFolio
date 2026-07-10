const {
  MOCK_COMPONENT_OPTIONS,
  MOCK_WORK_LIBRARY,
  addMockComponent,
  getMockPortfolioDraft,
  getWorkIdsFromComponent,
  removeMockComponent,
  resetMockPortfolioDraft,
  reorderMockComponent,
  saveMockPortfolioDraft,
  showMockLoginRequiredToast,
  trimText,
  updateComponentConfig
} = require('../utils/mock-experience')

const PREVIEW_URL = '/pages/mock/portfolio-standard-preview/portfolio-standard-preview'
const DEFAULT_SELECTED_COMPONENT_KEY = 'mock_carousel'
const SWIPE_REVEAL_THRESHOLD = -48
const SWIPE_CLOSE_THRESHOLD = 28
const SWIPE_VERTICAL_TOLERANCE = 26
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

function isWorkSelectionComponent(componentType) {
  return componentType === 'CAROUSEL' || componentType === 'WORK_GRID' || componentType === 'WORK_LIST'
}

function findComponent(draft, componentKey) {
  const components = draft && draft.config && Array.isArray(draft.config.components)
    ? draft.config.components
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

function buildComponentRows(draft, selectedComponentKey) {
  const components = draft.config && Array.isArray(draft.config.components)
    ? draft.config.components
    : []
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

function buildState(draft, selectedComponentKey = DEFAULT_SELECTED_COMPONENT_KEY) {
  const safeSelectedKey = findComponent(draft, selectedComponentKey).componentKey || DEFAULT_SELECTED_COMPONENT_KEY
  const selectedComponent = findComponent(draft, safeSelectedKey)
  const selectedWorkIds = getWorkIdsFromComponent(selectedComponent)
  return {
    draft,
    componentRows: buildComponentRows(draft, safeSelectedKey),
    selectedComponentKey: safeSelectedKey,
    selectedComponentType: selectedComponent.componentType || '',
    selectedComponentName: selectedComponent.name || '',
    profileForm: Object.assign({}, findProfileConfig(selectedComponent)),
    scheduleQueryForm: Object.assign({}, findConfig(selectedComponent)),
    contactFormConfig: Object.assign({}, findConfig(selectedComponent)),
    qrContactForm: Object.assign({}, findConfig(selectedComponent)),
    textSectionForm: Object.assign({}, findConfig(selectedComponent)),
    dividerForm: Object.assign({}, findConfig(selectedComponent)),
    textSectionAlignmentOptions: TEXT_SECTION_ALIGNMENT_OPTIONS,
    dividerColorOptions: DIVIDER_COLOR_OPTIONS,
    selectedWorkIds,
    componentOptions: MOCK_COMPONENT_OPTIONS,
    workOptions: buildWorkOptions(
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
    revealedComponentKey: ''
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
      Object.assign(buildState(getMockPortfolioDraft(), this.data.selectedComponentKey), nextOptions.extraData || {}),
      () => {
        if (nextOptions.resetViewport) {
          this.resetEditViewport()
        }
      }
    )
  },

  setDraftState(draft, selectedComponentKey, extraData) {
    this.setData(Object.assign(
      buildState(draft, selectedComponentKey || this.data.selectedComponentKey),
      extraData || {}
    ))
  },

  resetEditViewport() {
    this.setData({ editScrollTop: 1 }, () => {
      this.setData({ editScrollTop: 0 })
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
    })
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
    })
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
    })
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
    })
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
    })
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
    })
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
    })
    this.setDraftState(draft, componentKey)
  },

  handleWorkToggle(event) {
    const workId = Number(event.currentTarget.dataset.id || 0)
    if (!workId || !isWorkSelectionComponent(this.data.selectedComponentType)) {
      return
    }
    const componentKey = this.data.selectedComponentKey
    const currentIds = this.data.selectedWorkIds || []
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
    })
    this.setDraftState(draft, componentKey)
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
    const currentKeys = new Set((this.data.draft.config.components || []).map((component) => component.componentKey))
    const draft = addMockComponent(this.data.draft, componentType)
    const addedComponent = (draft.config.components || []).find((component) => !currentKeys.has(component.componentKey)) ||
      (draft.config.components || [])[draft.config.components.length - 1] ||
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
        reorderMockComponent(this.data.draft, draggingIndex, dragTargetIndex),
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
    const draft = removeMockComponent(this.data.draft, componentKey)
    const components = draft.config.components || []
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
