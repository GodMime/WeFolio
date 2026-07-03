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

test('normalizes visitor portfolio from backend render data first', () => {
  const result = normalizeVisitorPortfolio({
    title: '旧标题',
    config: {
      share: { title: '旧配置标题' },
      components: [
        { componentKey: 'c_old', componentType: 'TEXT_SECTION', sortOrder: 1000, config: { title: '旧组件' } }
      ]
    },
    renderData: {
      shareCode: 'PF001',
      portfolioId: 88,
      title: '林安婚礼司仪',
      preview: true,
      share: {
        title: '林安婚礼司仪',
        intro: '温暖沉稳',
        coverUrl: 'https://cdn.example.com/share.jpg'
      },
      components: [
        {
          componentKey: 'c_list',
          componentType: 'WORK_LIST',
          name: '单列作品列表',
          sortOrder: 2000,
          title: '精选案例',
          groups: [
            {
              groupKey: 'g_featured',
              name: '精选',
              sortOrder: 1000,
              works: [
                {
                  workId: 11,
                  title: '迎宾布置',
                  mediaType: 'IMAGE',
                  coverUrl: 'https://cdn.example.com/cover.jpg',
                  mediaUrl: 'https://cdn.example.com/media.jpg'
                }
              ]
            }
          ]
        }
      ]
    }
  })

  assert.equal(result.preview, true)
  assert.equal(result.title, '林安婚礼司仪')
  assert.equal(result.share.coverUrl, 'https://cdn.example.com/share.jpg')
  assert.deepEqual(result.components.map((item) => item.componentKey), ['c_list'])
  assert.equal(result.components[0].layout, 'single')
  assert.equal(result.components[0].activeGroup.name, '精选')
  assert.equal(result.components[0].activeGroup.works[0].workId, 11)
})

test('normalizes work grid tags and qr contact preview url from render data', () => {
  const result = normalizeVisitorPortfolio({
    renderData: {
      title: '林安婚礼司仪',
      components: [
        {
          componentKey: 'c_grid',
          componentType: 'WORK_GRID',
          sortOrder: 1000,
          groups: [
            { groupKey: 'g_all', name: '全部案例', sortOrder: 1000, works: [] },
            { groupKey: 'g_outdoor', name: '户外案例', sortOrder: 2000, works: [] }
          ]
        },
        {
          componentKey: 'c_qr',
          componentType: 'QR_CONTACT',
          sortOrder: 2000,
          qrContact: {
            title: '微信联系',
            qrUrl: 'https://cdn.example.com/qr.jpg'
          }
        }
      ]
    }
  })

  assert.equal(result.components[0].layout, 'grid')
  assert.deepEqual(result.components[0].displayTags, [
    { groupKey: 'g_all', name: '全部案例', active: true },
    { groupKey: 'g_outdoor', name: '户外案例', active: false }
  ])
  assert.equal(result.components[1].qrContact.qrUrl, 'https://cdn.example.com/qr.jpg')
  assert.equal(result.components[1].previewImageUrl, 'https://cdn.example.com/qr.jpg')
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
