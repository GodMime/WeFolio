// 团队组件保持分包内依赖；与主包颜色纯规则通过一致性测试同步。
const TEXT_COLOR_AUTO = 'AUTO'
const TEXT_COLOR_HEX = /^#[0-9a-fA-F]{6}$/
const TEXT_COLOR_ERROR = '颜色须为跟随主题或六位十六进制颜色'

function isValidTextColor(value) {
  return value == null || value === TEXT_COLOR_AUTO || (typeof value === 'string' && TEXT_COLOR_HEX.test(value))
}

/** 缺省即跟随主题；非法显式配置不静默改成默认值，交由保存校验提示。 */
function normalizeTextColor(value) {
  if (value == null) return TEXT_COLOR_AUTO
  return typeof value === 'string' && TEXT_COLOR_HEX.test(value) ? value.toUpperCase() : value
}

/** AUTO 留空以使用原组件主题样式；仅允许六位颜色覆盖文字，防止样式注入。 */
function buildTextColorStyle(value) {
  const color = normalizeTextColor(value)
  return typeof color === 'string' && TEXT_COLOR_HEX.test(color) ? `color:${color};` : ''
}

module.exports = { TEXT_COLOR_AUTO, TEXT_COLOR_ERROR, isValidTextColor, normalizeTextColor, buildTextColorStyle }
