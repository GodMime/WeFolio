const PORTFOLIO_TEXT_FONT_FAMILIES = Object.freeze({
  SYSTEM: 'SYSTEM',
  WECHAT_SANS_SS: 'WECHAT_SANS_SS'
})

const PORTFOLIO_TEXT_FONT_CLASS_MAP = Object.freeze({
  SYSTEM: 'font-system',
  WECHAT_SANS_SS: 'font-wechat-sans-ss'
})

const PORTFOLIO_TEXT_FONT_SAMPLE = '映期 Folio 字体预览 123'
const PORTFOLIO_TEXT_FONT_SIZE_MIN_RPX = 20
const PORTFOLIO_TEXT_FONT_SIZE_MAX_RPX = 48
const NEW_COMPONENT_FONT_SIZE_RPX = 28
const LEGACY_PERSONAL_FONT_SIZE_RPX = 26
// 旧团队组件继承页面默认字号，32rpx 是对现有视觉效果的兼容近似值。
const LEGACY_TEAM_FONT_SIZE_RPX = 32
const PORTFOLIO_TEXT_FONT_SIZE_VALUES = Object.freeze([26, 28, 32, 36])

const PORTFOLIO_TEXT_FONT_OPTIONS = Object.freeze([
  Object.freeze({
    value: PORTFOLIO_TEXT_FONT_FAMILIES.SYSTEM,
    label: '系统默认',
    fontClass: PORTFOLIO_TEXT_FONT_CLASS_MAP.SYSTEM,
    sample: PORTFOLIO_TEXT_FONT_SAMPLE
  }),
  Object.freeze({
    value: PORTFOLIO_TEXT_FONT_FAMILIES.WECHAT_SANS_SS,
    label: '微信字体',
    fontClass: PORTFOLIO_TEXT_FONT_CLASS_MAP.WECHAT_SANS_SS,
    sample: PORTFOLIO_TEXT_FONT_SAMPLE
  })
])

const PORTFOLIO_TEXT_FONT_SIZE_OPTIONS = Object.freeze(
  PORTFOLIO_TEXT_FONT_SIZE_VALUES.map((value) => Object.freeze({
    value,
    label: `${value}rpx`
  }))
)

function normalizePortfolioTextFontFamily(value) {
  return Object.values(PORTFOLIO_TEXT_FONT_FAMILIES).includes(value)
    ? value
    : PORTFOLIO_TEXT_FONT_FAMILIES.SYSTEM
}

function isValidPortfolioTextFontSizeRpx(value) {
  return typeof value === 'number'
    && Number.isInteger(value)
    && value >= PORTFOLIO_TEXT_FONT_SIZE_MIN_RPX
    && value <= PORTFOLIO_TEXT_FONT_SIZE_MAX_RPX
}

function normalizePortfolioTextFontSizeRpx(
  value,
  fallback = LEGACY_PERSONAL_FONT_SIZE_RPX
) {
  if (isValidPortfolioTextFontSizeRpx(value)) return value
  return isValidPortfolioTextFontSizeRpx(fallback)
    ? fallback
    : LEGACY_PERSONAL_FONT_SIZE_RPX
}

function buildPortfolioTextTypography(
  raw = {},
  fallbackSizeRpx = LEGACY_PERSONAL_FONT_SIZE_RPX
) {
  const fontFamily = normalizePortfolioTextFontFamily(raw && raw.fontFamily)
  const fontSizeRpx = normalizePortfolioTextFontSizeRpx(
    raw && raw.fontSizeRpx,
    fallbackSizeRpx
  )
  return {
    fontFamily,
    fontSizeRpx,
    fontClass: PORTFOLIO_TEXT_FONT_CLASS_MAP[fontFamily],
    fontSizeStyle: `font-size: ${fontSizeRpx}rpx;`
  }
}

function buildPortfolioTextFontSizeOptions(currentSizeRpx) {
  const options = PORTFOLIO_TEXT_FONT_SIZE_OPTIONS.map((item) =>
    Object.assign({}, item)
  )
  const normalized = normalizePortfolioTextFontSizeRpx(currentSizeRpx, NaN)
  if (isValidPortfolioTextFontSizeRpx(currentSizeRpx)
    && !PORTFOLIO_TEXT_FONT_SIZE_VALUES.includes(normalized)) {
    options.push({
      value: normalized,
      label: `自定义 ${normalized}rpx`,
      custom: true
    })
  }
  return options
}

module.exports = {
  LEGACY_PERSONAL_FONT_SIZE_RPX,
  LEGACY_TEAM_FONT_SIZE_RPX,
  NEW_COMPONENT_FONT_SIZE_RPX,
  PORTFOLIO_TEXT_FONT_CLASS_MAP,
  PORTFOLIO_TEXT_FONT_FAMILIES,
  PORTFOLIO_TEXT_FONT_OPTIONS,
  PORTFOLIO_TEXT_FONT_SIZE_MAX_RPX,
  PORTFOLIO_TEXT_FONT_SIZE_MIN_RPX,
  PORTFOLIO_TEXT_FONT_SIZE_OPTIONS,
  PORTFOLIO_TEXT_FONT_SIZE_VALUES,
  buildPortfolioTextFontSizeOptions,
  buildPortfolioTextTypography,
  isValidPortfolioTextFontSizeRpx,
  normalizePortfolioTextFontFamily,
  normalizePortfolioTextFontSizeRpx
}
