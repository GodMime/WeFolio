const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const MODULE_PATH = path.resolve(__dirname, '../pages/team-portfolios/utils/team-contact-leads.js')

function load() {
  delete require.cache[require.resolve(MODULE_PATH)]
  return require(MODULE_PATH)
}

test('validates contact name and requires phone or wechat', () => {
  const { validateTeamContactLeadForm } = load()
  assert.deepEqual(validateTeamContactLeadForm({ contactName: '', phone: '', wechat: '' }), { valid: false, message: '请填写称呼' })
  assert.deepEqual(validateTeamContactLeadForm({ contactName: '客户', phone: '', wechat: '' }), { valid: false, message: '请至少填写手机号或微信号' })
  assert.deepEqual(validateTeamContactLeadForm({ contactName: '客户', phone: '13800138000', wechat: '' }), { valid: true, message: '' })
})

test('builds a team lead payload with visit, source, consent and idempotency fields', () => {
  const { buildTeamContactLeadPayload } = load()
  assert.deepEqual(buildTeamContactLeadPayload({
    contactName: ' 客户 ', phone: ' 13800138000 ', wechat: ' wechat-id ', desiredSchedule: ' 2026-08-01 ', needs: ' 婚礼主持 '
  }, { visitRecordId: 8, sourceType: 'WECHAT_SHARE_CARD', consentVersion: '', idempotencyKey: 'lead-1' }), {
    visitRecordId: 8,
    contactName: '客户',
    phone: '13800138000',
    wechat: 'wechat-id',
    desiredSchedule: '2026-08-01',
    needs: '婚礼主持',
    sourceType: 'WECHAT_SHARE_CARD',
    consentVersion: 'v1',
    idempotencyKey: 'lead-1'
  })
})

test('submits through the team visitor endpoint', async () => {
  const { submitTeamContactLead } = load()
  const calls = []
  const response = await submitTeamContactLead((options) => { calls.push(options); return Promise.resolve({ leadId: 11 }) }, 'SHARE', {
    contactName: '客户', phone: '13800138000'
  }, { visitRecordId: 8, sourceType: 'QR_CODE', consentVersion: 'v1', idempotencyKey: 'lead-2' })
  assert.deepEqual(response, { leadId: 11 })
  assert.equal(calls[0].url, '/api/visitor/team-portfolios/SHARE/contact-leads')
  assert.equal(calls[0].authMode, 'visitor')
})

test('contact lead submit rejects blank or overlong idempotency keys before requesting', async () => {
  const { submitTeamContactLead } = load()
  let requestCount = 0
  const requestFn = async () => { requestCount += 1 }
  const form = { contactName: '客户', phone: '13800138000' }
  await assert.rejects(() => submitTeamContactLead(requestFn, 'SHARE', form, { visitRecordId: 8, idempotencyKey: ' ' }), /幂等键/)
  await assert.rejects(() => submitTeamContactLead(requestFn, 'SHARE', form, { visitRecordId: 8, idempotencyKey: 'x'.repeat(65) }), /幂等键/)
  assert.equal(requestCount, 0)
})

test('normalizes plaintext team leads without masking phone or wechat', () => {
  const { normalizeTeamContactLeadPage } = load()
  const page = normalizeTeamContactLeadPage({ page: 1, pageSize: 20, hasMore: false, items: [{
    leadId: 1, contactName: '客户甲', phone: '13800138000', wechat: 'wx-complete', desiredSchedule: '八月一日',
    needs: '婚礼主持', followNote: '下午联系', portfolioTitle: '团队作品集'
  }] })
  assert.equal(page.items[0].phone, '13800138000')
  assert.equal(page.items[0].wechat, 'wx-complete')
  assert.equal(page.items[0].followNote, '下午联系')
  assert.doesNotMatch(JSON.stringify(page), /\*/)
})

test('loads plaintext leads for OWNER, MANAGER and MEMBER through one team endpoint', async () => {
  const { fetchTeamContactLeads } = load()
  for (const role of ['OWNER', 'MANAGER', 'MEMBER']) {
    const calls = []
    const page = await fetchTeamContactLeads((options) => { calls.push(options); return Promise.resolve({ items: [{ contactName: role, phone: '10086' }] }) }, 7, { page: 2, pageSize: 10 })
    assert.equal(page.items[0].phone, '10086')
    assert.deepEqual(calls, [{ url: '/api/mine/teams/7/contact-leads', data: { page: 2, pageSize: 10 } }])
  }
})

test('updates follow status with encoded request params and no JSON body', async () => {
  const { updateTeamContactLeadFollowStatus } = load()
  const calls = []
  await updateTeamContactLeadFollowStatus((options) => { calls.push(options); return Promise.resolve({ leadId: 3 }) }, 7, 3, 'IN PROGRESS', '电话+微信跟进')
  assert.deepEqual(calls, [{
    url: '/api/mine/teams/7/contact-leads/3/follow-status?followStatus=IN%20PROGRESS&followNote=%E7%94%B5%E8%AF%9D%2B%E5%BE%AE%E4%BF%A1%E8%B7%9F%E8%BF%9B',
    method: 'POST'
  }])
})

test('lead utility never logs, masks or persists plaintext lead content', () => {
  const source = fs.readFileSync(MODULE_PATH, 'utf8')
  assert.doesNotMatch(source, /console\./)
  assert.doesNotMatch(source, /setStorage|mask|desensiti|contact-lead\.js/)
})
