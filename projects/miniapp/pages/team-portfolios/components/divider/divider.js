const DIVIDER_COLORS = Object.freeze({ BLACK: '#17202a', WHITE: '#ffffff', GRAY: '#d7dfe1', TRANSPARENT: 'transparent' })
const DEFAULT_DIVIDER_HEIGHT = 16
const DIVIDER_HEX_COLOR_PATTERN = /^#[0-9A-Fa-f]{6}$/
// 旧 BLACK 的展示长期使用深灰，不能因接入自定义颜色而改变已有作品集。
const LEGACY_BLACK_RENDER_COLOR = '#212529'

/** 保留既有枚举，并只放行六位 HEX，避免把任意样式写入分割线。 */
function isValidDividerColor(value) {
  return typeof value === 'string' &&
    (Object.prototype.hasOwnProperty.call(DIVIDER_COLORS, value) || DIVIDER_HEX_COLOR_PATTERN.test(value))
}

/** 旧枚举映射保持原展示语义，自定义色值归一为大写。 */
function resolveDividerColorValue(value) {
  if (value === 'BLACK') return LEGACY_BLACK_RENDER_COLOR
  if (Object.prototype.hasOwnProperty.call(DIVIDER_COLORS, value)) return DIVIDER_COLORS[value]
  return typeof value === 'string' && DIVIDER_HEX_COLOR_PATTERN.test(value) ? value.toUpperCase() : DIVIDER_COLORS.GRAY
}

function createDefaultDividerConfig() {
  return { color: 'GRAY', heightPx: DEFAULT_DIVIDER_HEIGHT }
}

function normalizeDividerConfig(config = {}) {
  const color = isValidDividerColor(config.color) ? config.color.toUpperCase() : 'GRAY'
  const heightPx = Number.isInteger(Number(config.heightPx)) && Number(config.heightPx) > 0 ? Number(config.heightPx) : DEFAULT_DIVIDER_HEIGHT
  return { color, heightPx }
}

function validateDividerConfig(config = {}) {
  if (!isValidDividerColor(config.color)) return { valid: false, message: '请选择有效的分割线颜色' }
  if (!Number.isInteger(Number(config.heightPx)) || Number(config.heightPx) <= 0) return { valid: false, message: '分割线高度必须为正整数' }
  return { valid: true, message: '' }
}

function syncDividerDraft(component) {
  const draft = normalizeDividerConfig(component.properties.config)
  component.setData({ draft, colorValue: resolveDividerColorValue(draft.color), errorMessage: '' })
}

Component({
  properties: {
    themeMode: { type: String, value: 'light' },
    config: { type: Object, value: {}, observer() { if (!this.properties.editMode) syncDividerDraft(this) } },
    editMode: { type: Boolean, value: false, observer(value, oldValue) { if (value !== oldValue) syncDividerDraft(this) } }
  },
  data: { draft: createDefaultDividerConfig(), colorValue: DIVIDER_COLORS.GRAY, errorMessage: '' },
  methods: {
    beginEdit() { syncDividerDraft(this) },
    selectColor(event) {
      const color = event.detail.color
      if (!isValidDividerColor(color)) return
      const draft = normalizeDividerConfig(Object.assign({}, this.data.draft, { color }))
      this.setData({ draft, colorValue: resolveDividerColorValue(draft.color) })
      this.triggerEvent('change', { config: draft })
    },
    changeHeight(event) { const draft = Object.assign({}, this.data.draft, { heightPx: Number(event.detail.value) }); this.setData({ draft }); this.triggerEvent('change', { config: draft }) },
    cancelEdit() { syncDividerDraft(this); this.triggerEvent('cancel') },
    saveEdit() { const validation = validateDividerConfig(this.data.draft); if (!validation.valid) return this.setData({ errorMessage: validation.message }); this.triggerEvent('save', { config: normalizeDividerConfig(this.data.draft) }) }
  },
  lifetimes: {
    attached() { syncDividerDraft(this) }
  }
})

module.exports = { DEFAULT_DIVIDER_HEIGHT, DIVIDER_COLORS, createDefaultDividerConfig, normalizeDividerConfig, validateDividerConfig, resolveDividerColorValue }
