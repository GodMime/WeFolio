const { buildRemoteFontStyle } = require('./portfolio-component-platform')
const MAX_NODES = 20
const MAX_TITLE_CODE_POINTS = 60
const MAX_DESCRIPTION_CODE_POINTS = 1000
const MAX_CUSTOM_LABEL_CODE_POINTS = 20
const CUSTOM_LABEL_CONTROLS = /[\u0000-\u001F\u007F\u0085\u2028\u2029]/
const MAX_TOTAL_TEXT_CODE_POINTS = 6000
const MIN_GAP_RPX = 120
const MAX_GAP_RPX = 640
const MIN_FONT_SIZE = 10
const MAX_FONT_SIZE = 96
const MIN_TAIL_RPX = 96
const TAIL_SPACE_RPX = 80
const AXIS_CENTER_RPX = 6
const DEFAULT_TAIL_LENGTH_RPX = 320
const TAIL_DASH_PERIOD_RPX = 12
const TAIL_DASH_RATIO = 0.5
const MAX_TAIL_DASHES = 256
const TAIL_START_ALPHA = 0.85
const TAIL_RGB = Object.freeze({ light: '207,198,186', dark: '155,141,122' })
const VARIANTS = Object.freeze(['MINIMAL', 'DATE_COLUMN', 'MILESTONE_CARD'])
const DIRECTIONS = Object.freeze(['HORIZONTAL', 'VERTICAL'])
const PRECISIONS = Object.freeze(['YEAR', 'MONTH', 'DAY', 'CUSTOM'])
const ROLES = Object.freeze(['heading', 'date', 'title', 'description'])
const NODE_ROLES = Object.freeze(['date', 'title', 'description'])
const VARIANT_LABELS = Object.freeze({ MINIMAL: '极简线轴', DATE_COLUMN: '日期分栏', MILESTONE_CARD: '里程碑卡片' })
const DEFAULT_SIZES = Object.freeze({ MINIMAL: [44, 24, 28, 24], DATE_COLUMN: [44, 40, 30, 22], MILESTONE_CARD: [44, 24, 26, 22] })
const COLORS = Object.freeze({ light: { heading: '#212529', title: '#212529', date: '#7B6A54', description: '#687078' }, dark: { heading: '#F8F9FA', title: '#F8F9FA', date: '#D4C0A1', description: '#C1C7CE' } })
const clone = value => JSON.parse(JSON.stringify(value))
const own = (value, key) => Object.prototype.hasOwnProperty.call(value || {}, key)
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value)
const count = value => typeof value === 'string' ? Array.from(value).length : 0
let sequence = 0

/** 节点身份在编辑会话内生成，重排和日期修改不重新生成。 */
function createTimelineNode() { return { nodeKey: `event_${Date.now().toString(36)}_${++sequence}`, datePrecision: 'YEAR', date: '', title: '', description: '' } }
function createTimeline(ownerType = 'USER') { return { ...normalizeTimeline(), title: ownerType === 'TEAM' ? '团队里程碑' : '我的历程', nodes: [createTimelineNode()] } }
/** 仅为缺省值补默认；非法显式值及未知键保留给校验器报错，不截断用户内容。 */
function normalizeTimeline(raw = {}) {
  if (!object(raw)) return raw
  return { variant: 'MINIMAL', direction: 'HORIZONTAL', title: '', showKicker: true, ...clone(raw),
    minGapRpx: !own(raw, 'minGapRpx') ? { horizontal: 320, vertical: 200 } : object(raw.minGapRpx) ? { horizontal: 320, vertical: 200, ...raw.minGapRpx } : raw.minGapRpx,
    textStyles: !own(raw, 'textStyles') ? {} : normalizeStyles(raw.textStyles), nodes: !own(raw, 'nodes') ? [] : Array.isArray(raw.nodes) ? raw.nodes.map(node => object(node) ? { title: '', description: '', ...clone(node), ...(own(node, 'textStyles') ? { textStyles: normalizeStyles(node.textStyles) } : {}) } : node) : clone(raw.nodes) }
}
function normalizeStyles(styles) {
  if (!object(styles)) return styles
  const value = clone(styles)
  Object.keys(value).forEach(role => { const patch = value[role]; if (object(patch) && typeof patch.fontId === 'string' && patch.fontId && (!own(patch, 'fontFamily') || ['SYSTEM', 'WECHAT_SANS_SS'].includes(patch.fontFamily))) patch.fontFamily = 'SYSTEM' })
  return value
}
function unknown(value, keys) { return Object.keys(value).some(key => !keys.includes(key)) }
function daysInMonth(year, month) { return [31, year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1] || 0 }
function validTimelineDate(precision, date) {
  if (typeof date !== 'string' || !PRECISIONS.includes(precision)) return false
  if (precision === 'CUSTOM') return !customTimelineLabelError(date)
  const patterns = { YEAR: /^\d{4}$/, MONTH: /^\d{4}-\d{2}$/, DAY: /^\d{4}-\d{2}-\d{2}$/ }
  if (!patterns[precision].test(date)) return false
  const [year, month, day] = date.split('-').map(Number)
  return year >= 1 && year <= 9999 && (precision === 'YEAR' || month >= 1 && month <= 12 && (precision === 'MONTH' || day >= 1 && day <= daysInMonth(year, month)))
}
/** 自定义标记保存原文；普通日期仍使用点分隔展示。 */
function formatTimelineDate(precision, date) { return precision !== 'CUSTOM' && validTimelineDate(precision, date) ? date.replace(/-/g, '.') : String(date || '') }
/** 允许临时空草稿，但控制字符与长度限制始终生效。 */
function customTimelineLabelError(value, allowEmpty = false) {
  if (typeof value !== 'string') return '自定义标签必须为文字'
  if (count(value) > MAX_CUSTOM_LABEL_CODE_POINTS) return '自定义标签最多 20 字'
  if (CUSTOM_LABEL_CONTROLS.test(value)) return '自定义标签须为单行文字，不能包含控制字符'
  if (!allowEmpty && !value.trim()) return '请填写自定义标签'
  return ''
}
/** 编辑反馈独立于输入框，未完成时提示具体缺项，完整时展示实际标记。 */
function timelineDateFeedback(precision, date) {
  if (precision === 'CUSTOM') { const error = customTimelineLabelError(date); return { text: error || date, valid: !error } }
  const [year, month, day] = String(date || '').split('-')
  let error = ''
  if (!year) error = '请先填写年份'
  else if (!/^\d{4}$/.test(year) || Number(year) < 1 || Number(year) > 9999) error = '请填写有效年份（1–9999）'
  else if (precision !== 'YEAR' && !month) error = '请选择月份'
  else if (precision !== 'YEAR' && (!/^\d{2}$/.test(month) || Number(month) < 1 || Number(month) > 12)) error = '请选择有效月份'
  else if (precision === 'DAY' && !day) error = '请选择日期'
  else if (!validTimelineDate(precision, date)) error = '请选择有效日期'
  return { text: error || formatTimelineDate(precision, date), valid: !error }
}
function countTimelineText(config) { return count(config && config.title) + (Array.isArray(config && config.nodes) ? config.nodes.reduce((sum, node) => sum + count(node.title) + count(node.description) + (node.datePrecision === 'CUSTOM' ? count(node.date) : 0), 0) : 0) }
function validateStyles(styles, roles, path) {
  if (!object(styles) || unknown(styles, roles)) return `${path}：文字样式对象无效`
  for (const role of Object.keys(styles)) {
    const patch = styles[role]
    if (!object(patch) || unknown(patch, ['fontFamily', 'fontId', 'fontSizeRpx', 'color'])) return `${path}.${role}：文字样式字段无效`
    if (own(patch, 'fontFamily') && !['SYSTEM', 'WECHAT_SANS_SS'].includes(patch.fontFamily)) return `${path}.${role}：字体无效`
    if (own(patch, 'fontId') && (typeof patch.fontId !== 'string' || !patch.fontId.trim() || own(patch, 'fontFamily') && patch.fontFamily !== 'SYSTEM')) return `${path}.${role}：远程字体无效`
    if (own(patch, 'fontSizeRpx') && (!Number.isInteger(patch.fontSizeRpx) || patch.fontSizeRpx < MIN_FONT_SIZE || patch.fontSizeRpx > MAX_FONT_SIZE)) return `${path}.${role}：字号须为 10–96 的整数`
    if (own(patch, 'color') && (typeof patch.color !== 'string' || !/^(AUTO|#[0-9A-Fa-f]{6})$/.test(patch.color))) return `${path}.${role}：颜色无效`
  }
  return ''
}
/** 日期、节点身份、文本及样式在完成编辑和保存时使用同一检查。 */
function validateTimeline(raw, { allowIncomplete = false } = {}) {
  if (!object(raw) || unknown(raw, ['variant', 'direction', 'title', 'showKicker', 'minGapRpx', 'textStyles', 'nodes'])) return '时间轴：配置字段无效'
  const config = normalizeTimeline(raw)
  if (!VARIANTS.includes(config.variant) || !DIRECTIONS.includes(config.direction) || typeof config.showKicker !== 'boolean') return '时间轴：样式或方向无效'
  if (!object(config.minGapRpx) || unknown(config.minGapRpx, ['horizontal', 'vertical']) || ['horizontal', 'vertical'].some(key => !Number.isInteger(config.minGapRpx[key]) || config.minGapRpx[key] < MIN_GAP_RPX || config.minGapRpx[key] > MAX_GAP_RPX)) return '时间轴：节点间距须为 120–640 的整数'
  if (!Array.isArray(config.nodes) || config.nodes.length > MAX_NODES || !allowIncomplete && config.nodes.length < 1) return '时间轴：需要 1–20 个节点'
  const keys = new Set()
  for (let index = 0; index < config.nodes.length; index++) {
    const node = config.nodes[index], label = `第 ${index + 1} 个节点`
    if (!object(node) || unknown(node, ['nodeKey', 'datePrecision', 'date', 'title', 'description', 'textStyles'])) return `${label}：配置字段无效`
    if (!PRECISIONS.includes(node.datePrecision) || typeof node.date !== 'string') return `${label}：请选择与精度一致的有效日期`
    if (node.datePrecision === 'CUSTOM') { const error = customTimelineLabelError(node.date, allowIncomplete); if (error) return `${label}：${error}` }
    else if (!allowIncomplete && !validTimelineDate(node.datePrecision, node.date)) return `${label}：请选择与精度一致的有效日期`
    if (typeof node.nodeKey !== 'string' || !node.nodeKey.trim() || count(node.nodeKey) > 64 || keys.has(node.nodeKey)) return `${label}：节点身份重复或无效`
    keys.add(node.nodeKey)
    if (typeof node.title !== 'string' || typeof node.description !== 'string') return `${label}：标题和描述必须为文字`
    if (count(node.title) > MAX_TITLE_CODE_POINTS || count(node.description) > MAX_DESCRIPTION_CODE_POINTS) return `${label}：标题最多 60 字，描述最多 1000 字`
    if (!allowIncomplete && !node.title.trim() && !node.description.trim()) return `${label}：标题和描述至少填写一项`
  }
  if (typeof config.title !== 'string' || count(config.title) > MAX_TITLE_CODE_POINTS) return '时间轴：标题最多 60 字'
  if (countTimelineText(config) > MAX_TOTAL_TEXT_CODE_POINTS) return '时间轴：标题及节点文字合计最多 6000 字'
  let error = validateStyles(config.textStyles, ROLES, '统一样式')
  if (error) return error
  for (let index = 0; index < config.nodes.length; index++) {
    if (own(config.nodes[index], 'textStyles')) error = validateStyles(config.nodes[index].textStyles, NODE_ROLES, `第 ${index + 1} 个节点`)
    if (error) return error
  }
  return ''
}
function timelineTheme(themeMode, backgroundColor) {
  if (themeMode === 'light' || themeMode === 'dark') return themeMode
  const color = /^#[0-9A-Fa-f]{6}$/.test(backgroundColor) ? backgroundColor : '#FFFFFF'
  const rgb = [1, 3, 5].map(index => parseInt(color.slice(index, index + 2), 16))
  return (299 * rgb[0] + 587 * rgb[1] + 114 * rgb[2]) / 1000 < 128 ? 'dark' : 'light'
}
/** 字体选择作为一个整体覆盖；显式内置字体阻断上级 fontId。 */
function mergeStyle(base, patch = {}) {
  const result = { ...base, ...patch }
  if (own(patch, 'fontFamily') || own(patch, 'fontId')) {
    delete result.fontId
    if (patch.fontId) { result.fontId = patch.fontId; result.fontFamily = 'SYSTEM' }
  }
  return result
}
function resolveTimelineStyle(config, role, node) {
  const sizes = DEFAULT_SIZES[config.variant] || DEFAULT_SIZES.MINIMAL
  const base = { fontFamily: 'SYSTEM', fontSizeRpx: sizes[ROLES.indexOf(role)], color: 'AUTO',
    fontWeight: role === 'description' || role === 'date' && config.variant === 'DATE_COLUMN' ? 'NORMAL' : 'BOLD',
    lineHeight: role === 'heading' || role === 'date' ? 1.4 : role === 'description' ? 1.85 : 1.65 }
  return mergeStyle(mergeStyle(base, (config.textStyles || {})[role]), node && (node.textStyles || {})[role])
}
function timelineFontSelections(config) {
  if (!object(config)) return []
  const result = []
  Object.keys(config.textStyles || {}).forEach(role => result.push({ ...config.textStyles[role], role }))
  ;(config.nodes || []).forEach(node => Object.keys(node.textStyles || {}).forEach(role => result.push({ ...node.textStyles[role], role, nodeKey: node.nodeKey })))
  return result
}
/** 有效文本与原始字体选择分别枚举，继承结果不会写回配置。 */
function timelineFontDemands(raw) {
  const config = normalizeTimeline(raw), result = []
  if (!object(config) || !Array.isArray(config.nodes)) return result
  const add = (role, text, node) => { if (text) result.push({ ...resolveTimelineStyle(config, role, node), content: text }) }
  add('heading', config.title)
  config.nodes.forEach(node => { add('date', formatTimelineDate(node.datePrecision, node.date), node); add('title', node.title, node); add('description', node.description, node) })
  return result
}
function timelineSummary(config) { return `${VARIANT_LABELS[config.variant] || VARIANT_LABELS.MINIMAL} · ${config.direction === 'VERTICAL' ? '纵向' : '横向'} · ${(config.nodes || []).length} 个节点` }
function textStyle(config, role, node, theme, fontContext) {
  const style = resolveTimelineStyle(config, role, node)
  return (style.fontFamily === 'WECHAT_SANS_SS' && !style.fontId ? 'font-family:WeFolioWechatSansSS,-apple-system,BlinkMacSystemFont,sans-serif;' : buildRemoteFontStyle(style, fontContext)) + `font-size:${style.fontSizeRpx}rpx;font-weight:${style.fontWeight === 'BOLD' ? 700 : 400};line-height:${style.lineHeight};color:${style.color === 'AUTO' ? COLORS[theme][role] : style.color};`
}
/** 每条短划线取整条尾线上的透明度区间，内部也连续插值，避免大段明暗分界。 */
function buildTimelineTailDashes(lengthRpx = DEFAULT_TAIL_LENGTH_RPX, horizontal = true, theme = 'light') {
  const length = Number.isFinite(lengthRpx) && lengthRpx > 0 ? lengthRpx : DEFAULT_TAIL_LENGTH_RPX
  const count = Math.min(MAX_TAIL_DASHES, Math.max(1, Math.ceil(length / TAIL_DASH_PERIOD_RPX)))
  const color = TAIL_RGB[theme] || TAIL_RGB.light
  return Array.from({ length: count }, (_, key) => {
    const start = key / count, end = (key + TAIL_DASH_RATIO) / count
    const startAlpha = TAIL_START_ALPHA * (1 - start), endAlpha = TAIL_START_ALPHA * (1 - end)
    const position = horizontal ? 'left' : 'top', size = horizontal ? 'width' : 'height'
    return { key, start, end, startAlpha, endAlpha,
      style: `${position}:${start * 100}%;${size}:${(end - start) * 100}%;background:linear-gradient(to ${horizontal ? 'right' : 'bottom'},rgba(${color},${startAlpha}),rgba(${color},${endAlpha}));` }
  })
}
function presentTimeline(raw, ownerType = 'USER', themeMode, backgroundColor, fontContext = {}) {
  const normalized = normalizeTimeline(raw)
  const config = object(normalized) ? normalized : normalizeTimeline(), theme = timelineTheme(themeMode, backgroundColor)
  return { config, theme, headingStyle: textStyle(config, 'heading', null, theme, fontContext), kicker: ownerType === 'TEAM' ? 'OUR STORY' : 'MY JOURNEY',
    hint: ownerType === 'TEAM' ? '左右滑动，继续看我们的故事 →' : '左右滑动，继续看我的故事 →',
    nodes: (Array.isArray(config.nodes) ? config.nodes : []).map((node, index) => {
      const date = formatTimelineDate(node.datePrecision, node.date), dateStyle = resolveTimelineStyle(config, 'date', node)
      const last = index === config.nodes.length - 1
      return { ...node, last, tailDashes: last ? buildTimelineTailDashes(undefined, config.direction === 'HORIZONTAL', theme) : [], displayDate: date, customDate: node.datePrecision === 'CUSTOM', year: node.datePrecision === 'CUSTOM' ? '' : date.slice(0, 4), monthDay: node.datePrecision === 'CUSTOM' ? '' : date.slice(5), dateStyle: textStyle(config, 'date', node, theme, fontContext),
        monthDayStyle: `font-size:${Math.max(10, Math.round(dateStyle.fontSizeRpx * 0.6))}rpx;line-height:1.5;`, titleStyle: textStyle(config, 'title', node, theme, fontContext), descriptionStyle: textStyle(config, 'description', node, theme, fontContext) }
    }) }
}
/** 布局仅使用实测尺寸；未知尺寸由组件切回自然流，配置不保存像素。 */
function layoutTimeline(raw, widthPx, scale, measurements) {
  const config = normalizeTimeline(raw)
  if (!object(config) || !Array.isArray(config.nodes) || !object(config.minGapRpx)
    || !Number.isFinite(widthPx) || !(widthPx > 0) || !Number.isFinite(scale) || !(scale > 0)
    || !['horizontal', 'vertical'].every(key => Number.isFinite(config.minGapRpx[key]) && config.minGapRpx[key] > 0)
    || !Array.isArray(measurements) || measurements.length !== config.nodes.length
    || measurements.some(item => !object(item) || ['dateWidth', 'dateHeight', 'dateFirstLine', 'bodyWidth', 'bodyHeight', 'height'].some(key => own(item, key) && (!Number.isFinite(item[key]) || item[key] < 0)))) return null
  const gap = 32 * scale, padding = config.variant === 'MILESTONE_CARD' ? 40 * scale : 0
  const horizontal = config.direction === 'HORIZONTAL'
  const dateHeight = Math.max(0, ...measurements.map(item => item.dateHeight || 0))
  let cursor = 0
  const nodes = measurements.map((item, index) => {
    const occupiedWidth = Math.max(item.dateWidth || 0, (item.bodyWidth || 240 * scale) + padding)
    const width = horizontal ? occupiedWidth : widthPx
    const dotOffset = horizontal ? dateHeight + 16 * scale : (item.dateFirstLine || item.dateHeight || 24 * scale) / 2
    const height = horizontal ? dotOffset + 32 * scale + (item.bodyHeight || 0) + padding : item.height || (item.dateHeight || 0) + (item.bodyHeight || 0) + 32 * scale + padding
    const result = { left: horizontal ? cursor : 0, top: horizontal ? 0 : cursor, width, height, dot: (horizontal ? cursor : cursor + dotOffset), dotOffset, last: index === measurements.length - 1 }
    if (horizontal) cursor += index === measurements.length - 1 ? width : Math.max(config.minGapRpx.horizontal * scale, width + gap)
    else {
      const nextOffset = index + 1 < measurements.length ? (measurements[index + 1].dateFirstLine || measurements[index + 1].dateHeight || 24 * scale) / 2 : 0
      cursor += index === measurements.length - 1 ? height : Math.max(height + gap, config.minGapRpx.vertical * scale + dotOffset - nextOffset)
    }
    return result
  })
  nodes.forEach((node, index) => { node.lineLength = index + 1 < nodes.length ? nodes[index + 1].dot - node.dot : 0 })
  const last = nodes[nodes.length - 1]
  if (last) {
    // 虚线越过末节点内容后仍留出余量，画布包含轴线起点偏移，避免滚动到末尾时裁切。
    last.tailLength = Math.max(MIN_TAIL_RPX * scale, (horizontal ? last.width : last.height - last.dotOffset) + TAIL_SPACE_RPX * scale)
    cursor = Math.max(cursor, horizontal ? last.left + AXIS_CENTER_RPX * scale + last.tailLength : last.top + last.dotOffset + last.tailLength)
  }
  return { nodes, width: horizontal ? cursor : widthPx, height: horizontal ? Math.max(0, ...nodes.map(node => node.height)) : cursor, overflow: horizontal && cursor > widthPx }
}
module.exports = { MAX_CUSTOM_LABEL_CODE_POINTS, MAX_NODES, MAX_TITLE_CODE_POINTS, MAX_DESCRIPTION_CODE_POINTS, MAX_TOTAL_TEXT_CODE_POINTS, MIN_GAP_RPX, MAX_GAP_RPX, MIN_FONT_SIZE, MAX_FONT_SIZE,
  VARIANTS, DIRECTIONS, PRECISIONS, ROLES, NODE_ROLES, VARIANT_LABELS, createTimelineNode, createTimeline, normalizeTimeline, validateTimeline, validTimelineDate, daysInMonth,
  formatTimelineDate, customTimelineLabelError, timelineDateFeedback, countTimelineText, timelineTheme, resolveTimelineStyle, timelineFontSelections, timelineFontDemands, timelineSummary, buildTimelineTailDashes, presentTimeline, layoutTimeline }
