const MAX_CAROUSEL_ITEMS = 9
const DEFAULT_INTERVAL = 5000
const CAROUSEL_WORK_ITEM_HEIGHT_RPX = 112
const CAROUSEL_WORK_ITEM_GAP_RPX = 14
const CAROUSEL_WORK_LIST_MIN_HEIGHT_RPX = 150
const CAROUSEL_WORK_LIST_MAX_HEIGHT_RPX = 616
const CAROUSEL_EDITOR_FIXED_HEIGHT_RPX = 310

function createDefaultCarouselConfig() {
  return { items: [] }
}

function positiveId(value) {
  const id = Number(value)
  return Number.isFinite(id) && id > 0 ? id : null
}

function normalizeCarouselItem(item = {}) {
  const normalized = {
    memberUserId: positiveId(item.memberUserId),
    workId: positiveId(item.workId)
  }
  const title = String(item.title || '').trim()
  const coverUrl = String(item.coverUrl || '').trim()
  const mediaUrl = String(item.mediaUrl || '').trim()
  if (title) normalized.title = title
  if (coverUrl) normalized.coverUrl = coverUrl
  if (mediaUrl) normalized.mediaUrl = mediaUrl
  if (Number(item.width) > 0) normalized.width = Number(item.width)
  if (Number(item.height) > 0) normalized.height = Number(item.height)
  if (item.aspectRatio) normalized.aspectRatio = String(item.aspectRatio)
  return normalized
}

function normalizePositiveNumber(value, fallback) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) && numberValue > 0 ? Math.round(numberValue) : fallback
}

/**
 * 根据当前作品数计算列表视口高度，少量作品时收拢，超过五项后在列表内滚动。
 */
function buildCarouselWorkListHeight(works = [], loading = false) {
  if (loading) return CAROUSEL_WORK_LIST_MIN_HEIGHT_RPX
  const count = Array.isArray(works) ? works.length : 0
  if (!count) return CAROUSEL_WORK_LIST_MIN_HEIGHT_RPX
  const contentHeight = count * CAROUSEL_WORK_ITEM_HEIGHT_RPX + (count - 1) * CAROUSEL_WORK_ITEM_GAP_RPX
  return Math.max(
    CAROUSEL_WORK_LIST_MIN_HEIGHT_RPX,
    Math.min(CAROUSEL_WORK_LIST_MAX_HEIGHT_RPX, contentHeight)
  )
}

/**
 * 计算页面级滚动区所需高度，固定区域包含成员选择、字段内边距和组件间距。
 */
function buildCarouselEditorScrollHeight(works = [], loading = false) {
  return CAROUSEL_EDITOR_FIXED_HEIGHT_RPX + buildCarouselWorkListHeight(works, loading)
}

function syncCarouselEditorLayout(component) {
  const workListHeightRpx = buildCarouselWorkListHeight(component.properties.works, component.properties.loading)
  component.setData({ workListHeightRpx })
  if (component.properties.editMode) {
    component.triggerEvent('layoutchange', {
      scrollHeightRpx: CAROUSEL_EDITOR_FIXED_HEIGHT_RPX + workListHeightRpx
    })
  }
}

function buildProgressSegments(items, current) {
  return (Array.isArray(items) ? items : []).map((item, index) => ({
    key: item && item.workId ? `work-${item.workId}` : `slide-${index}`,
    state: index < current ? 'done' : index === current ? 'current' : 'pending'
  }))
}

function validateCarouselConfig(config = {}) {
  const items = Array.isArray(config.items) ? config.items.map(normalizeCarouselItem) : []
  if (items.length === 0) return { valid: false, message: '请至少选择一张图片作品' }
  if (items.length > MAX_CAROUSEL_ITEMS) return { valid: false, message: '轮播图最多选择9张图片' }
  if (items.some((item) => !item.memberUserId || !item.workId)) return { valid: false, message: '轮播图作品来源无效' }
  return { valid: true, message: '' }
}

function fetchCarouselMembers(requestFn, portfolioId) {
  return requestFn({ url: `/api/mine/team-portfolios/${positiveId(portfolioId)}/components/carousel/members` })
}

function fetchCarouselWorks(requestFn, portfolioId, memberUserId) {
  const memberId = positiveId(memberUserId)
  if (!memberId) return Promise.reject(new Error('请先选择团队成员'))
  return requestFn({ url: `/api/mine/team-portfolios/${positiveId(portfolioId)}/components/carousel/members/${memberId}/works` })
}

function toggleCarouselWork(items, selected) {
  const normalized = normalizeCarouselItem(selected)
  if (!normalized.memberUserId || !normalized.workId) return Array.isArray(items) ? items.slice() : []
  const source = Array.isArray(items) ? items.map(normalizeCarouselItem) : []
  const index = source.findIndex((item) => item.workId === normalized.workId)
  if (index >= 0) return source.filter((_, itemIndex) => itemIndex !== index)
  return source.length >= MAX_CAROUSEL_ITEMS ? source : source.concat(normalized)
}

function buildCarouselConfig(items) {
  return {
    items: (Array.isArray(items) ? items : []).map((item) => ({
      memberUserId: positiveId(item.memberUserId),
      workId: positiveId(item.workId)
    })).filter((item) => item.memberUserId && item.workId)
  }
}

function syncCarouselDisplay(component, requestedCurrent) {
  const items = Array.isArray(component.properties.items) ? component.properties.items : []
  const candidate = requestedCurrent === undefined ? Number(component.data.current) : Number(requestedCurrent)
  const current = Number.isInteger(candidate) && candidate >= 0 && candidate < items.length ? candidate : 0
  const shouldRotate = items.length > 1
  const safeInterval = normalizePositiveNumber(component.properties.interval, DEFAULT_INTERVAL)
  component.setData({
    current,
    autoplay: shouldRotate,
    circular: shouldRotate,
    safeInterval,
    progressStyle: `animation-duration: ${safeInterval}ms;`,
    progressSegments: buildProgressSegments(items, current)
  })
}

function syncCarouselDraft(component, requestSources) {
  component.setData({ selectedMemberId: null, draftItems: (component.properties.items || []).map(normalizeCarouselItem), errorMessage: '' })
  if (requestSources) component.triggerEvent('loadmembers', { portfolioId: component.properties.portfolioId })
}

Component({
  properties: {
    themeMode: { type: String, value: 'light' },
    portfolioId: { type: Number, value: 0 },
    items: {
      type: Array,
      value: [],
      observer() {
        syncCarouselDisplay(this)
        if (!this.properties.editMode) syncCarouselDraft(this, false)
      }
    },
    members: { type: Array, value: [] },
    works: {
      type: Array,
      value: [],
      observer() { syncCarouselEditorLayout(this) }
    },
    loading: {
      type: Boolean,
      value: false,
      observer() { syncCarouselEditorLayout(this) }
    },
    editMode: {
      type: Boolean,
      value: false,
      observer(value, oldValue) {
        if (value !== oldValue) syncCarouselDraft(this, value === true)
        syncCarouselEditorLayout(this)
      }
    },
    interval: {
      type: Number,
      value: DEFAULT_INTERVAL,
      observer() { syncCarouselDisplay(this) }
    }
  },
  data: {
    current: 0,
    autoplay: false,
    circular: false,
    safeInterval: DEFAULT_INTERVAL,
    progressStyle: `animation-duration: ${DEFAULT_INTERVAL}ms;`,
    progressSegments: [],
    selectedMemberId: null,
    draftItems: [],
    workListHeightRpx: CAROUSEL_WORK_LIST_MIN_HEIGHT_RPX,
    errorMessage: ''
  },
  methods: {
    beginEdit() { syncCarouselDraft(this, true) },
    selectMember(event) { const memberUserId = positiveId(event.currentTarget.dataset.id); this.setData({ selectedMemberId: memberUserId }); this.triggerEvent('memberchange', { portfolioId: this.properties.portfolioId, memberUserId }) },
    toggleWork(event) { const item = event.currentTarget.dataset.item || {}; const draftItems = toggleCarouselWork(this.data.draftItems, Object.assign({}, item, { memberUserId: this.data.selectedMemberId })); this.setData({ draftItems }); this.triggerEvent('change', { items: draftItems }) },
    saveEdit() { const validation = validateCarouselConfig({ items: this.data.draftItems }); if (!validation.valid) return this.setData({ errorMessage: validation.message }); this.triggerEvent('save', { config: buildCarouselConfig(this.data.draftItems) }) },
    cancelEdit() { syncCarouselDraft(this, false); this.triggerEvent('cancel') },
    handleChange(event) {
      const requestedCurrent = Number(event.detail.current)
      syncCarouselDisplay(this, requestedCurrent)
      this.triggerEvent('changeindex', { current: this.data.current })
    },
    previewItem(event) { this.triggerEvent('preview', { item: event.currentTarget.dataset.item, index: event.currentTarget.dataset.index }) }
  },
  lifetimes: {
    attached() {
      syncCarouselDisplay(this)
      syncCarouselDraft(this, this.properties.editMode === true)
      syncCarouselEditorLayout(this)
    }
  }
})

module.exports = { CAROUSEL_WORK_LIST_MAX_HEIGHT_RPX, CAROUSEL_WORK_LIST_MIN_HEIGHT_RPX, DEFAULT_INTERVAL, MAX_CAROUSEL_ITEMS, buildCarouselConfig, buildCarouselEditorScrollHeight, buildCarouselWorkListHeight, buildProgressSegments, createDefaultCarouselConfig, fetchCarouselMembers, fetchCarouselWorks, normalizeCarouselItem, normalizePositiveNumber, toggleCarouselWork, validateCarouselConfig }
