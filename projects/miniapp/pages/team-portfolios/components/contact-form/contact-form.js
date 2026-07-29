const CONTACT_SUCCESS_TEXT = '已提交给团队'
const DISPLAY_MODES = Object.freeze(['MODAL_FORM', 'INLINE_FORM'])
const DEFAULT_FIELDS = Object.freeze(['contactName', 'phone', 'wechat', 'needs'])

function text(value) {
  return String(value || '').trim()
}

function createDefaultContactForm(source = {}) {
  const form = source || {}
  return {
    contactName: text(form.contactName),
    phone: text(form.phone),
    wechat: text(form.wechat),
    desiredSchedule: text(form.desiredSchedule),
    needs: text(form.needs)
  }
}

function normalizeFields(fields) {
  if (!Array.isArray(fields) || fields.length === 0) return DEFAULT_FIELDS.slice()
  return Array.from(new Set(fields.map(text).filter(Boolean)))
}

function createDefaultContactConfig(source = {}) {
  return {
    title: text(source.title),
    description: text(source.description),
    displayMode: DISPLAY_MODES.includes(source.displayMode) ? source.displayMode : 'MODAL_FORM',
    fields: normalizeFields(source.fields)
  }
}

function createFieldVisibility(fields) {
  const source = normalizeFields(fields)
  return {
    contactName: source.includes('contactName'),
    phone: source.includes('phone'),
    wechat: source.includes('wechat'),
    needs: source.includes('needs')
  }
}

function buildVisibleContactForm(form = {}, fields = DEFAULT_FIELDS) {
  const normalized = createDefaultContactForm(form)
  const visibility = createFieldVisibility(fields)
  return {
    contactName: visibility.contactName ? normalized.contactName : '',
    phone: visibility.phone ? normalized.phone : '',
    wechat: visibility.wechat ? normalized.wechat : '',
    desiredSchedule: normalized.desiredSchedule,
    needs: visibility.needs ? normalized.needs : ''
  }
}

function validateContactForm(form = {}, fields = DEFAULT_FIELDS) {
  const normalized = buildVisibleContactForm(form, fields)
  if (!normalized.contactName) return { valid: false, message: '请填写称呼' }
  if (!normalized.phone && !normalized.wechat) return { valid: false, message: '请至少填写手机号或微信号' }
  return { valid: true, message: '' }
}

function validateContactConfig(config = {}) {
  if (!DISPLAY_MODES.includes(config.displayMode)) return { valid: false, message: '请选择有效的表单展示方式' }
  const fields = normalizeFields(config.fields)
  if (!fields.includes('contactName')) return { valid: false, message: '表单必须包含联系人字段' }
  if (!fields.includes('phone') && !fields.includes('wechat')) return { valid: false, message: '表单必须包含手机号或微信号字段' }
  return { valid: true, message: '' }
}

function reduceContactSubmit(form, success) {
  return success ? createDefaultContactForm() : createDefaultContactForm(form)
}

function syncContactConfig(component, forceDraft) {
  const displayConfig = createDefaultContactConfig(component.properties.config)
  const fieldVisibility = createFieldVisibility(displayConfig.fields)
  const patch = { displayConfig, fieldVisibility }
  if (forceDraft || !component.properties.editMode) {
    patch.draftConfig = createDefaultContactConfig(component.properties.config)
    patch.draftFieldVisibility = createFieldVisibility(patch.draftConfig.fields)
    patch.errorMessage = ''
  }
  component.setData(patch)
}

Component({
  properties: {
    themeMode: { type: String, value: 'light' },
    config: { type: Object, value: {} },
    form: { type: Object, value: {} },
    editMode: { type: Boolean, value: false, observer(value, oldValue) { if (value !== oldValue) syncContactConfig(this, true) } },
    modalVisible: { type: Boolean, value: false },
    submitting: { type: Boolean, value: false },
    success: { type: Boolean, value: false }
  },
  data: {
    draftConfig: createDefaultContactConfig(),
    displayConfig: createDefaultContactConfig(),
    fieldVisibility: createFieldVisibility(DEFAULT_FIELDS),
    draftFieldVisibility: createFieldVisibility(DEFAULT_FIELDS),
    localForm: createDefaultContactForm(),
    errorMessage: ''
  },
  observers: {
    config(value) {
      syncContactConfig(this, false)
    },
    form(value) {
      this.setData({ localForm: createDefaultContactForm(value) })
    }
  },
  methods: {
    beginEdit() { const draftConfig = createDefaultContactConfig(this.properties.config); this.setData({ draftConfig, draftFieldVisibility: createFieldVisibility(draftConfig.fields), errorMessage: '' }) },
    handleConfigInput(event) { const draftConfig = Object.assign({}, this.data.draftConfig, { [event.currentTarget.dataset.field]: event.detail.value }); this.setData({ draftConfig }); this.triggerEvent('change', { config: draftConfig }) },
    selectDisplayMode(event) { const draftConfig = Object.assign({}, this.data.draftConfig, { displayMode: event.currentTarget.dataset.mode }); this.setData({ draftConfig }); this.triggerEvent('change', { config: draftConfig }) },
    toggleField(event) {
      const field = event.currentTarget.dataset.field
      const fields = this.data.draftConfig.fields.slice()
      const index = fields.indexOf(field)
      if (index >= 0) fields.splice(index, 1)
      else fields.push(field)
      const draftConfig = Object.assign({}, this.data.draftConfig, { fields })
      this.setData({ draftConfig, draftFieldVisibility: createFieldVisibility(fields) })
      this.triggerEvent('change', { config: draftConfig })
    },
    cancelEdit() { const draftConfig = createDefaultContactConfig(this.properties.config); this.setData({ draftConfig, draftFieldVisibility: createFieldVisibility(draftConfig.fields), errorMessage: '' }); this.triggerEvent('cancel') },
    saveEdit() { const validation = validateContactConfig(this.data.draftConfig); if (!validation.valid) return this.setData({ errorMessage: validation.message }); this.triggerEvent('save', { config: createDefaultContactConfig(this.data.draftConfig) }) },
    noop() {},
    openModal() { this.triggerEvent('openmodal') },
    closeModal() { this.triggerEvent('closemodal') },
    handleInput(event) { const field = event.currentTarget.dataset.field; const localForm = Object.assign({}, this.data.localForm, { [field]: event.detail.value }); this.setData({ localForm, errorMessage: '' }); this.triggerEvent('contactinput', { field, value: event.detail.value, form: localForm }) },
    handleDateChange(event) { this.handleInput(event) },
    submitContact() { const form = buildVisibleContactForm(this.data.localForm, this.data.displayConfig.fields); const validation = validateContactForm(form, this.data.displayConfig.fields); if (!validation.valid) return this.setData({ errorMessage: validation.message }); this.triggerEvent('submit', { form }) },
    completeSubmit(event) { const success = event.detail && event.detail.success === true; this.setData({ localForm: reduceContactSubmit(this.data.localForm, success), errorMessage: success ? '' : text(event.detail && event.detail.message) }); if (success) this.triggerEvent('submitsuccess', { message: CONTACT_SUCCESS_TEXT }) }
  },
  lifetimes: {
    attached() {
      syncContactConfig(this, true)
      this.setData({ localForm: createDefaultContactForm(this.properties.form) })
    }
  }
})

module.exports = { CONTACT_SUCCESS_TEXT, DEFAULT_FIELDS, DISPLAY_MODES, buildVisibleContactForm, createDefaultContactConfig, createDefaultContactForm, createFieldVisibility, reduceContactSubmit, validateContactConfig, validateContactForm }
