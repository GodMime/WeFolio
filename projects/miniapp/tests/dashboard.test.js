const assert = require('node:assert/strict')
const test = require('node:test')

const { normalizeDashboard } = require('../utils/dashboard')

test('normalizes mine dashboard response for page rendering', () => {
  const dashboard = normalizeDashboard({
    profile: {
      uniqueCode: 'MC-8392',
      displayName: '林安 · 婚礼司仪',
      avatarUrl: 'https://example.com/avatar.jpg',
      city: '上海',
      profession: '婚礼司仪',
      tags: [
        { content: '高端婚礼', color: '#0f766e' },
        { content: '双语主持', color: '#2d5f9a' }
      ]
    },
    point: {
      balance: 42,
      todayConsumed: 14,
      lowBalance: true
    },
    metrics: {
      workCount: 36,
      publishedPortfolioCount: 4,
      recentVisitCount: 128
    }
  })

  assert.equal(dashboard.profile.uniqueCode, 'MC-8392')
  assert.equal(dashboard.profile.subtitle, '上海 / 高端婚礼 / 双语主持')
  assert.deepEqual(dashboard.profile.tags, ['高端婚礼', '双语主持'])
  assert.equal(dashboard.point.balanceText, '42')
  assert.equal(dashboard.point.warningText, '低于 50 提醒')
  assert.deepEqual(dashboard.metrics, [
    { label: '作品素材', value: '36' },
    { label: '作品集', value: '4' },
    { label: '近 7 日访问', value: '128' }
  ])
})

test('normalizes empty mine dashboard response with safe defaults', () => {
  const dashboard = normalizeDashboard({})

  assert.equal(dashboard.profile.displayName, '微信用户')
  assert.equal(dashboard.profile.subtitle, '完善资料后展示服务区域和标签')
  assert.equal(dashboard.profile.uniqueCode, '-')
  assert.equal(dashboard.point.balanceText, '0')
  assert.equal(dashboard.point.warningText, '')
  assert.deepEqual(dashboard.metrics.map((item) => item.value), ['0', '0', '0'])
})
