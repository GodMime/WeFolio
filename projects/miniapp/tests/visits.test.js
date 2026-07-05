const assert = require('node:assert/strict')
const test = require('node:test')

const {
  appendVisitDetailPage,
  appendVisitEventTimeline,
  markContactLeadFollowed,
  markVisitRecordFollowed,
  normalizeVisitDetailPage,
  normalizeVisitEventTimeline,
  normalizeVisitRecords
} = require('../utils/visits')

test('normalizes visit records response for page rendering', () => {
  const result = normalizeVisitRecords({
    summary: {
      totalVisitCount: 428,
      todayVisitCount: 36,
      scheduleQueryCount: 19,
      contactLeadCount: 7
    },
    trend: {
      changeText: '上升 24%',
      points: [
        { label: '周一', value: 12 },
        { label: '周二', value: 18 },
        { label: '周三', value: 15 }
      ]
    },
    records: [
      {
        visitorLabel: '微信访客 8A21',
        visitorAvatarUrl: 'https://cdn.example.com/visit/visitor-avatar-1024-20260705093000-a1b2c3d4.jpg',
        sourceText: '来自分享卡片「林安婚礼司仪」',
        summaryText: '第 4 次访问 · 查看作品 6 次 · 查询 10-03 档期 · 点击二维码 1 次',
        followStatusText: '未跟进',
        followTone: 'rose',
        events: [
          {
            id: 9001,
            title: '打开作品集',
            detailText: '来自分享卡片',
            occurredTimeText: '11:01',
            tone: 'teal'
          }
        ]
      }
    ]
  })

  assert.deepEqual(result.metrics, [
    { label: '累计访问次数', value: '428', action: '', interactive: false, className: 'metric' },
    { label: '今日访问', value: '36', action: '', interactive: false, className: 'metric' },
    { label: '查询档期', value: '19', action: 'scheduleQueries', interactive: true, className: 'metric interactive' },
    { label: '预留信息', value: '7', action: 'contactLeads', interactive: true, className: 'metric interactive' }
  ])
  assert.equal(result.trend.changeText, '上升 24%')
  assert.deepEqual(result.trend.points.map((item) => item.height), [67, 100, 83])
  assert.equal(result.records[0].visitorInitial, '8')
  assert.equal(
    result.records[0].visitorAvatarUrl,
    'https://cdn.example.com/visit/visitor-avatar-1024-20260705093000-a1b2c3d4.jpg'
  )
  assert.equal(result.records[0].followStatus, 'NOT_FOLLOWED_UP')
  assert.equal(result.records[0].followToneClass, 'follow-pill rose')
  assert.equal(result.records[0].canMarkFollowed, true)
  assert.equal(result.records[0].eventCountText, '1 个事件')
  assert.equal(result.records[0].events[0].toneClass, 'visit-event-dot teal')
})

test('marks one visit record followed without changing summary and trend data', () => {
  const current = normalizeVisitRecords({
    summary: {
      totalVisitCount: 8,
      todayVisitCount: 2,
      scheduleQueryCount: 1
    },
    trend: {
      changeText: '持平',
      points: [
        { label: '周日', value: 2 }
      ]
    },
    records: [
      {
        id: 101,
        visitorLabel: '微信访客 8A21',
        followStatus: 'NOT_FOLLOWED_UP',
        followStatusText: '未跟进',
        followTone: 'rose'
      },
      {
        id: 102,
        visitorLabel: '微信访客 C19F',
        followStatus: 'CONTACTED',
        followStatusText: '已跟进',
        followTone: 'teal'
      }
    ]
  })

  const result = markVisitRecordFollowed(current, 101, {
    id: 101,
    followStatus: 'CONTACTED',
    followStatusText: '已跟进',
    followTone: 'teal'
  })

  assert.equal(result.metrics[0].value, '8')
  assert.equal(result.trend.changeText, '持平')
  assert.equal(result.records[0].followStatus, 'CONTACTED')
  assert.equal(result.records[0].followStatusText, '已跟进')
  assert.equal(result.records[0].followToneClass, 'follow-pill teal')
  assert.equal(result.records[0].canMarkFollowed, false)
  assert.equal(result.records[1].followStatus, 'CONTACTED')
})

test('normalizes visit event timeline response for bottom sheet rendering', () => {
  const result = normalizeVisitRecords({
    records: [
      {
        id: 101,
        visitorLabel: '微信访客 8A21',
        events: [
          {
            eventId: 9001,
            title: '查询档期',
            detailText: '查询 2026-10-03 档期',
            occurredDateText: '2026-07-05',
            occurredTimeText: '11:00',
            tone: 'blue'
          },
          {
            eventId: 9002,
            title: '点击二维码',
            detailText: '',
            occurredDateText: '2026-07-05',
            occurredTimeText: '11:01'
          }
        ]
      }
    ]
  })

  assert.equal(result.records[0].events.length, 2)
  assert.equal(result.records[0].events[0].id, 9002)
  assert.equal(result.records[0].events[1].id, 9001)
  assert.equal(result.records[0].events[1].toneClass, 'visit-event-dot blue')
  assert.equal(result.records[0].events[0].detailText, '暂无补充信息')
  assert.equal(result.records[0].eventCountText, '2 个事件')
})

test('normalizes visit event timeline with newest events first and pagination state', () => {
  const result = normalizeVisitEventTimeline({
    recordId: 101,
    pageNo: 1,
    pageSize: 2,
    hasMore: true,
    events: [
      {
        eventId: 9001,
        title: '打开作品集',
        occurredDateText: '2026-07-05',
        occurredTimeText: '11:00'
      },
      {
        eventId: 9002,
        title: '查询档期',
        occurredDateText: '2026-07-05',
        occurredTimeText: '11:05'
      }
    ]
  })

  assert.deepEqual(result.events.map((item) => item.id), [9002, 9001])
  assert.equal(result.pageNo, 1)
  assert.equal(result.pageSize, 2)
  assert.equal(result.hasMore, true)
  assert.equal(result.nextPage, 2)
})

test('appends visit event timeline pages and refreshes loaded count', () => {
  const firstPage = normalizeVisitEventTimeline({
    recordId: 101,
    pageNo: 1,
    pageSize: 1,
    hasMore: true,
    events: [
      {
        eventId: 9002,
        title: '查询档期',
        occurredDateText: '2026-07-05',
        occurredTimeText: '11:05'
      }
    ]
  })
  const secondPage = normalizeVisitEventTimeline({
    recordId: 101,
    pageNo: 2,
    pageSize: 1,
    hasMore: false,
    events: [
      {
        eventId: 9001,
        title: '打开作品集',
        occurredDateText: '2026-07-05',
        occurredTimeText: '11:00'
      }
    ]
  }, firstPage)

  const result = appendVisitEventTimeline(firstPage, secondPage)

  assert.deepEqual(result.events.map((item) => item.id), [9002, 9001])
  assert.equal(result.pageNo, 2)
  assert.equal(result.pageSize, 1)
  assert.equal(result.hasMore, false)
  assert.equal(result.nextPage, null)
  assert.equal(result.eventCountText, '2 个事件')
})

test('normalizes schedule query detail page for bottom sheet rendering', () => {
  const result = normalizeVisitDetailPage('scheduleQueries', {
    pageNo: 1,
    pageSize: 20,
    hasMore: true,
    items: [
      {
        id: 301,
        visitorLabel: '小陈',
        visitorAvatarUrl: 'https://cdn.example.com/avatar.jpg',
        visitorInitial: 'C',
        portfolioTitle: '林安婚礼司仪',
        queriedDateText: '2026-07-18',
        slotText: '午宴 10:00-14:00',
        resultStatusText: '已约',
        available: false,
        resultMessage: '该档期已约',
        sourceText: '来自分享卡片',
        createdTimeText: '07-05 14:18'
      }
    ]
  })

  assert.equal(result.type, 'scheduleQueries')
  assert.equal(result.pageNo, 1)
  assert.equal(result.pageSize, 20)
  assert.equal(result.hasMore, true)
  assert.equal(result.nextPage, 2)
  assert.equal(result.items[0].visitorLabel, '小陈')
  assert.equal(result.items[0].slotText, '午宴 10:00-14:00')
  assert.equal(result.items[0].resultToneClass, 'detail-status rose')
})

test('normalizes and appends contact lead detail pages without ciphertext fields', () => {
  const firstPage = normalizeVisitDetailPage('contactLeads', {
    pageNo: 1,
    pageSize: 1,
    hasMore: true,
    items: [
      {
        id: 401,
        contactName: '王小姐',
        phone: '13800108899',
        phoneLast4: '8899',
        wechat: 'wx-full-99',
        wechatMaskHint: 'wx***99',
        desiredSchedule: '2026-10-03 午宴',
        needs: '想了解主持和摄影套餐',
        portfolioTitle: '林安婚礼司仪',
        sourceText: '来自分享卡片',
        followStatusText: '未跟进',
        submittedTimeText: '07-05 13:30'
      }
    ]
  })
  const secondPage = normalizeVisitDetailPage('contactLeads', {
    pageNo: 2,
    pageSize: 1,
    hasMore: false,
    items: [
      {
        id: 402,
        contactName: '李先生',
        phoneLast4: '',
        wechatMaskHint: '',
        desiredSchedule: '',
        needs: '',
        portfolioTitle: '',
        sourceText: '',
        followStatusText: '已跟进',
        submittedTimeText: '07-05 12:00',
        phoneCiphertext: 'secret-phone',
        wechatCiphertext: 'secret-wechat'
      }
    ]
  }, firstPage)

  const result = appendVisitDetailPage(firstPage, secondPage)

  assert.equal(result.type, 'contactLeads')
  assert.equal(result.items.length, 2)
  assert.equal(result.items[0].phoneText, '13800108899')
  assert.equal(result.items[0].phoneCanCopy, true)
  assert.equal(result.items[0].phoneCopyText, '13800108899')
  assert.equal(result.items[0].wechatText, 'wx-full-99')
  assert.equal(result.items[0].wechatCanCopy, true)
  assert.equal(result.items[0].wechatCopyText, 'wx-full-99')
  assert.equal(result.items[1].phoneText, '未留手机')
  assert.equal(result.items[1].wechatText, '未留微信')
  assert.equal(Object.prototype.hasOwnProperty.call(result.items[1], 'phoneCiphertext'), false)
  assert.equal(Object.prototype.hasOwnProperty.call(result.items[1], 'wechatCiphertext'), false)
  assert.equal(result.hasMore, false)
  assert.equal(result.nextPage, null)
})

test('marks one contact lead followed inside current detail page', () => {
  const detailPage = normalizeVisitDetailPage('contactLeads', {
    pageNo: 1,
    pageSize: 20,
    hasMore: false,
    items: [
      {
        id: 401,
        contactName: '王小姐',
        phoneLast4: '8899',
        wechatMaskHint: 'wx***99',
        sourceText: '来自分享卡片',
        followStatus: 'NOT_FOLLOWED_UP',
        followStatusText: '未跟进',
        submittedTimeText: '07-05 13:30'
      },
      {
        id: 402,
        contactName: '李先生',
        followStatus: 'CONTACTED',
        followStatusText: '已跟进'
      }
    ]
  })

  const result = markContactLeadFollowed(detailPage, 401, {
    id: 401,
    followStatus: 'CONTACTED',
    followStatusText: '已跟进'
  })

  assert.equal(result.items[0].followStatus, 'CONTACTED')
  assert.equal(result.items[0].followStatusText, '已跟进')
  assert.equal(result.items[0].followToneClass, 'follow-pill teal')
  assert.equal(result.items[0].canMarkFollowed, false)
  assert.equal(result.items[1].followStatus, 'CONTACTED')
})

test('normalizes empty visit records response with safe defaults', () => {
  const result = normalizeVisitRecords({})

  assert.deepEqual(result.metrics.map((item) => item.value), ['0', '0', '0', '0'])
  assert.equal(result.trend.changeText, '暂无趋势')
  assert.equal(result.trend.points.length, 7)
  assert.deepEqual(result.records, [])
})
