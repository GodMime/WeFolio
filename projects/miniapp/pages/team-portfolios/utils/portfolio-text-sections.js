// 两个分包各自维护本地副本，通过同一组行为测试保持一致，避免跨分包引用。
const BLOCK_PRESETS = {
  EYEBROW: [24, 'NORMAL', 0, 16],
  TITLE: [44, 'BOLD', 24, 16],
  PARAGRAPH: [28, 'NORMAL', 0, 16],
  LIST: [26, 'NORMAL', 0, 16],
  HINT: [24, 'NORMAL', 32, 0],
  SPACER: [0, '', 0, 0]
}
const TEXT_TYPES = ['EYEBROW', 'TITLE', 'PARAGRAPH', 'HINT']
const FONT_FAMILIES = ['SYSTEM', 'WECHAT_SANS_SS']
const FONT_WEIGHTS = ['NORMAL', 'BOLD']
const ALIGNMENTS = ['LEFT', 'CENTER', 'RIGHT']
const BACKGROUND_TREATMENTS = ['ORIGINAL', 'DARK_MASK', 'GRADIENT']
const VERTICAL_ALIGNMENTS = ['TOP', 'CENTER', 'BOTTOM']
const IMAGE_MEDIA_TYPES = ['IMAGE', 'ANIMATION']
const MAX_BLOCKS = 20
const MAX_TEXT = 2000
const MAX_ITEMS = 10
const SPACING_STEP = 4
const MAX_SPACING = 128
const DEFAULT_SPACER_HEIGHT = 32
const MIN_FONT_SIZE = 20
const MAX_FONT_SIZE = 96
const AUTO_COLOR = 'AUTO'
const HEX_COLOR = /^#[0-9a-fA-F]{6}$/
const BLOCK_BASE_FIELDS = ['blockKey', 'type', 'marginTopRpx', 'marginBottomRpx']
const TEXT_STYLE_FIELDS = ['fontFamily', 'fontSizeRpx', 'fontWeight', 'color', 'alignment']

/** 编辑状态必须独立复制，避免嵌套列表和展示资源污染原始页面配置。 */
function copy(value) {
  if (Array.isArray(value)) return value.map(copy)
  if (isObject(value)) return Object.keys(value).reduce((result, key) => ({ ...result, [key]: copy(value[key]) }), {})
  return value
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

// 保持与现有小程序基础库兼容，不依赖较新的 Object.hasOwn。
function hasOwn(value, key) {
  return Object.prototype.hasOwnProperty.call(value, key)
}

function pick(value, fields) {
  const result = {}
  fields.forEach((field) => {
    if (hasOwn(value, field)) result[field] = copy(value[field])
  })
  return result
}

/** 默认值仅用于新增及缺失字段；非法值留给独立校验处理。 */
function createStructuredBlock(type, blockKey) {
  const preset = hasOwn(BLOCK_PRESETS, type) ? BLOCK_PRESETS[type] : null
  if (!preset) return { blockKey, type }
  const block = { blockKey, type, marginTopRpx: preset[2], marginBottomRpx: preset[3] }
  if (type === 'SPACER') return { ...block, heightRpx: DEFAULT_SPACER_HEIGHT }
  return {
    ...block, fontFamily: FONT_FAMILIES[0], fontSizeRpx: preset[0], fontWeight: preset[1],
    color: AUTO_COLOR, alignment: ALIGNMENTS[0],
    ...(type === 'LIST' ? { items: [''] } : { content: '' })
  }
}

function normalizeBlock(block) {
  if (!isObject(block)) return copy(block)
  return { ...createStructuredBlock(block.type, block.blockKey), ...copy(block) }
}

/** 背景规范化只返回背景配置；关闭期间仍在编辑副本中保留资源身份。 */
function normalizeTextBackground(config = {}, { team = false, structured = false } = {}) {
  const source = isObject(config) ? config : {}
  const fields = ['backgroundEnabled', 'backgroundTreatment', 'backgroundWorkId']
  if (team) fields.push('backgroundMemberUserId')
  if (!structured) fields.push('verticalAlignment')
  return {
    backgroundEnabled: false, backgroundTreatment: BACKGROUND_TREATMENTS[2],
    ...(!structured ? { verticalAlignment: VERTICAL_ALIGNMENTS[1] } : {}),
    ...pick(source, fields)
  }
}

/** 确认关闭后释放本配置的资源身份，同时保留用户背景处理偏好。 */
function finalizeTextBackground(config, options) {
  const result = normalizeTextBackground(config, options)
  if (result.backgroundEnabled === false) {
    delete result.backgroundWorkId
    delete result.backgroundMemberUserId
  }
  return result
}

function validId(value) {
  return (typeof value === 'number' && Number.isSafeInteger(value) && value > 0)
    || (typeof value === 'string' && /^[1-9]\d*$/.test(value))
}

/** 前端仅检查可见配置，作品归属与团队授权仍由保存／发布接口终验。 */
function validateTextBackground(config = {}, options = {}) {
  if (!isObject(config)) return '背景配置格式不正确'
  const background = normalizeTextBackground(config, options)
  if (typeof background.backgroundEnabled !== 'boolean') return '背景开关格式不正确'
  if (!BACKGROUND_TREATMENTS.includes(background.backgroundTreatment)) return '请选择有效的背景处理'
  if (!options.structured && !VERTICAL_ALIGNMENTS.includes(background.verticalAlignment)) return '请选择有效的上下对齐方式'
  if (!background.backgroundEnabled) return ''
  if (!validId(background.backgroundWorkId)) return '请选择背景作品'
  if (options.team && !validId(background.backgroundMemberUserId)) return '请选择背景作品所属成员'
  if (config.backgroundInvalid === true || config.backgroundWork === null) return '背景作品已失效，请重新选择或关闭背景'
  if (isObject(config.backgroundWork) && !IMAGE_MEDIA_TYPES.includes(config.backgroundWork.mediaType)) return '背景作品仅支持图片或动图'
  if (hasOwn(config, 'backgroundWork') && !validBackgroundWork(config)) return '背景作品已失效，请重新选择或关闭背景'
  return ''
}

/** 保留编辑与展示附加字段，持久化时另行按白名单剔除。 */
function normalizeStructuredTextConfig(config = {}, { team = false } = {}) {
  if (!isObject(config)) return copy(config)
  const result = { ...copy(config), ...normalizeTextBackground(config, { team, structured: true }) }
  delete result.verticalAlignment
  if (!team) delete result.backgroundMemberUserId
  result.blocks = hasOwn(config, 'blocks') ? copy(config.blocks) : []
  if (Array.isArray(result.blocks)) result.blocks = result.blocks.map(normalizeBlock)
  return result
}

function validSpacing(value) {
  return Number.isInteger(value) && value >= 0 && value <= MAX_SPACING && value % SPACING_STEP === 0
}

/** 手工间距输入允许归一化；加载已有配置时不能调用本函数静默修正。 */
function normalizeSpacingInput(value) {
  const number = Number(value)
  if (Number.isNaN(number)) return 0
  return Math.round(Math.max(0, Math.min(MAX_SPACING, number)) / SPACING_STEP) * SPACING_STEP
}

/** 仅统计当前类型的文字，Unicode 码点包含用户输入的空格和换行。 */
function countStructuredText(blocks) {
  if (!Array.isArray(blocks)) return 0
  return blocks.reduce((total, block) => {
    if (!isObject(block)) return total
    const strings = block.type === 'LIST' && Array.isArray(block.items)
      ? block.items : TEXT_TYPES.includes(block.type) ? [block.content] : []
    return total + strings.reduce((count, value) => count + (typeof value === 'string' ? Array.from(value).length : 0), 0)
  }, 0)
}

function validateBlock(block) {
  if (!isObject(block) || !hasOwn(BLOCK_PRESETS, block.type)) return '请选择有效的区块类型'
  if (typeof block.blockKey !== 'string' || !block.blockKey.trim()) return '区块标识不能为空'
  if (!validSpacing(block.marginTopRpx) || !validSpacing(block.marginBottomRpx)) return '区块间距须为 0–128 rpx 内的 4 的倍数'
  if (block.type === 'SPACER') return validSpacing(block.heightRpx) ? '' : '留白高度须为 0–128 rpx 内的 4 的倍数'
  if (!FONT_FAMILIES.includes(block.fontFamily)) return '请选择有效的字体'
  if (!Number.isInteger(block.fontSizeRpx) || block.fontSizeRpx < MIN_FONT_SIZE || block.fontSizeRpx > MAX_FONT_SIZE) return '字号须为 20–96 rpx 的整数'
  if (!FONT_WEIGHTS.includes(block.fontWeight)) return '请选择有效的字重'
  if (typeof block.color !== 'string' || (block.color !== AUTO_COLOR && !HEX_COLOR.test(block.color))) return '颜色须为跟随主题或六位十六进制颜色'
  if (!ALIGNMENTS.includes(block.alignment)) return '请选择有效的左右对齐方式'
  if (block.type === 'LIST') {
    if (!Array.isArray(block.items) || block.items.length < 1 || block.items.length > MAX_ITEMS) return '列表须包含 1–10 条内容'
    if (block.items.some((item) => typeof item !== 'string' || !item.trim())) return '列表内容不能为空'
  } else if (typeof block.content !== 'string' || !block.content.trim()) return '区块内容不能为空'
  return ''
}

function validateStructuredTextConfig(config = {}, { team = false, requireContent = true } = {}) {
  const normalized = normalizeStructuredTextConfig(config, { team })
  if (!isObject(normalized) || !Array.isArray(normalized.blocks)) return '区块配置格式不正确'
  if (normalized.blocks.length > MAX_BLOCKS) return '最多添加 20 个区块'
  const keys = new Set()
  for (const block of normalized.blocks) {
    const error = validateBlock(block)
    if (error) return error
    if (keys.has(block.blockKey)) return '区块标识不能重复'
    keys.add(block.blockKey)
  }
  if (countStructuredText(normalized.blocks) > MAX_TEXT) return '文字总量不能超过 2000 字'
  if (requireContent && !normalized.blocks.some((block) => block.type !== 'SPACER')) return '请至少添加一个非空文字或列表区块'
  return validateTextBackground(normalized, { team, structured: true })
}

function finalizeBlock(block) {
  if (!isObject(block)) return copy(block)
  const fields = [...BLOCK_BASE_FIELDS]
  if (block.type === 'SPACER') fields.push('heightRpx')
  else fields.push(...TEXT_STYLE_FIELDS, block.type === 'LIST' ? 'items' : 'content')
  return pick(block, fields)
}

/** 只持久化当前类型字段，临时缓存、展示样式和服务端资源信息均不提交。 */
function finalizeStructuredTextConfig(config, options = {}) {
  const normalized = normalizeStructuredTextConfig(config, options)
  if (!isObject(normalized)) return normalized
  return {
    ...finalizeTextBackground(normalized, { ...options, structured: true }),
    blocks: Array.isArray(normalized.blocks) ? normalized.blocks.map(finalizeBlock) : normalized.blocks
  }
}

const LIGHT_TEXT_COLOR = '#212529'
const DARK_TEXT_COLOR = '#F8F9FA'
const VIEWPORT_WIDTH_RPX = 750
const BACKGROUND_IMAGE_SELECTOR = '.text-background-image'
const TREATMENT_CLASSES = {
  ORIGINAL: 'text-background--original',
  DARK_MASK: 'text-background--dark-mask',
  GRADIENT: 'text-background--gradient'
}
const FONT_CLASSES = { SYSTEM: 'font-system', WECHAT_SANS_SS: 'font-wechat-sans-ss' }

function contentKind(type) {
  return type === 'SPACER' ? 'spacer' : type === 'LIST' ? 'list' : 'text'
}

/** payloads 是详情会话缓存，不参与任何最终配置的持久化。 */
function cacheBlockPayload(draft) {
  const block = draft.block
  const kind = contentKind(block.type)
  const field = kind === 'text' ? 'content' : kind === 'list' ? 'items' : 'heightRpx'
  draft.payloads[kind] = pick(block, [field])
  if (kind !== 'spacer') draft.payloads.style = pick(block, TEXT_STYLE_FIELDS)
}

function createBlockEditDraft(block) {
  const draft = { block: finalizeBlock(normalizeBlock(block)), payloads: {} }
  if (isObject(draft.block)) cacheBlockPayload(draft)
  return draft
}

/** 切换返回新副本；目标结构已有缓存时恢复，首次转换才从另一种文字结构派生。 */
function switchBlockEditType(draft, type) {
  const unchanged = () => copy(draft)
  if (!hasOwn(BLOCK_PRESETS, type) || !isObject(draft) || !isObject(draft.block)) {
    return { draft: unchanged(), error: '请选择有效的区块类型' }
  }
  const source = draft.block
  if (source.type === 'LIST' && Array.isArray(source.items) && source.items.length > MAX_ITEMS) {
    return { draft: unchanged(), error: '列表最多 10 条，请先调整内容' }
  }
  if (countStructuredText([source]) > MAX_TEXT) return { draft: unchanged(), error: '文字总量不能超过 2000 字' }
  const result = copy(draft)
  if (!isObject(result.payloads)) result.payloads = {}
  cacheBlockPayload(result)
  const kind = contentKind(type)
  let payload = result.payloads[kind]
  if (!payload) {
    if (kind === 'list') {
      const text = result.payloads.text && result.payloads.text.content
      const items = typeof text === 'string' ? text.split(/\r\n|\r|\n/).filter((line) => line.trim()) : []
      if (items.length > MAX_ITEMS) return { draft: unchanged(), error: '列表最多 10 条，请先调整内容' }
      payload = { items: items.length ? items : [''] }
    } else if (kind === 'text') {
      const items = result.payloads.list && result.payloads.list.items
      payload = { content: Array.isArray(items) ? items.join('\n') : '' }
    } else payload = { heightRpx: DEFAULT_SPACER_HEIGHT }
  }
  if (kind === 'list' && Array.isArray(payload.items) && payload.items.length > MAX_ITEMS) {
    return { draft: unchanged(), error: '列表最多 10 条，请先调整内容' }
  }
  result.block = {
    ...createStructuredBlock(type, source.blockKey),
    ...pick(source, ['marginTopRpx', 'marginBottomRpx']),
    ...(kind !== 'spacer' ? copy(result.payloads.style || {}) : {}),
    ...copy(payload)
  }
  cacheBlockPayload(result)
  return { draft: result, error: '' }
}

/** 只对派生样式使用安全默认值，保留原字段供编辑器提示修正。 */
function buildStructuredTextPresentation(config = {}, themeMode) {
  const source = isObject(config) ? config : {}
  const foreground = source.backgroundEnabled === true || themeMode === 'DARK' || themeMode === 'dark'
    ? DARK_TEXT_COLOR : LIGHT_TEXT_COLOR
  const blocks = Array.isArray(source.blocks) ? source.blocks : []
  return {
    blocks: blocks.filter(isObject).map((raw) => {
      const block = normalizeBlock(raw)
      const top = validSpacing(block.marginTopRpx) ? block.marginTopRpx : 0
      const bottom = validSpacing(block.marginBottomRpx) ? block.marginBottomRpx : 0
      const spacing = 'margin-top:' + top + 'rpx;margin-bottom:' + bottom + 'rpx;'
      if (block.type === 'SPACER') {
        const height = validSpacing(block.heightRpx) ? block.heightRpx : DEFAULT_SPACER_HEIGHT
        return { ...block, style: spacing + 'height:' + height + 'rpx;' }
      }
      const displayColor = typeof block.color === 'string' && HEX_COLOR.test(block.color) ? block.color : foreground
      const fontSize = Number.isInteger(block.fontSizeRpx) && block.fontSizeRpx >= MIN_FONT_SIZE && block.fontSizeRpx <= MAX_FONT_SIZE
        ? block.fontSizeRpx : (hasOwn(BLOCK_PRESETS, block.type) ? BLOCK_PRESETS[block.type] : BLOCK_PRESETS.PARAGRAPH)[0]
      const alignment = ALIGNMENTS.includes(block.alignment) ? block.alignment.toLowerCase() : 'left'
      const weight = block.fontWeight === 'BOLD' ? 700 : 400
      const fontClass = hasOwn(FONT_CLASSES, block.fontFamily) ? FONT_CLASSES[block.fontFamily] : FONT_CLASSES.SYSTEM
      return {
        ...block, displayColor, fontClass,
        style: spacing + 'font-size:' + fontSize + 'rpx;font-weight:' + weight + ';color:' + displayColor + ';text-align:' + alignment + ';'
      }
    })
  }
}

/** 防止选择新作品后，旧资源展示副本仍被用作当前背景。 */
function validBackgroundWork(source) {
  const work = source.backgroundWork
  return isObject(work) && validId(work.workId) && String(work.workId) === String(source.backgroundWorkId)
    && IMAGE_MEDIA_TYPES.includes(work.mediaType) && typeof work.url === 'string' && !!work.url.trim()
}

/** 资源地址仅来自后端授权后的 backgroundWork，动图不使用静态封面替换。 */
function buildTextBackgroundPresentation(config = {}) {
  const source = isObject(config) ? config : {}
  const enabled = source.backgroundEnabled === true
  const work = source.backgroundWork
  const valid = enabled && source.backgroundInvalid !== true && validBackgroundWork(source)
  const validDimensions = valid && typeof work.width === 'number' && Number.isFinite(work.width) && work.width > 0
    && typeof work.height === 'number' && Number.isFinite(work.height) && work.height > 0
  const height = validDimensions ? VIEWPORT_WIDTH_RPX * work.height / work.width : 0
  return {
    enabled, imageUrl: valid ? work.url : '',
    minHeightRpx: Number.isFinite(height) ? height : 0,
    treatmentClass: enabled ? (hasOwn(TREATMENT_CLASSES, source.backgroundTreatment) ? TREATMENT_CLASSES[source.backgroundTreatment] : TREATMENT_CLASSES.GRADIENT) : '',
    invalid: enabled && !valid
  }
}

/** 图片加载后以实际容器宽度计算最小高度，同一背景刷新文字时复用已测量尺寸。 */
function buildTextBackgroundFrameStyle(background, layout) {
  if (!background.enabled || !background.imageUrl) return ''
  if (layout && layout.imageUrl === background.imageUrl) {
    const measured = Number.isFinite(layout.frameWidth) && layout.frameWidth > 0
    const height = (measured ? layout.frameWidth : VIEWPORT_WIDTH_RPX) * layout.height / layout.width
    if (Number.isFinite(height) && height > 0) return `min-height:${height}${measured ? 'px' : 'rpx'};`
  }
  return background.minHeightRpx > 0 ? `min-height:${background.minHeightRpx}rpx;` : ''
}

/** 旧作品可能缺少宽高；从原图加载事件补足，并拒绝换图后的过期回调。 */
function handleTextBackgroundLoad(component, event) {
  const background = component.data.background
  const dataset = event && event.currentTarget && event.currentTarget.dataset || {}
  const imageUrl = dataset.src
  const dimensions = event && event.detail || {}
  if (!background.enabled || !imageUrl || imageUrl !== background.imageUrl
      || !Number.isFinite(dimensions.width) || dimensions.width <= 0
      || !Number.isFinite(dimensions.height) || dimensions.height <= 0) return
  const layout = { imageUrl, width: dimensions.width, height: dimensions.height }
  component._textBackgroundLayout = layout
  component.setData({ frameStyle: buildTextBackgroundFrameStyle(background, layout) })
  // 图片宽度包含组件内边距，避免编辑预览比屏幕窄时按 750rpx 计算导致裁切。
  component.createSelectorQuery().select(BACKGROUND_IMAGE_SELECTOR).boundingClientRect(rect => {
    if (component._textBackgroundLayout !== layout || !component.data.background.enabled
        || component.data.background.imageUrl !== imageUrl || !rect
        || !Number.isFinite(rect.width) || rect.width <= 0) return
    layout.frameWidth = rect.width
    component.setData({ frameStyle: buildTextBackgroundFrameStyle(component.data.background, layout) })
  }).exec()
}

module.exports = {
  MIN_FONT_SIZE, MAX_FONT_SIZE,
  createStructuredBlock, normalizeTextBackground, finalizeTextBackground, validateTextBackground,
  normalizeStructuredTextConfig, validateStructuredTextConfig, finalizeStructuredTextConfig,
  normalizeSpacingInput, countStructuredText, createBlockEditDraft, switchBlockEditType,
  buildStructuredTextPresentation, buildTextBackgroundPresentation,
  buildTextBackgroundFrameStyle, handleTextBackgroundLoad
}
