const TEXT_SECTION_MAX_LENGTH = 200
const TEXT_ALIGNMENTS = Object.freeze(['LEFT', 'CENTER', 'RIGHT'])

function text(value) {
  return String(value || '').trim()
}

function countTextCodePoints(value) {
  return Array.from(String(value || '')).length
}

function createDefaultTextSectionConfig(source = {}) {
  return {
    content: text(source.content),
    alignment: TEXT_ALIGNMENTS.includes(source.alignment) ? source.alignment : 'LEFT'
  }
}

function validateTextSectionConfig(config = {}) {
  const content = text(config.content)
  if (!content) return { valid: false, message: '请填写正文' }
  if (countTextCodePoints(content) > TEXT_SECTION_MAX_LENGTH) return { valid: false, message: '正文不能超过200字' }
  if (!TEXT_ALIGNMENTS.includes(config.alignment)) return { valid: false, message: '请选择有效的对齐方式' }
  return { valid: true, message: '' }
}

function syncTextSectionDraft(component) {
  const draft = createDefaultTextSectionConfig(component.properties.config)
  component.setData({ draft, count: countTextCodePoints(draft.content), errorMessage: '' })
}

Component({
  properties: {
    config: { type: Object, value: {}, observer() { if (!this.properties.editMode) syncTextSectionDraft(this) } },
    editMode: { type: Boolean, value: false, observer(value, oldValue) { if (value !== oldValue) syncTextSectionDraft(this) } }
  },
  data: { draft: createDefaultTextSectionConfig(), count: 0, alignments: TEXT_ALIGNMENTS, errorMessage: '' },
  methods: {
    beginEdit() { syncTextSectionDraft(this) },
    handleInput(event) { const field = event.currentTarget.dataset.field; const draft = Object.assign({}, this.data.draft, { [field]: event.detail.value }); this.setData({ draft, count: countTextCodePoints(draft.content) }); this.triggerEvent('change', { field, value: event.detail.value }) },
    selectAlignment(event) { const draft = Object.assign({}, this.data.draft, { alignment: event.currentTarget.dataset.alignment }); this.setData({ draft }); this.triggerEvent('change', { field: 'alignment', value: draft.alignment }) },
    cancelEdit() { syncTextSectionDraft(this); this.triggerEvent('cancel') },
    saveEdit() { const validation = validateTextSectionConfig(this.data.draft); if (!validation.valid) return this.setData({ errorMessage: validation.message }); this.triggerEvent('save', { config: createDefaultTextSectionConfig(this.data.draft) }) }
  },
  lifetimes: {
    attached() { syncTextSectionDraft(this) }
  }
})

module.exports = { TEXT_ALIGNMENTS, TEXT_SECTION_MAX_LENGTH, countTextCodePoints, createDefaultTextSectionConfig, validateTextSectionConfig }
