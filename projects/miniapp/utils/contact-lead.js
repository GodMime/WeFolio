const DEFAULT_CONSENT_VERSION = 'v1'

function trimText(value) {
  return String(value || '').trim()
}

function createContactLeadForm(raw = {}) {
  return {
    contactName: trimText(raw.contactName),
    phone: trimText(raw.phone),
    wechat: trimText(raw.wechat),
    desiredSchedule: trimText(raw.desiredSchedule),
    needs: trimText(raw.needs)
  }
}

function validateContactLeadForm(form = {}) {
  if (!trimText(form.contactName)) {
    return { valid: false, message: '请填写联系人' }
  }
  if (!trimText(form.phone) && !trimText(form.wechat)) {
    return { valid: false, message: '请至少填写手机号或微信号' }
  }
  return { valid: true, message: '' }
}

function buildContactLeadPayload(form = {}, options = {}) {
  const normalized = createContactLeadForm(form)
  return {
    visitorKey: trimText(options.visitorKey),
    visitRecordId: options.visitRecordId || null,
    contactName: normalized.contactName,
    phone: normalized.phone,
    wechat: normalized.wechat,
    desiredSchedule: normalized.desiredSchedule,
    needs: normalized.needs,
    sourceType: trimText(options.sourceType) || 'UNKNOWN',
    consentVersion: trimText(options.consentVersion) || DEFAULT_CONSENT_VERSION,
    idempotencyKey: trimText(options.idempotencyKey)
  }
}

module.exports = {
  DEFAULT_CONSENT_VERSION,
  buildContactLeadPayload,
  createContactLeadForm,
  validateContactLeadForm
}
