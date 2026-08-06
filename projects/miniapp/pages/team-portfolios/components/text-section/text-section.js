const TEXT_SECTION_MAX_LENGTH = 200
const TEXT_ALIGNMENTS = Object.freeze(['LEFT', 'CENTER', 'RIGHT'])
// 团队展示组件包禁止 require 外部模块；以下排版常量镜像 utils/portfolio-text-typography.js，变更时需同步维护。
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
    alignment: TEXT_ALIGNMENTS.includes(normalizedSource.alignment) ? normalizedSource.alignment : 'LEFT',
    ...normalizeTextSectionTypography(normalizedSource)
  }
}

function validateTextSectionConfig(config = {}) {
  const content = text(config.content)
  if (!content) return { valid: false, message: '请填写正文' }
  if (countTextCodePoints(content) > TEXT_SECTION_MAX_LENGTH) return { valid: false, message: '正文不能超过200字' }
  if (!TEXT_ALIGNMENTS.includes(config.alignment)) return { valid: false, message: '请选择有效的对齐方式' }
  return { valid: true, message: '' }
}

Component({
  properties: {
    themeMode: { type: String, value: 'light' },
    config: { type: Object, value: {} }
  },
  data: {
    normalizedConfig: createDefaultTextSectionConfig()
  },
  observers: {
    config(config) {
      this.setData({
        normalizedConfig: createDefaultTextSectionConfig(config)
      })
    }
  }
})

module.exports = { TEXT_ALIGNMENTS, TEXT_SECTION_MAX_LENGTH, countTextCodePoints, createDefaultTextSectionConfig, normalizeTextSectionTypography, validateTextSectionConfig }
