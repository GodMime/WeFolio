const assert = require('node:assert/strict')
const test = require('node:test')

const {
  buildContactLeadPayload,
  createContactLeadForm,
  validateContactLeadForm
} = require('../pages/portfolios/utils/contact-lead')

test('creates and validates contact lead form', () => {
  const form = createContactLeadForm({
    contactName: ' 林安 ',
    phone: ' 13800138000 ',
    wechat: '',
    desiredSchedule: ' 2026-07-18 午宴 ',
    needs: ' 想了解报价 '
  })

  assert.equal(form.contactName, '林安')
  assert.equal(validateContactLeadForm(form).valid, true)
})

test('rejects contact lead without name or contact method', () => {
  assert.equal(validateContactLeadForm({ contactName: '', phone: '13800138000' }).message, '请填写联系人')
  assert.equal(validateContactLeadForm({ contactName: '林安', phone: '', wechat: '' }).message, '请至少填写手机号或微信号')
})

test('builds contact lead payload', () => {
  const form = createContactLeadForm({
    contactName: '林安',
    phone: '13800138000',
    wechat: 'wefolio',
    desiredSchedule: '2026-07-18',
    needs: '想了解报价'
  })

  assert.deepEqual(buildContactLeadPayload(form, {
    visitorKey: 'visitor-a',
    visitRecordId: 33,
    sourceType: 'WECHAT_SHARE_CARD',
    idempotencyKey: 'lead-1'
  }), {
    visitorKey: 'visitor-a',
    visitRecordId: 33,
    contactName: '林安',
    phone: '13800138000',
    wechat: 'wefolio',
    desiredSchedule: '2026-07-18',
    needs: '想了解报价',
    sourceType: 'WECHAT_SHARE_CARD',
    consentVersion: 'v1',
    idempotencyKey: 'lead-1'
  })
})
