const PORTFOLIO_TEXT_FONT_FAMILIES = Object.freeze({
  SYSTEM: 'SYSTEM',
  WECHAT_SANS_SS: 'WECHAT_SANS_SS'
})

const PORTFOLIO_TEXT_FONT_CLASS_MAP = Object.freeze({
  SYSTEM: 'font-system',
  WECHAT_SANS_SS: 'font-wechat-sans-ss'
})

const PORTFOLIO_TEXT_FONT_SAMPLE = '映期 Folio 字体预览 123'
const PORTFOLIO_TEXT_FONT_SIZE_MIN_RPX = 10
const PORTFOLIO_TEXT_FONT_SIZE_MAX_RPX = 96
const NEW_COMPONENT_FONT_SIZE_RPX = 28
const LEGACY_PERSONAL_FONT_SIZE_RPX = 26
// 旧团队组件继承页面默认字号，32rpx 是对现有视觉效果的兼容近似值。
const LEGACY_TEAM_FONT_SIZE_RPX = 32
const PORTFOLIO_TEXT_FONT_SIZE_VALUES = Object.freeze([26, 28, 32, 36])
const PORTFOLIO_TEXT_LINE_HEIGHT_MIN = 0.5
const PORTFOLIO_TEXT_LINE_HEIGHT_MAX = 3
const PORTFOLIO_TEXT_LINE_HEIGHT_STEP = 0.1
const PORTFOLIO_TEXT_LINE_HEIGHT_ERROR = '行间距须为 0.5–3.0 倍，步长 0.1'
const LEGACY_TEXT_SECTION_LINE_HEIGHT = 1.75
const LINE_HEIGHT_SCALE = 10

/** 行距按十分之一倍存储，旧配置不补值以保留原来的 CSS 行高。 */
function isValidPortfolioTextLineHeight(value) {
  return typeof value === 'number' && Number.isFinite(value)
    && value >= PORTFOLIO_TEXT_LINE_HEIGHT_MIN && value <= PORTFOLIO_TEXT_LINE_HEIGHT_MAX
    && Number.isInteger(value * LINE_HEIGHT_SCALE)
}

function parsePortfolioTextLineHeightInput(raw) {
  const text = String(raw)
  const value = /^(?:\d+(?:\.\d+)?|\.\d+)$/.test(text) ? Number(text) : NaN
  return isValidPortfolioTextLineHeight(value) ? value : text
}

/** 用整数刻度增减，缺省旧值位于刻度之间时取调节方向上的相邻刻度。 */
function stepPortfolioTextLineHeight(value, delta, fallback) {
  if (Math.abs(delta) !== PORTFOLIO_TEXT_LINE_HEIGHT_STEP
    || (value !== undefined && !isValidPortfolioTextLineHeight(value))) return value
  const current = value === undefined ? fallback : value
  const scaled = current * LINE_HEIGHT_SCALE
  const next = delta > 0 ? Math.floor(scaled) + 1 : Math.ceil(scaled) - 1
  return Math.max(PORTFOLIO_TEXT_LINE_HEIGHT_MIN,
    Math.min(PORTFOLIO_TEXT_LINE_HEIGHT_MAX, next / LINE_HEIGHT_SCALE))
}

function buildPortfolioTextLineHeightStyle(value) {
  return isValidPortfolioTextLineHeight(value) ? `line-height: ${value};` : ''
}

function buildPortfolioTextLineHeightEditor(value, fallback) {
  const invalid = value !== undefined && !isValidPortfolioTextLineHeight(value)
  return {
    value: value === undefined ? '' : value,
    placeholder: `默认（${fallback} 倍）`,
    min: PORTFOLIO_TEXT_LINE_HEIGHT_MIN, max: PORTFOLIO_TEXT_LINE_HEIGHT_MAX,
    step: PORTFOLIO_TEXT_LINE_HEIGHT_STEP,
    error: invalid ? PORTFOLIO_TEXT_LINE_HEIGHT_ERROR : '',
    decreaseDisabled: invalid || value <= PORTFOLIO_TEXT_LINE_HEIGHT_MIN,
    increaseDisabled: invalid || value >= PORTFOLIO_TEXT_LINE_HEIGHT_MAX
  }
}

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
    fontSizeStyle: `font-size: ${fontSizeRpx}rpx;`,
    ...(isValidPortfolioTextLineHeight(raw && raw.lineHeight)
      ? { lineHeightStyle: buildPortfolioTextLineHeightStyle(raw.lineHeight) } : {})
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
  LEGACY_TEXT_SECTION_LINE_HEIGHT,
  PORTFOLIO_TEXT_LINE_HEIGHT_MIN,
  PORTFOLIO_TEXT_LINE_HEIGHT_MAX,
  PORTFOLIO_TEXT_LINE_HEIGHT_STEP,
  PORTFOLIO_TEXT_LINE_HEIGHT_ERROR,
  isValidPortfolioTextLineHeight,
  parsePortfolioTextLineHeightInput,
  stepPortfolioTextLineHeight,
  buildPortfolioTextLineHeightStyle,
  buildPortfolioTextLineHeightEditor,
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
