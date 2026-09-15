const CONTACT_FIELDS = ['contactPhone', 'contactWechat']
const LIMITS = { contactPhone: 32, contactWechat: 64 }
const LABELS = { contactPhone: '联系手机', contactWechat: '联系微信' }
const CONTROL = /[\u0000-\u001f\u007f-\u009f\u2028\u2029]/u
const BORDER_AUTO_COLOR = 'AUTO'
const BORDER_COLORS = { light: '#D7DADD', dark: '#45464A' }
const COLOR_PATTERN = /^(AUTO|#[0-9A-Fa-f]{6})$/
const BORDER_NUMBER_FIELDS = Object.freeze({
  verticalMarginRpx: { label: '上下留白', min: 0, max: 96, fallback: 0 },
  horizontalMarginRpx: { label: '左右留白', min: 0, max: 96, fallback: 0 },
  contactBorderWidthRpx: { label: '线宽', min: 1, max: 12, fallback: 1 }
})
const owns = (value, key) => Object.prototype.hasOwnProperty.call(value, key)
const validNumber = (value, settings) => Number.isInteger(value) && value >= settings.min && value <= settings.max

/** 历史联系信息保持无边框；关闭时仍保留外观参数，重新开启可继续使用。 */
function normalizeContactInfo(raw = {}) {
  const safe = raw || {}
  const result = CONTACT_FIELDS.reduce((value, field) => ({ ...value, [field]: typeof safe[field] === 'string' ? safe[field].trim() : '' }), {})
  result.contactBorder = safe.contactBorder === true
  result.contactBorderColor = typeof safe.contactBorderColor === 'string' && COLOR_PATTERN.test(safe.contactBorderColor)
    ? safe.contactBorderColor.toUpperCase() : BORDER_AUTO_COLOR
  Object.entries(BORDER_NUMBER_FIELDS).forEach(([field, settings]) => {
    result[field] = validNumber(safe[field], settings) ? safe[field] : settings.fallback
  })
  return result
}

/** 留白由边框外层承载，避免上下 margin 折叠；文本内侧沿用原有内边距。 */
function buildContactInfoStyle(raw, themeMode = 'light') {
  const config = normalizeContactInfo(raw)
  if (!config.contactBorder) return { spacingStyle: '', borderStyle: '' }
  const color = config.contactBorderColor === BORDER_AUTO_COLOR ? (BORDER_COLORS[themeMode] || BORDER_COLORS.light) : config.contactBorderColor
  return {
    spacingStyle: `padding: ${config.verticalMarginRpx}rpx ${config.horizontalMarginRpx}rpx;`,
    borderStyle: `border-width: ${config.contactBorderWidthRpx}rpx; border-color: ${color}; border-style: solid;`
  }
}
function validateContactInfo(raw, requireContent = false) {
  for (const field of CONTACT_FIELDS) {
    const value = raw && raw[field]
    if (typeof value !== 'string' || CONTROL.test(value)) return `${LABELS[field]}不支持换行或控制字符`
    if (Array.from(value.trim()).length > LIMITS[field]) return `${LABELS[field]}不能超过 ${LIMITS[field]} 个字`
  }
  if (owns(raw, 'contactBorder') && typeof raw.contactBorder !== 'boolean') return '边框开关格式不正确'
  for (const [field, settings] of Object.entries(BORDER_NUMBER_FIELDS)) {
    if (owns(raw, field) && !validNumber(raw[field], settings)) return `${settings.label}须为 ${settings.min}–${settings.max} rpx 整数`
  }
  if (owns(raw, 'contactBorderColor') && (typeof raw.contactBorderColor !== 'string' || !COLOR_PATTERN.test(raw.contactBorderColor))) return '边框颜色须为完整 HEX 颜色或跟随主题'
  return requireContent && !CONTACT_FIELDS.some(field => raw[field].trim()) ? '请至少填写一项联系信息' : ''
}
module.exports = { BORDER_NUMBER_FIELDS, normalizeContactInfo, validateContactInfo, buildContactInfoStyle }
