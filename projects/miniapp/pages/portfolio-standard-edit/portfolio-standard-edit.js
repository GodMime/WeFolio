const { request } = require('../../utils/request')
const { handleAuthRequired, hasLocalToken } = require('../../utils/session')
const { normalizeWorkList } = require('../../utils/works')
const {
  createChoosePortfolioCoverOptions,
  uploadPortfolioCover
} = require('../../utils/portfolio-cover')
const {
  COMPONENT_NAMES,
  COMPONENT_TYPES,
  addComponent,
  buildDraftPayload,
  buildPublishPayload,
  createComponent,
  normalizePortfolioConfig,
  normalizeWorkIds,
  reorderComponent,
  removeComponent,
  updateComponentWorkIds
} = require('../../utils/portfolios')

const PORTFOLIO_API_PREFIX = '/api/mine/portfolios'
const STANDARD_PERSONAL_API_URL = '/api/mine/portfolios/standard-personal'
const COMPONENT_LIBRARY_API_URL = '/api/mine/portfolios/component-library'
const WORKS_API_URL = '/api/mine/works'
const PORTFOLIOS_PAGE_ROUTE = 'pages/portfolios/portfolios'
const PORTFOLIOS_PAGE_URL = `/${PORTFOLIOS_PAGE_ROUTE}`
const SWIPE_REVEAL_THRESHOLD = -32
const SWIPE_CLOSE_THRESHOLD = 24
const SWIPE_VERTICAL_TOLERANCE = 48
const COMPONENT_DRAG_SCALE = 1.015

const DEFAULT_COMPONENT_DESCRIPTIONS = {
  CAROUSEL: '展示已选择的图片作品',
  PROFILE: '展示个人资料和服务标签',
  SCHEDULE_QUERY: '开放访客查询档期',
  WORK_GRID: '双列展示图片和视频作品',
  QR_CONTACT: '展示二维码联系方式',
  CONTACT_FORM: '收集访客预留联系信息',
  TEXT_SECTION: '添加服务说明文字'
}

function makeIdempotencyKey(prefix) {
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`
}

function buildDefaultComponentOptions() {
  return Object.keys(COMPONENT_TYPES).map((key) => {
    const componentType = COMPONENT_TYPES[key]
    return {
      componentType,
      name: COMPONENT_NAMES[componentType],
      description: DEFAULT_COMPONENT_DESCRIPTIONS[componentType] || '标准个人作品集可选组件'
    }
  })
}

function getTouchClientY(event = {}) {
  const touch = (event.touches && event.touches[0]) || (event.changedTouches && event.changedTouches[0])
  const clientY = touch && Number(touch.clientY)
  return Number.isFinite(clientY) ? clientY : null
}

function getTouchClientX(event = {}) {
  const touch = (event.touches && event.touches[0]) || (event.changedTouches && event.changedTouches[0])
  const clientX = touch && Number(touch.clientX)
  return Number.isFinite(clientX) ? clientX : null
}

function buildComponentDragStyle(offsetY = 0) {
  const roundedOffset = Math.round(Number(offsetY) || 0)
  return `transform: translate3d(0, ${roundedOffset}px, 0) scale(${COMPONENT_DRAG_SCALE}); transition: transform 80ms linear, box-shadow 160ms ease, border-color 160ms ease, background 160ms ease; z-index: 3;`
}

function findComponentByKey(config = {}, componentKey) {
  return (config.components || []).find((component) => component.componentKey === componentKey) || null
}

function buildSelectedCountText(workIds = []) {
  return `${normalizeWorkIds(workIds).length} 已选`
}

function buildComponentWorkOptions(works = [], selectedIds = [], componentType = '') {
  const selectedSet = new Set(normalizeWorkIds(selectedIds))
  return works
    .filter((work) => componentType !== COMPONENT_TYPES.CAROUSEL || work.mediaType === 'IMAGE')
    .map((work) => Object.assign({}, work, {
      selected: selectedSet.has(work.id),
      thumbUrl: work.coverUrl || work.mediaUrl || '',
      metaText: work.tagText && work.tagText !== '未设置标签' ? `${work.typeText} · ${work.tagText}` : work.typeText
    }))
}

function resolvePortfolioListBackDelta() {
  const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
  for (let index = pages.length - 2; index >= 0; index -= 1) {
    if (pages[index] && pages[index].route === PORTFOLIOS_PAGE_ROUTE) {
      return pages.length - 1 - index
    }
  }
  return 0
}

function resolveChosenCoverPath(response = {}) {
  const files = Array.isArray(response.tempFiles) ? response.tempFiles : []
  const firstFile = files[0] || {}
  return firstFile.tempFilePath || firstFile.path || ''
}

Page({
  componentWorkRequestSeq: 0,

  data: {
    portfolioId: null,
    draftRevision: 0,
    publishedRevision: 0,
    draggingIndex: -1,
    dragTargetIndex: -1,
    componentDragStartY: null,
    componentDragStyle: '',
    revealedComponentKey: '',
    componentTouchStart: null,
    componentSheetVisible: false,
    componentOptions: buildDefaultComponentOptions(),
    componentWorkSheetVisible: false,
    componentWorkSheetTitle: '编辑轮播作品',
    componentWorkLoading: false,
    componentWorkErrorText: '',
    componentWorkOptions: [],
    componentWorkSelectedIds: [],
    componentWorkSelectedCountText: '0 已选',
    editingComponentKey: '',
    editingComponentType: '',
    config: normalizePortfolioConfig({
      components: [createComponent(COMPONENT_TYPES.PROFILE)]
    })
  },

  onLoad(options = {}) {
    this.setData({ portfolioId: options.portfolioId || null })
    this.bootstrap()
  },

  bootstrap() {
    if (!hasLocalToken()) {
      wx.navigateTo({ url: '/pages/login/login' })
      return
    }
    if (!this.data.portfolioId) {
      return
    }
    request({ url: `${PORTFOLIO_API_PREFIX}/${this.data.portfolioId}` })
      .then((response) => {
        this.setData({
          draftRevision: response.draftRevision || 0,
          publishedRevision: response.publishedRevision || 0,
          config: normalizePortfolioConfig(response.config || {})
        })
      })
      .catch((error) => {
        if (error.authRequired) {
          handleAuthRequired(error.message)
          return
        }
        wx.showToast({ title: error.message || '加载失败', icon: 'none' })
      })
  },

  handleShareInput(event) {
    const path = event.currentTarget.dataset.path
    const config = Object.assign({}, this.data.config)
    config.share = Object.assign({}, config.share)
    if (path === 'share.title') {
      config.share.title = event.detail.value
    }
    if (path === 'share.intro') {
      config.share.intro = event.detail.value
    }
    this.setData({ config: normalizePortfolioConfig(config) })
  },

  setShareCoverUrl(coverUrl) {
    const config = Object.assign({}, this.data.config)
    config.share = Object.assign({}, config.share, { coverUrl })
    this.setData({ config: normalizePortfolioConfig(config) })
  },

  handleChooseShareCover() {
    wx.chooseMedia(Object.assign({}, createChoosePortfolioCoverOptions(), {
      success: (response) => {
        const coverPath = resolveChosenCoverPath(response)
        if (coverPath) {
          this.setShareCoverUrl(coverPath)
        }
      },
      fail: (error) => {
        if (error && error.errMsg && !/cancel/i.test(error.errMsg)) {
          wx.showToast({ title: '选择封面失败', icon: 'none' })
        }
      }
    }))
  },

  handleRemoveShareCover() {
    this.setShareCoverUrl('')
  },

  handleOpenComponentSheet() {
    this.setData({ componentSheetVisible: true })
    this.loadComponentOptions()
  },

  loadComponentOptions() {
    request({ url: COMPONENT_LIBRARY_API_URL })
      .then((response) => {
        if (response && Array.isArray(response.components) && response.components.length > 0) {
          this.setData({ componentOptions: response.components })
        }
      })
      .catch(() => {})
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
    this.setData({
      config: addComponent(this.data.config, componentType),
      componentSheetVisible: false
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
    wx.createSelectorQuery()
      .in(this)
      .selectAll('.component-row')
      .boundingClientRect((rows = []) => {
        this.componentDragRows = rows
      })
      .exec()
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
    const nextState = {
      draggingIndex: -1,
      dragTargetIndex: -1,
      componentDragStartY: null,
      componentDragStyle: '',
      componentTouchStart: null
    }
    if (draggingIndex >= 0 && dragTargetIndex >= 0 && draggingIndex !== dragTargetIndex) {
      nextState.config = reorderComponent(this.data.config, draggingIndex, dragTargetIndex)
    }
    this.componentDragRows = []
    this.setData(nextState)
  },

  handleComponentTap(event) {
    const componentKey = event.currentTarget.dataset.key || ''
    const componentType = event.currentTarget.dataset.type || ''
    if (this.data.revealedComponentKey === componentKey) {
      this.setData({ revealedComponentKey: '' })
      return undefined
    }
    if (componentType === COMPONENT_TYPES.CAROUSEL) {
      return this.openComponentWorkSheet(componentKey, componentType)
    }
    return undefined
  },

  handleRemoveComponent(event) {
    this.setData({
      config: removeComponent(this.data.config, event.currentTarget.dataset.key),
      revealedComponentKey: ''
    })
  },

  openComponentWorkSheet(componentKey, componentType) {
    const component = findComponentByKey(this.data.config, componentKey)
    if (!component) {
      return Promise.resolve()
    }
    const selectedIds = normalizeWorkIds(component.config && component.config.workIds)
    this.setData({
      componentWorkSheetVisible: true,
      componentWorkSheetTitle: componentType === COMPONENT_TYPES.CAROUSEL ? '编辑轮播作品' : '编辑展示作品',
      componentWorkLoading: true,
      componentWorkErrorText: '',
      componentWorkOptions: buildComponentWorkOptions(this.data.componentWorkOptions, selectedIds, componentType),
      componentWorkSelectedIds: selectedIds,
      componentWorkSelectedCountText: buildSelectedCountText(selectedIds),
      editingComponentKey: componentKey,
      editingComponentType: componentType
    })
    return this.loadComponentWorks(componentType, selectedIds)
  },

  async loadComponentWorks(componentType = this.data.editingComponentType, selectedIds = this.data.componentWorkSelectedIds) {
    const requestSeq = this.componentWorkRequestSeq + 1
    this.componentWorkRequestSeq = requestSeq
    try {
      const response = await request({
        url: WORKS_API_URL,
        data: {
          page: 1,
          pageSize: 100
        }
      })
      if (requestSeq !== this.componentWorkRequestSeq) {
        return
      }
      const works = normalizeWorkList(response).works
      this.setData({
        componentWorkLoading: false,
        componentWorkErrorText: '',
        componentWorkOptions: buildComponentWorkOptions(works, selectedIds, componentType)
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ componentWorkLoading: false })
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        componentWorkLoading: false,
        componentWorkErrorText: error && error.message ? error.message : '作品加载失败'
      })
    }
  },

  handleRetryLoadComponentWorks() {
    this.setData({
      componentWorkLoading: true,
      componentWorkErrorText: ''
    })
    this.loadComponentWorks()
  },

  handleCloseComponentWorkSheet() {
    this.setData({
      componentWorkSheetVisible: false,
      editingComponentKey: '',
      editingComponentType: '',
      componentWorkErrorText: ''
    })
  },

  handleToggleComponentWork(event) {
    const workId = Number(event.currentTarget.dataset.id)
    if (!Number.isFinite(workId) || workId <= 0) {
      return
    }
    const selectedIds = normalizeWorkIds(this.data.componentWorkSelectedIds)
    const nextSelectedIds = selectedIds.includes(workId)
      ? selectedIds.filter((id) => id !== workId)
      : selectedIds.concat(workId)
    this.setData({
      componentWorkSelectedIds: nextSelectedIds,
      componentWorkSelectedCountText: buildSelectedCountText(nextSelectedIds),
      componentWorkOptions: buildComponentWorkOptions(this.data.componentWorkOptions, nextSelectedIds, this.data.editingComponentType)
    })
  },

  handleConfirmComponentWorks() {
    if (!this.data.editingComponentKey) {
      return
    }
    this.setData({
      config: updateComponentWorkIds(this.data.config, this.data.editingComponentKey, this.data.componentWorkSelectedIds),
      componentWorkSheetVisible: false,
      editingComponentKey: '',
      editingComponentType: ''
    })
  },

  ensureDraftPortfolio() {
    if (this.data.portfolioId) {
      return Promise.resolve(this.data.portfolioId)
    }
    return request({
      url: STANDARD_PERSONAL_API_URL,
      method: 'POST',
      data: {
        config: this.data.config
      }
    }).then((response = {}) => {
      const portfolioId = response.portfolioId
      if (!portfolioId) {
        throw new Error('创建失败')
      }
      this.setData({
        portfolioId,
        draftRevision: response.draftRevision || 0,
        publishedRevision: response.publishedRevision || 0
      })
      return portfolioId
    })
  },

  saveDraftForPortfolio(portfolioId) {
    return request({
      url: `${PORTFOLIO_API_PREFIX}/${portfolioId}/draft`,
      method: 'PUT',
      data: buildDraftPayload(this.data.config, this.data.draftRevision, makeIdempotencyKey('draft'))
    }).then((response) => {
      this.setData({
        portfolioId: response.portfolioId || portfolioId,
        draftRevision: response.draftRevision || this.data.draftRevision,
        publishedRevision: response.publishedRevision || this.data.publishedRevision
      })
      wx.showToast({ title: '草稿已保存', icon: 'success' })
      this.returnToPortfolioList()
    })
  },

  uploadLocalShareCover(portfolioId) {
    const coverUrl = this.data.config.share && this.data.config.share.coverUrl
    if (!coverUrl) {
      return Promise.resolve('')
    }
    return uploadPortfolioCover(portfolioId, coverUrl).then((uploadedUrl) => {
      if (uploadedUrl && uploadedUrl !== coverUrl) {
        this.setShareCoverUrl(uploadedUrl)
      }
      return uploadedUrl || coverUrl
    })
  },

  handleSaveDraft() {
    return this.ensureDraftPortfolio().then((portfolioId) => {
      return this.uploadLocalShareCover(portfolioId).then(() => this.saveDraftForPortfolio(portfolioId))
    }).catch((error) => {
      wx.showToast({ title: error.message || '保存失败', icon: 'none' })
    })
  },

  returnToPortfolioList() {
    const delta = resolvePortfolioListBackDelta()
    if (delta > 0) {
      wx.navigateBack({ delta })
      return
    }
    wx.redirectTo({ url: PORTFOLIOS_PAGE_URL })
  },

  handlePreview() {
    wx.navigateTo({ url: `/pages/portfolio-standard-preview/portfolio-standard-preview?portfolioId=${this.data.portfolioId}` })
  },

  handlePublish() {
    request({
      url: `${PORTFOLIO_API_PREFIX}/${this.data.portfolioId}/publish`,
      method: 'POST',
      data: buildPublishPayload(this.data.draftRevision, makeIdempotencyKey('publish'))
    }).then((response) => {
      this.setData({ publishedRevision: response.publishedRevision || this.data.publishedRevision })
      wx.showToast({ title: '已发布', icon: 'success' })
    }).catch((error) => {
      wx.showToast({ title: error.message || '发布失败', icon: 'none' })
    })
  }
})
