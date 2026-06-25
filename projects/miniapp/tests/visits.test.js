const assert = require('node:assert/strict')
const test = require('node:test')

const { normalizeVisitRecords } = require('../utils/visits')

test('normalizes visit records response for page rendering', () => {
  const result = normalizeVisitRecords({
    summary: {
      totalVisitCount: 428,
      todayVisitCount: 36,
      scheduleQueryCount: 19
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
        sourceText: '来自分享卡片「林安婚礼司仪」',
        summaryText: '第 4 次访问 · 查看作品 6 次 · 查询 10-03 档期 · 点击二维码 1 次',
        followStatusText: '未跟进',
        followTone: 'rose'
      }
    ]
  })

  assert.deepEqual(result.metrics, [
    { label: '累计访问次数', value: '428' },
    { label: '今日访问', value: '36' },
    { label: '查询档期', value: '19' }
  ])
  assert.equal(result.trend.changeText, '上升 24%')
  assert.deepEqual(result.trend.points.map((item) => item.height), [67, 100, 83])
  assert.equal(result.records[0].visitorInitial, '8')
  assert.equal(result.records[0].followToneClass, 'follow-pill rose')
})

test('normalizes empty visit records response with safe defaults', () => {
  const result = normalizeVisitRecords({})

  assert.deepEqual(result.metrics.map((item) => item.value), ['0', '0', '0'])
  assert.equal(result.trend.changeText, '暂无趋势')
  assert.equal(result.trend.points.length, 7)
  assert.deepEqual(result.records, [])
})
