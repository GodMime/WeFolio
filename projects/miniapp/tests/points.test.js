const assert = require('node:assert/strict')
const test = require('node:test')

const {
  appendPointTransactions,
  buildPointTransactionQuery,
  normalizePointOverview,
  normalizePointTransactions
} = require('../utils/points')

test('normalizes point overview with grouped consumption rules', () => {
  const overview = normalizePointOverview({
    balance: 286,
    totalRecharged: 820,
    totalConsumed: 534,
    todayConsumed: 14,
    visitorConsumed: 42,
    maintenanceConsumed: 8,
    lowBalance: false,
    rules: [
      {
        ruleId: 1,
        ruleName: '上传图片作品',
        sceneCode: 'UPLOAD_IMAGE',
        sceneText: '上传图片作品',
        groupCode: 'MAINTENANCE',
        groupText: '维护',
        calcMode: 'FIXED_PER_ACTION',
        transactionType: 'CONSUMPTION',
        unitCount: 1,
        pointsValue: 1
      },
      {
        ruleId: 2,
        ruleName: '查看作品集图片',
        sceneCode: 'VIEW_PORTFOLIO_IMAGES',
        sceneText: '查看作品集图片',
        groupCode: 'VISITOR',
        groupText: '访客',
        calcMode: 'ACCUMULATED_THRESHOLD',
        transactionType: 'CONSUMPTION',
        unitCount: 10,
        pointsValue: 1
      },
      {
        ruleId: 3,
        ruleName: '后台人工加分',
        sceneCode: 'MANUAL_ADMIN_GRANT',
        sceneText: '后台人工加分',
        groupCode: 'OTHER',
        groupText: '其他',
        calcMode: 'MANUAL_ADJUSTMENT',
        transactionType: 'GIFT',
        unitCount: 1,
        pointsValue: 100
      }
    ]
  })

  assert.equal(overview.balanceText, '286')
  assert.equal(overview.totalRechargedText, '累计充值 820 积分')
  assert.equal(overview.totalConsumedText, '累计消耗 534 积分')
  assert.deepEqual(overview.metrics, [
    { label: '今日消耗', value: '14' },
    { label: '访客消耗', value: '42' },
    { label: '维护消耗', value: '8' }
  ])
  assert.deepEqual(overview.ruleGroups.map((group) => group.groupCode), ['MAINTENANCE', 'VISITOR'])
  assert.equal(overview.ruleGroups[0].rules[0].costText, '1 分')
  assert.equal(overview.ruleGroups[1].rules[0].costText, '每 10 次 1 分')
  assert.equal(overview.ruleGroups[1].rules[0].desc, '累计 10 次计费一次')
})

test('normalizes point transactions and appends next page without duplicates', () => {
  const first = normalizePointTransactions({
    page: 1,
    pageSize: 2,
    total: 3,
    hasMore: true,
    records: [
      {
        transactionId: 12,
        sceneText: '访问个人作品集',
        transactionTypeText: '消耗',
        pointsChange: -1,
        pointsText: '-1',
        remark: '林安婚礼司仪',
        occurredAt: '2026-06-26 14:28:00'
      },
      {
        transactionId: 11,
        sceneText: '充值到账',
        transactionTypeText: '充值',
        pointsChange: 520,
        pointsText: '+520',
        remark: '50 元档',
        occurredAt: '2026-06-25 20:12:00'
      }
    ]
  })
  const second = normalizePointTransactions({
    page: 2,
    pageSize: 2,
    total: 3,
    hasMore: false,
    records: [
      { transactionId: 11, sceneText: '重复充值', pointsChange: 520 },
      { transactionId: 10, sceneText: '维护高级作品集', pointsChange: -10 }
    ]
  })

  const result = appendPointTransactions(first, second)

  assert.deepEqual(first.records.map((item) => item.pointsTone), ['minus', 'plus'])
  assert.equal(first.records[0].desc, '2026-06-26 14:28:00 · 林安婚礼司仪')
  assert.deepEqual(result.records.map((item) => item.id), [12, 11, 10])
  assert.equal(result.hasMore, false)
  assert.equal(result.nextPage, 3)
})

test('builds point transaction query with safe page defaults', () => {
  assert.deepEqual(buildPointTransactionQuery({ page: 2, pageSize: 10 }), {
    page: 2,
    pageSize: 10
  })
  assert.deepEqual(buildPointTransactionQuery({ transactionType: 'CONSUMPTION', sceneCode: 'CREATE_TEAM' }), {
    transactionType: 'CONSUMPTION',
    sceneCode: 'CREATE_TEAM',
    page: 1,
    pageSize: 20
  })
})

test('formats monthly work storage rule with MB billing copy', () => {
  const overview = normalizePointOverview({
    rules: [{
      ruleId: 32,
      ruleName: '作品存储月费',
      sceneCode: 'MONTHLY_WORK_STORAGE',
      sceneText: '作品存储月费',
      groupCode: 'MAINTENANCE',
      groupText: '维护',
      calcMode: 'MONTHLY_STORAGE_SIZE',
      transactionType: 'CONSUMPTION',
      unitCount: 2,
      pointsValue: 1
    }]
  })

  const rule = overview.ruleGroups[0].rules[0]
  assert.equal(`${rule.sceneText} ${rule.costText}`, '作品存储月费 每2MB扣1积分')
  assert.equal(rule.desc, '每月月初扣除')
})

test('formats revised maintenance and rolling visitor billing rules', () => {
  const overview = normalizePointOverview({
    rules: [
      ['CREATE_TEAM', '新建团队', 'MAINTENANCE', 2000],
      ['MAINTAIN_ADVANCED_PORTFOLIO', '发布高级作品集', 'MAINTENANCE', 20],
      ['MAINTAIN_STANDARD_PORTFOLIO', '发布标准作品集', 'MAINTENANCE', 10],
      ['UPLOAD_IMAGE', '上传图片作品', 'MAINTENANCE', 5],
      ['UPLOAD_VIDEO', '上传视频作品', 'MAINTENANCE', 10],
      ['VISIT_PERSONAL_PORTFOLIO', '访问个人作品集', 'VISITOR', 10, 'PORTFOLIO'],
      ['VIEW_PORTFOLIO_IMAGES', '查看作品集图片', 'VISITOR', 1, 'WORK'],
      ['VIEW_PORTFOLIO_VIDEO', '查看作品集视频', 'VISITOR', 10, 'WORK']
    ].map(([sceneCode, ruleName, groupCode, pointsValue, dedupeScope], index) => ({
      ruleId: index + 1,
      ruleName,
      sceneCode,
      sceneText: ruleName,
      groupCode,
      groupText: groupCode === 'MAINTENANCE' ? '维护' : '访客',
      calcMode: 'FIXED_PER_ACTION',
      transactionType: 'CONSUMPTION',
      unitCount: 1,
      pointsValue,
      dedupeWindowHours: dedupeScope ? 2 : null,
      dedupeScope: dedupeScope || null
    }))
  })

  const maintenanceRules = overview.ruleGroups[0].rules
  const visitorRules = overview.ruleGroups[1].rules
  assert.deepEqual(maintenanceRules.map((rule) => rule.costText), ['2000 分', '20 分', '10 分', '5 分', '10 分'])
  assert.deepEqual(visitorRules.map((rule) => rule.costText), ['10 分', '1 分', '10 分'])
  assert.equal(visitorRules[0].desc, '同访客同作品集 2 小时内不重复扣')
  assert.equal(visitorRules[1].desc, '同访客同作品 2 小时内不重复扣')
  assert.equal(visitorRules[2].desc, '同访客同作品 2 小时内不重复扣')
  assert.ok(visitorRules.every((rule) => !rule.desc.includes('累计 10 次')))
})
