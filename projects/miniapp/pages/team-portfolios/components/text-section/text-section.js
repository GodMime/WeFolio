const TEXT_SECTION_MAX_LENGTH = 200
const TEXT_ALIGNMENTS = Object.freeze(['LEFT', 'CENTER', 'RIGHT'])
const { buildTextBackgroundPresentation, buildTextBackgroundFrameStyle, handleTextBackgroundLoad } = require('../../utils/portfolio-text-sections')
const { normalizeTextColor, buildTextColorStyle, isValidTextColor, TEXT_COLOR_ERROR } = require('../../utils/portfolio-text-color')
// 排版常量沿用原团队文字组件；新增背景逻辑不改变旧字体与字号回退。
const LEGACY_TEAM_FONT_SIZE_RPX = 32
const TEXT_FONT_SIZE_MIN_RPX = 20
const TEXT_FONT_SIZE_MAX_RPX = 48
const TEXT_FONT_CLASS_MAP = Object.freeze({
  SYSTEM: 'font-system',
  WECHAT_SANS_SS: 'font-wechat-sans-ss'
})

function text(value) {
  return String(value || '').trim()
}

function displayText(value) {
  // 展示路径保留作者输入的首尾空格；校验时仅通过 text() 使用 trim 后的副本判断是否为空和长度。
  return typeof value === 'string' ? value : ''
}

function countTextCodePoints(value) {
  return Array.from(String(value || '')).length
}

function normalizeTextSectionTypography(source = {}) {
  const fontFamily = Object.prototype.hasOwnProperty.call(
    TEXT_FONT_CLASS_MAP,
    source.fontFamily
  )
    ? source.fontFamily
    : 'SYSTEM'
  const fontSizeRpx = typeof source.fontSizeRpx === 'number'
    && Number.isInteger(source.fontSizeRpx)
    && source.fontSizeRpx >= TEXT_FONT_SIZE_MIN_RPX
    && source.fontSizeRpx <= TEXT_FONT_SIZE_MAX_RPX
    ? source.fontSizeRpx
    : LEGACY_TEAM_FONT_SIZE_RPX
  return {
    fontFamily,
    fontSizeRpx,
    fontClass: TEXT_FONT_CLASS_MAP[fontFamily],
    fontSizeStyle: `font-size: ${fontSizeRpx}rpx;`
  }
}

function createDefaultTextSectionConfig(source = {}) {
  const normalizedSource = source || {}
  return {
    content: displayText(normalizedSource.content),
    color: normalizeTextColor(normalizedSource.color),
    alignment: TEXT_ALIGNMENTS.includes(normalizedSource.alignment) ? normalizedSource.alignment : 'LEFT',
    ...normalizeTextSectionTypography(normalizedSource)
  }
}

function validateTextSectionConfig(config = {}) {
  const content = text(config.content)
  if (!content) return { valid: false, message: '请填写正文' }
  if (countTextCodePoints(content) > TEXT_SECTION_MAX_LENGTH) return { valid: false, message: '正文不能超过200字' }
  if (!TEXT_ALIGNMENTS.includes(config.alignment)) return { valid: false, message: '请选择有效的对齐方式' }
  if (!isValidTextColor(config.color)) return { valid: false, message: TEXT_COLOR_ERROR }
  return { valid: true, message: '' }
}

Component({
  properties: {
    themeMode: { type: String, value: 'light' },
    config: { type: Object, value: {} },
    repairMode: { type: Boolean, value: false }
  },
  data: {
    normalizedConfig: createDefaultTextSectionConfig(),
    background: { enabled: false, imageUrl: '', invalid: false },
    frameStyle: '', verticalClass: '', imageFailed: false, showRepairHint: false, textColorStyle: ''
  },
  observers: {
    config(config) {
      this.setData({
        normalizedConfig: createDefaultTextSectionConfig(config)
      })
      this.updatePresentation()
    },
    repairMode() { this.updatePresentation() }
  },
  methods: {
    updatePresentation() {
      const config = this.properties.config || {}
      const background = buildTextBackgroundPresentation(config)
      const imageFailed = this.data.background.imageUrl === background.imageUrl && this.data.imageFailed
      const verticalAlignment = ['TOP', 'CENTER', 'BOTTOM'].includes(config.verticalAlignment) ? config.verticalAlignment : 'CENTER'
      this.setData({
        normalizedConfig: createDefaultTextSectionConfig(config), background,
        textColorStyle: buildTextColorStyle(config.color),
        frameStyle: buildTextBackgroundFrameStyle(background, this._textBackgroundLayout),
        verticalClass: background.enabled ? `vertical-${verticalAlignment}` : '',
        imageFailed: Boolean(imageFailed),
        showRepairHint: Boolean(this.properties.repairMode && background.enabled && (background.invalid || imageFailed))
      })
    },
    handleBackgroundLoad(event) { handleTextBackgroundLoad(this, event) },
    handleBackgroundError() {
      this.setData({ imageFailed: true, showRepairHint: Boolean(this.properties.repairMode && this.data.background.enabled) })
    }
  }
})

module.exports = { TEXT_ALIGNMENTS, TEXT_SECTION_MAX_LENGTH, countTextCodePoints, createDefaultTextSectionConfig, normalizeTextSectionTypography, validateTextSectionConfig }
