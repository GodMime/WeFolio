const { normalizeId } = require('./id')

const POINT_TRANSACTION_TYPE = {
  CONSUMPTION: 'CONSUMPTION'
}

const POINT_CALC_MODE = {
  ACCUMULATED_THRESHOLD: 'ACCUMULATED_THRESHOLD',
  MONTHLY_STORAGE_SIZE: 'MONTHLY_STORAGE_SIZE'
}

const RULE_GROUP_ORDER = {
  MAINTENANCE: 1,
  VISITOR: 2,
  OTHER: 99
}

function toNumber(value) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : 0
}

function toPositiveNumber(value, fallback) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) && numberValue > 0 ? numberValue : fallback
}

function toDisplayText(value) {
  return String(toNumber(value))
}

function formatSignedPoints(value) {
  const points = toNumber(value)
  return points > 0 ? `+${points}` : String(points)
}

function normalizeGroupCode(groupCode) {
  const code = String(groupCode || '').trim()
  return code || 'OTHER'
}

function defaultGroupText(groupCode) {
  if (groupCode === 'MAINTENANCE') {
    return '维护'
  }
  if (groupCode === 'VISITOR') {
    return '访客'
  }
  return '其他'
}

function buildRuleDesc(rule) {
  const unitCount = toPositiveNumber(rule.unitCount, 1)
  const pointsValue = toNumber(rule.pointsValue)
  if (rule.calcMode === POINT_CALC_MODE.MONTHLY_STORAGE_SIZE) {
    return '每月月初扣除'
  }
  if (pointsValue === 0) {
    return '当前规则免费'
  }
  if (rule.calcMode === POINT_CALC_MODE.ACCUMULATED_THRESHOLD && unitCount > 1) {
    return `累计 ${unitCount} 次计费一次`
  }
  if (unitCount > 1) {
    return `每 ${unitCount} 次计费一次`
  }
  return '每次成功操作计费一次'
}

function buildCostText(rule) {
  const unitCount = toPositiveNumber(rule.unitCount, 1)
  const pointsValue = toNumber(rule.pointsValue)
  if (rule.calcMode === POINT_CALC_MODE.MONTHLY_STORAGE_SIZE) {
    return `每${unitCount}MB扣${pointsValue}积分`
  }
  if (unitCount > 1) {
    return `每 ${unitCount} 次 ${pointsValue} 分`
  }
  return `${pointsValue} 分`
}

function normalizeRule(rule = {}) {
  const groupCode = normalizeGroupCode(rule.groupCode)
  return {
    id: normalizeId(rule.ruleId || rule.id) || rule.ruleCode || rule.sceneCode || rule.ruleName,
    ruleId: normalizeId(rule.ruleId || rule.id),
    ruleCode: rule.ruleCode || '',
    ruleName: rule.ruleName || rule.sceneText || '积分规则',
    sceneCode: rule.sceneCode || '',
    sceneText: rule.sceneText || rule.ruleName || '积分规则',
    groupCode,
    groupText: rule.groupText || defaultGroupText(groupCode),
    groupTone: groupCode.toLowerCase(),
    calcMode: rule.calcMode || '',
    transactionType: rule.transactionType || '',
    unitCount: toPositiveNumber(rule.unitCount, 1),
    pointsValue: toNumber(rule.pointsValue),
    costText: buildCostText(rule),
    desc: buildRuleDesc(rule)
  }
}

function normalizeRuleGroups(rules) {
  if (!Array.isArray(rules)) {
    return []
  }
  const groups = {}
  rules
    .map(normalizeRule)
    .filter((rule) => rule.transactionType === POINT_TRANSACTION_TYPE.CONSUMPTION)
    .forEach((rule) => {
      if (!groups[rule.groupCode]) {
        groups[rule.groupCode] = {
          groupCode: rule.groupCode,
          groupText: rule.groupText,
          groupTone: rule.groupTone,
          rules: []
        }
      }
      groups[rule.groupCode].rules.push(rule)
    })

  return Object.keys(groups)
    .sort((left, right) => (
      (RULE_GROUP_ORDER[left] || RULE_GROUP_ORDER.OTHER) - (RULE_GROUP_ORDER[right] || RULE_GROUP_ORDER.OTHER)
    ))
    .map((groupCode) => groups[groupCode])
}

function normalizePointOverview(raw = {}) {
  return {
    accountId: raw.accountId || null,
    userId: raw.userId || null,
    balance: toNumber(raw.balance),
    balanceText: toDisplayText(raw.balance),
    totalRecharged: toNumber(raw.totalRecharged),
    totalConsumed: toNumber(raw.totalConsumed),
    totalGifted: toNumber(raw.totalGifted),
    totalRechargedText: `累计充值 ${toDisplayText(raw.totalRecharged)} 积分`,
    totalConsumedText: `累计消耗 ${toDisplayText(raw.totalConsumed)} 积分`,
    lowBalance: Boolean(raw.lowBalance),
    lowBalanceThreshold: toNumber(raw.lowBalanceThreshold),
    metrics: [
      { label: '今日消耗', value: toDisplayText(raw.todayConsumed) },
      { label: '访客消耗', value: toDisplayText(raw.visitorConsumed) },
      { label: '维护消耗', value: toDisplayText(raw.maintenanceConsumed) }
    ],
    ruleGroups: normalizeRuleGroups(raw.rules)
  }
}

function normalizeTransaction(record = {}) {
  const pointsChange = toNumber(record.pointsChange)
  const occurredAt = record.occurredAt || record.createdAtText || ''
  const remark = record.remark || ''
  const descParts = [occurredAt, remark].filter(Boolean)
  return {
    id: normalizeId(record.transactionId || record.id) || record.businessId || record.sceneCode || record.sceneText,
    transactionId: normalizeId(record.transactionId || record.id),
    title: record.sceneText || record.transactionTypeText || '积分变动',
    desc: descParts.length ? descParts.join(' · ') : (record.transactionTypeText || '积分流水'),
    transactionType: record.transactionType || '',
    transactionTypeText: record.transactionTypeText || '',
    sceneCode: record.sceneCode || '',
    sceneText: record.sceneText || '',
    pointsChange,
    pointsText: record.pointsText || formatSignedPoints(pointsChange),
    pointsTone: pointsChange > 0 ? 'plus' : (pointsChange < 0 ? 'minus' : 'neutral'),
    balanceBefore: toNumber(record.balanceBefore),
    balanceAfter: toNumber(record.balanceAfter),
    occurredAt
  }
}

function normalizePointTransactions(raw = {}) {
  const page = toPositiveNumber(raw.page, 1)
  const pageSize = toPositiveNumber(raw.pageSize, 20)
  return {
    page,
    pageSize,
    total: toNumber(raw.total),
    hasMore: Boolean(raw.hasMore),
    nextPage: page + 1,
    records: Array.isArray(raw.records) ? raw.records.map(normalizeTransaction) : []
  }
}

function appendPointTransactions(current = {}, next = {}) {
  const currentData = normalizePointTransactions(current)
  const nextData = normalizePointTransactions(next)
  const seen = {}
  const records = []
  currentData.records.concat(nextData.records).forEach((item) => {
    if (item.id) {
      if (seen[item.id]) {
        return
      }
      seen[item.id] = true
    }
    records.push(item)
  })
  return Object.assign({}, nextData, { records })
}

function buildPointTransactionQuery(options = {}) {
  const query = {
    page: toPositiveNumber(options.page, 1),
    pageSize: toPositiveNumber(options.pageSize, 20)
  }
  if (options.transactionType) {
    query.transactionType = options.transactionType
  }
  if (options.sceneCode) {
    query.sceneCode = options.sceneCode
  }
  return query
}

module.exports = {
  appendPointTransactions,
  buildPointTransactionQuery,
  normalizePointOverview,
  normalizePointTransactions
}
