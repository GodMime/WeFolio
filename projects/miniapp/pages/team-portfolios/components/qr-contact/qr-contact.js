const QR_SOURCE_CUSTOM = 'CUSTOM'
const QR_EVENT_TYPE = 'QR_CODE_INTERACTED'
const QR_ACTION_CLICK = 'CLICK'
const QR_ACTION_LONG_PRESS = 'LONG_PRESS'

function text(value) {
  return String(value || '').trim()
}

function createDefaultQrContactConfig(source = {}) {
  return {
    qrUrlSource: QR_SOURCE_CUSTOM,
    qrUrl: text(source.qrUrl)
  }
}

function validateQrContactConfig(config = {}) {
  if (config.qrUrlSource !== QR_SOURCE_CUSTOM) return { valid: false, message: '团队二维码只允许自定义图片' }
  if (!text(config.qrUrl)) return { valid: false, message: '请选择二维码图片' }
  return { valid: true, message: '' }
}

function interactionDetail(context, action) {
  return { eventType: QR_EVENT_TYPE, componentKey: text(context.properties.componentKey), action }
}

function handleQrTap(context, wxApi) {
  const config = context.properties.config || {}
  const validation = validateQrContactConfig(config)
  if (!validation.valid) return false
  if (wxApi && wxApi.previewImage) wxApi.previewImage({ urls: [config.qrUrl], current: config.qrUrl })
  if (context.properties.visitorMode === true) context.triggerEvent('interact', interactionDetail(context, QR_ACTION_CLICK))
  else context.triggerEvent('preview', { componentKey: text(context.properties.componentKey), qrUrl: config.qrUrl })
  return true
}

function handleQrLongPress(context) {
  if (context.properties.visitorMode !== true) return false
  context.triggerEvent('interact', interactionDetail(context, QR_ACTION_LONG_PRESS))
  return true
}

function syncQrDraft(component) {
  component.setData({ draft: createDefaultQrContactConfig(component.properties.config), errorMessage: '' })
}

Component({
  properties: {
    themeMode: { type: String, value: 'light' },
    componentKey: { type: String, value: '' },
    config: { type: Object, value: {}, observer() { if (!this.properties.editMode) syncQrDraft(this) } },
    editMode: { type: Boolean, value: false, observer(value, oldValue) { if (value !== oldValue) syncQrDraft(this) } },
    visitorMode: { type: Boolean, value: false },
    choosing: { type: Boolean, value: false }
  },
  data: { draft: createDefaultQrContactConfig(), errorMessage: '' },
  methods: {
    beginEdit() { syncQrDraft(this) },
    chooseImage() { this.triggerEvent('choose', { assetType: 'QR_CONTACT', qrUrlSource: QR_SOURCE_CUSTOM }) },
    applySelectedImage(event) { const draft = createDefaultQrContactConfig({ qrUrl: event.detail.qrUrl }); this.setData({ draft, errorMessage: '' }) },
    cancelEdit() { syncQrDraft(this); this.triggerEvent('cancel') },
    saveEdit() { const validation = validateQrContactConfig(this.data.draft); if (!validation.valid) return this.setData({ errorMessage: validation.message }); this.triggerEvent('save', { config: createDefaultQrContactConfig(this.data.draft) }) },
    previewDraft() { const runtimeWx = typeof wx !== 'undefined' ? wx : null; const context = { properties: { config: this.data.draft, componentKey: this.properties.componentKey, visitorMode: false }, triggerEvent: this.triggerEvent.bind(this) }; handleQrTap(context, runtimeWx) },
    handleTap() { handleQrTap(this, typeof wx !== 'undefined' ? wx : null) },
    handleLongPress() { handleQrLongPress(this) }
  },
  lifetimes: {
    attached() { syncQrDraft(this) }
  }
})

module.exports = {
  QR_ACTION_CLICK,
  QR_ACTION_LONG_PRESS,
  QR_EVENT_TYPE,
  QR_SOURCE_CUSTOM,
  createDefaultQrContactConfig,
  handleQrLongPress,
  handleQrTap,
  validateQrContactConfig
}
