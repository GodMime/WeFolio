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

Component({
  properties: {
    config: { type: Object, value: {} }
  },
  data: {}
})

module.exports = { TEXT_ALIGNMENTS, TEXT_SECTION_MAX_LENGTH, countTextCodePoints, createDefaultTextSectionConfig, validateTextSectionConfig }
