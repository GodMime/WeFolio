const { normalizeId } = require('./id')
const {
  DEFAULT_TAG_COLOR,
  TAG_COLOR_OPTIONS,
  TAG_MAX_COUNT,
  TAG_MAX_LENGTH
} = require('./profile')

const TITLE_LIMIT = 30
const DESCRIPTION_LIMIT = 1000
const FILTER_TAG_LABEL_LIMIT = 10
const WORK_TAG_DELETE_BLOCKED_SUFFIX = '先移除这些作品的标签后再删除。'
const FILTER_ALL_ACTIVE_STYLE = 'color: #40546a; background: #eef4f7; border-color: #cbd8e5;'
const WORK_TAG_PICKER_MIN_HEIGHT = 80
const WORK_TAG_PICKER_ROW_STEP = 72
const WORK_TAG_PICKER_MAX_HEIGHT = 520

const MEDIA_TYPE_TEXT = {
  IMAGE: '图片',
  VIDEO: '视频'
}

const TAG_COLOR_MAP = TAG_COLOR_OPTIONS.reduce((result, item) => {
  result[item.color] = item
  return result
}, {})

function trimText(value) {
  return String(value || '').trim()
}

function countText(value) {
  return Array.from(String(value || '')).length
}

function limitText(value, limit) {
  return Array.from(String(value || '')).slice(0, limit).join('')
}

function toNumber(value, fallback = 0) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : fallback
}

function formatFilterTagLabel(name, count) {
  return limitText(`${name} ${count}`, FILTER_TAG_LABEL_LIMIT)
}

function normalizeWorkTagColor(color) {
  const value = trimText(color).toLowerCase()
  return TAG_COLOR_MAP[value] ? value : ''
}

function getWorkTagColorOption(color) {
  return TAG_COLOR_MAP[normalizeWorkTagColor(color)] || TAG_COLOR_MAP[DEFAULT_TAG_COLOR]
}

function buildTagStyle(color) {
  if (!color) {
    return ''
  }
  const option = getWorkTagColorOption(color)
  return `color: ${option.color}; background: ${option.background}; border-color: ${option.border};`
}

function buildTagActiveStyle(color) {
  if (!color) {
    return FILTER_ALL_ACTIVE_STYLE
  }
  const option = getWorkTagColorOption(color)
  return `color: #ffffff; background: ${option.color}; border-color: ${option.color};`
}

function buildTagDeleteStyle(color) {
  if (!color) {
    return ''
  }
  const option = getWorkTagColorOption(color)
  return `color: #ffffff; background: ${option.color};`
}

function formatDuration(milliseconds) {
  const totalSeconds = Math.max(0, Math.floor(toNumber(milliseconds) / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

function formatFileSize(bytes) {
  const value = toNumber(bytes)
  if (value <= 0) {
    return ''
  }
  if (value >= 1024 * 1024) {
    return `${(value / 1024 / 1024).toFixed(1)}MB`
  }
  if (value >= 1024) {
    return `${Math.round(value / 1024)}KB`
  }
  return `${value}B`
}

function normalizeTag(raw = {}) {
  const count = toNumber(raw.count)
  const name = trimText(raw.name) || '未命名标签'
  const color = normalizeWorkTagColor(raw.color)
  return {
    id: normalizeId(raw.id),
    name,
    color,
    count,
    active: Boolean(raw.active),
    labelText: formatFilterTagLabel(name, count),
    style: buildTagStyle(color),
    activeStyle: buildTagActiveStyle(color),
    deleteStyle: buildTagDeleteStyle(color)
  }
}

function normalizeWork(raw = {}) {
  const mediaType = trimText(raw.mediaType) || 'IMAGE'
  const tags = Array.isArray(raw.tags) ? raw.tags.map(normalizeTag) : []
  const coverUrl = trimText(raw.coverUrl)
  const referenceCount = toNumber(raw.referenceCount)
  return {
    id: normalizeId(raw.id),
    mediaType,
    typeText: MEDIA_TYPE_TEXT[mediaType] || '作品',
    title: trimText(raw.title) || '未命名作品',
    originalFileName: trimText(raw.originalFileName),
    mediaUrl: trimText(raw.mediaUrl),
    coverUrl,
    hasCover: Boolean(coverUrl),
    mimeType: trimText(raw.mimeType),
    fileSize: toNumber(raw.fileSize),
    fileSizeText: formatFileSize(raw.fileSize),
    durationMs: toNumber(raw.durationMs),
    durationText: mediaType === 'VIDEO' ? formatDuration(raw.durationMs) : '',
    width: toNumber(raw.width),
    height: toNumber(raw.height),
    description: trimText(raw.description),
    serviceDate: trimText(raw.serviceDate),
    sortOrder: toNumber(raw.sortOrder),
    status: trimText(raw.status),
    referenceCount,
    referenceText: referenceCount > 0 ? `引用 ${referenceCount} 次` : '未引用',
    tags,
    tagText: tags.length > 0 ? tags.map((item) => item.name).join('、') : '未设置标签',
    createdAt: trimText(raw.createdAt),
    updatedAt: trimText(raw.updatedAt)
  }
}

function normalizeSummary(raw = {}) {
  const totalCount = toNumber(raw.totalCount)
  const imageCount = toNumber(raw.imageCount)
  const videoCount = toNumber(raw.videoCount)
  return {
    totalCount,
    imageCount,
    videoCount,
    totalText: `全部 ${totalCount}`,
    imageText: `图片 ${imageCount}`,
    videoText: `视频 ${videoCount}`
  }
}

function isAllTag(tag = {}) {
  return !tag.id && tag.name === '全部'
}

function buildFilterTags(summary, tags) {
  if (tags.some(isAllTag)) {
    return tags
  }
  return [
    {
      id: null,
      name: '全部',
      color: '',
      count: summary.totalCount,
      active: true,
      labelText: formatFilterTagLabel('全部', summary.totalCount),
      activeStyle: buildTagActiveStyle('')
    },
    ...tags
  ]
}

function normalizeWorkList(raw = {}) {
  const works = Array.isArray(raw.works) ? raw.works.map(normalizeWork) : []
  const summary = normalizeSummary(raw.summary)
  const tags = Array.isArray(raw.tags) ? raw.tags.map(normalizeTag) : []
  return {
    page: toNumber(raw.page, 1) || 1,
    pageSize: toNumber(raw.pageSize, 20) || 20,
    total: toNumber(raw.total),
    hasMore: Boolean(raw.hasMore),
    summary,
    tags,
    filterTags: buildFilterTags(summary, tags),
    works,
    empty: works.length === 0
  }
}

function normalizeWorkTags(raw = {}) {
  const source = Array.isArray(raw) ? raw : raw.tags
  const tags = Array.isArray(source) ? source : []
  return tags.map(normalizeTag).filter((item) => item.id)
}

function getTagName(tag) {
  if (tag && typeof tag === 'object') {
    return trimText(tag.name)
  }
  return trimText(tag)
}

function normalizeTagNames(tagNames = []) {
  if (!Array.isArray(tagNames)) {
    return []
  }
  return Array.from(new Set(tagNames.map(getTagName).filter(Boolean)))
}

function buildWorkTagPickerOptions(tags = [], selectedTagNames = []) {
  const selectedSet = new Set(normalizeTagNames(selectedTagNames))
  return normalizeWorkTags(tags).map((tag) => Object.assign({}, tag, {
    selected: selectedSet.has(tag.name)
  }))
}

function buildWorkTagOptionListHeight(tags = []) {
  const count = Array.isArray(tags) ? tags.length : Math.max(0, Math.round(toNumber(tags)))
  if (count <= 0) {
    return 0
  }
  return Math.min(WORK_TAG_PICKER_MAX_HEIGHT, WORK_TAG_PICKER_MIN_HEIGHT + (count - 1) * WORK_TAG_PICKER_ROW_STEP)
}

function buildUnifiedWorkTagNames(tags = []) {
  if (!Array.isArray(tags)) {
    return []
  }
  return normalizeTagNames(tags
    .filter((tag) => tag && tag.selected)
    .map((tag) => tag.name))
}

function buildUnifiedWorkTagItems(tags = []) {
  if (!Array.isArray(tags)) {
    return []
  }
  const seen = {}
  return tags.reduce((result, tag) => {
    if (!tag || !tag.selected) {
      return result
    }
    const item = normalizeTag(tag)
    if (!item.name || seen[item.name]) {
      return result
    }
    seen[item.name] = true
    result.push(item)
    return result
  }, [])
}

function applyUnifiedWorkTags(files = [], tagNames = []) {
  const tags = normalizeTagNames(tagNames)
  if (!Array.isArray(files)) {
    return []
  }
  return files.map((file) => Object.assign({}, file, {
    tags: tags.slice()
  }))
}

function normalizeReference(raw = {}) {
  const portfolioId = normalizeId(raw.portfolioId)
  return {
    id: normalizeId(raw.id),
    portfolioId,
    componentKey: trimText(raw.componentKey),
    componentPath: trimText(raw.componentPath),
    valid: raw.valid !== false,
    titleText: portfolioId ? `作品集 #${portfolioId}` : '作品集引用'
  }
}

function normalizeWorkDetail(raw = {}) {
  const work = normalizeWork(raw.work || {})
  const references = Array.isArray(raw.references) ? raw.references.map(normalizeReference) : []
  return {
    work,
    references,
    referenceSummaryText: references.length > 0
      ? `已被 ${references.length} 个作品集引用`
      : '暂未被作品集引用'
  }
}

function buildTagNames(form = {}) {
  const names = Array.isArray(form.tags) ? form.tags.map(trimText) : []
  const input = trimText(form.tagInput)
  if (input) {
    names.push(input)
  }
  return Array.from(new Set(names.filter(Boolean)))
}

function buildWorkUpdatePayload(form = {}) {
  const payload = {
    title: trimText(form.title),
    description: trimText(form.description)
  }
  if (Object.prototype.hasOwnProperty.call(form, 'coverFrameTimeMs')) {
    const frameTimeMs = Math.max(0, Math.round(toNumber(form.coverFrameTimeMs)))
    payload.coverFrameTimeMs = frameTimeMs
  }
  if (Object.prototype.hasOwnProperty.call(form, 'coverTaskId')) {
    const coverTaskId = normalizeId(form.coverTaskId)
    if (coverTaskId) {
      payload.coverTaskId = coverTaskId
    }
  }
  const width = Math.max(0, Math.round(toNumber(form.width)))
  const height = Math.max(0, Math.round(toNumber(form.height)))
  if (width > 0 && height > 0) {
    payload.width = width
    payload.height = height
  }
  return payload
}

function createWorkTagForm(raw = {}) {
  const color = normalizeWorkTagColor(raw.color) || DEFAULT_TAG_COLOR
  return {
    id: normalizeId(raw.id),
    name: trimText(raw.name),
    color
  }
}

function buildWorkTagPayload(form = {}) {
  return {
    name: trimText(form.name),
    color: trimText(form.color).toLowerCase()
  }
}

function validateWorkTagForm(form = {}, existingTags = [], currentTagId = null) {
  const payload = buildWorkTagPayload(form)
  const editingTagId = normalizeId(currentTagId || form.id)
  if (!payload.name) {
    return { valid: false, message: '标签名称不能为空' }
  }
  if (countText(payload.name) > TAG_MAX_LENGTH) {
    return { valid: false, message: '单个标签不能超过 10 个字' }
  }
  if (!normalizeWorkTagColor(payload.color)) {
    return { valid: false, message: '请选择有效的标签颜色' }
  }
  const existingCustomTags = Array.isArray(existingTags)
    ? existingTags.filter((item) => normalizeId(item && item.id))
    : []
  if (!editingTagId && existingCustomTags.length >= TAG_MAX_COUNT) {
    return { valid: false, message: '标签最多保留 10 个' }
  }
  const duplicated = existingCustomTags.some((item) => {
    const itemId = normalizeId(item.id)
    return itemId !== editingTagId && trimText(item.name) === payload.name
  })
  if (duplicated) {
    return { valid: false, message: '标签不能重复' }
  }
  return { valid: true, message: '' }
}

function buildWorkTagDeleteBlockedMessage(tag = {}) {
  const name = trimText(tag.name) || '该标签'
  const count = toNumber(tag.count)
  return `标签「${name}」下还有 ${count} 个作品，${WORK_TAG_DELETE_BLOCKED_SUFFIX}`
}

function buildWorkFieldCounters(form = {}) {
  return {
    title: `${countText(form.title)}/${TITLE_LIMIT}`,
    description: `${countText(form.description)}/${DESCRIPTION_LIMIT}`
  }
}

function validateWorkForm(form = {}) {
  if (!trimText(form.title)) {
    return { valid: false, message: '作品标题不能为空' }
  }
  if (countText(form.title) > TITLE_LIMIT) {
    return { valid: false, message: '作品标题不能超过 30 字' }
  }
  if (countText(form.description) > DESCRIPTION_LIMIT) {
    return { valid: false, message: '作品说明不能超过 1000 字' }
  }
  return { valid: true, message: '' }
}

module.exports = {
  TITLE_LIMIT,
  DESCRIPTION_LIMIT,
  DEFAULT_WORK_TAG_COLOR: DEFAULT_TAG_COLOR,
  WORK_TAG_COLOR_OPTIONS: TAG_COLOR_OPTIONS,
  WORK_TAG_MAX_COUNT: TAG_MAX_COUNT,
  applyUnifiedWorkTags,
  buildWorkFieldCounters,
  buildUnifiedWorkTagItems,
  buildUnifiedWorkTagNames,
  buildWorkTagDeleteBlockedMessage,
  buildWorkTagOptionListHeight,
  buildWorkTagPickerOptions,
  buildWorkTagPayload,
  buildWorkUpdatePayload,
  createWorkTagForm,
  getWorkTagColorOption,
  normalizeWork,
  normalizeWorkDetail,
  normalizeWorkList,
  normalizeWorkTags,
  validateWorkTagForm,
  validateWorkForm
}
