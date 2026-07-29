const DIVIDER_COLORS = Object.freeze({ BLACK: '#17202a', WHITE: '#ffffff', GRAY: '#d7dfe1', TRANSPARENT: 'transparent' })
const DEFAULT_DIVIDER_HEIGHT = 16

function createDefaultDividerConfig() {
  return { color: 'GRAY', heightPx: DEFAULT_DIVIDER_HEIGHT }
}

function normalizeDividerConfig(config = {}) {
  const color = Object.prototype.hasOwnProperty.call(DIVIDER_COLORS, config.color) ? config.color : 'GRAY'
  const heightPx = Number.isInteger(Number(config.heightPx)) && Number(config.heightPx) > 0 ? Number(config.heightPx) : DEFAULT_DIVIDER_HEIGHT
  return { color, heightPx }
}

function validateDividerConfig(config = {}) {
  if (!Object.prototype.hasOwnProperty.call(DIVIDER_COLORS, config.color)) return { valid: false, message: '请选择有效的分割线颜色' }
  if (!Number.isInteger(Number(config.heightPx)) || Number(config.heightPx) <= 0) return { valid: false, message: '分割线高度必须为正整数' }
  return { valid: true, message: '' }
}

function syncDividerDraft(component) {
  component.setData({ draft: normalizeDividerConfig(component.properties.config), errorMessage: '' })
}

Component({
  properties: {
    themeMode: { type: String, value: 'light' },
    config: { type: Object, value: {}, observer() { if (!this.properties.editMode) syncDividerDraft(this) } },
    editMode: { type: Boolean, value: false, observer(value, oldValue) { if (value !== oldValue) syncDividerDraft(this) } }
  },
  data: { draft: createDefaultDividerConfig(), colorOptions: Object.keys(DIVIDER_COLORS), errorMessage: '' },
  methods: {
    beginEdit() { syncDividerDraft(this) },
    selectColor(event) { const draft = Object.assign({}, this.data.draft, { color: event.currentTarget.dataset.color }); this.setData({ draft }); this.triggerEvent('change', { config: draft }) },
    changeHeight(event) { const draft = Object.assign({}, this.data.draft, { heightPx: Number(event.detail.value) }); this.setData({ draft }); this.triggerEvent('change', { config: draft }) },
    cancelEdit() { syncDividerDraft(this); this.triggerEvent('cancel') },
    saveEdit() { const validation = validateDividerConfig(this.data.draft); if (!validation.valid) return this.setData({ errorMessage: validation.message }); this.triggerEvent('save', { config: normalizeDividerConfig(this.data.draft) }) }
  },
  lifetimes: {
    attached() { syncDividerDraft(this) }
  }
})

module.exports = { DEFAULT_DIVIDER_HEIGHT, DIVIDER_COLORS, createDefaultDividerConfig, normalizeDividerConfig, validateDividerConfig }
