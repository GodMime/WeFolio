const assert = require('node:assert/strict')
const test = require('node:test')

const {
  buildVisitorEventPayload,
  normalizeVisitorPortfolio,
  normalizeVisitorSchedule
} = require('../utils/visitor-portfolio')

test('normalizes visitor portfolio under maintenance state', () => {
  const result = normalizeVisitorPortfolio({
    shareCode: 'PF001',
    title: '林安婚礼司仪',
    underMaintenance: true,
    maintenanceText: {
      primary: 'UNDER MAINTENANCE',
      secondary: '维护中'
    }
  })

  assert.equal(result.underMaintenance, true)
  assert.equal(result.title, '林安婚礼司仪')
  assert.equal(result.maintenanceText.primary, 'UNDER MAINTENANCE')
  assert.deepEqual(result.components, [])
})

test('normalizes visitor portfolio components from published config', () => {
  const result = normalizeVisitorPortfolio({
    shareCode: 'PF001',
    publishedRevision: 3,
    config: {
      share: { title: ' 林安婚礼司仪 ' },
      components: [
        { componentKey: 'c_profile', componentType: 'PROFILE', sortOrder: 2000, enabled: true, config: {} },
        { componentKey: 'c_text', componentType: 'TEXT_SECTION', sortOrder: 1000, enabled: true, config: { title: '服务说明' } }
      ]
    }
  })

  assert.equal(result.title, '林安婚礼司仪')
  assert.deepEqual(result.components.map((item) => item.componentKey), ['c_text', 'c_profile'])
})

test('builds visitor event payload with idempotency key', () => {
  const payload = buildVisitorEventPayload({
    visitorKey: 'visitor-a',
    eventType: 'VIDEO_PLAYED',
    workId: 11,
    durationSeconds: 18
  }, 'event-1')

  assert.deepEqual(payload, {
    visitorKey: 'visitor-a',
    eventType: 'VIDEO_PLAYED',
    workId: 11,
    durationSeconds: 18,
    idempotencyKey: 'event-1'
  })
})

test('normalizes visitor schedule without internal fields', () => {
  const result = normalizeVisitorSchedule({
    schedules: [
      {
        date: '2026-07-18',
        slotName: '午宴',
        startTime: '10:00',
        endTime: '14:00',
        statusText: '待定',
        contactName: '内部客户',
        note: '内部备注'
      }
    ]
  })

  assert.equal(result.schedules[0].slotName, '午宴')
  assert.equal(result.schedules[0].contactName, '')
  assert.equal(result.schedules[0].note, '')
})
