const { normalizeId } = require('./id')

const DEFAULT_RECHARGE_AMOUNT_FEN = 5000
const DEFAULT_PAGE = 1
const DEFAULT_PAGE_SIZE = 20

function toNumber(value) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : 0
}

function toPositiveNumber(value, fallback) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) && numberValue > 0 ? numberValue : fallback
}

function formatAmountFen(amountFen) {
  const amount = toNumber(amountFen) / 100
  return amount.toFixed(2).replace(/\.00$/, '').replace(/(\.\d)0$/, '$1')
}

function normalizePackage(item = {}) {
  const packageId = normalizeId(item.packageId || item.id)
  const amountFen = toNumber(item.amountFen)
  const bonusPoints = toNumber(item.bonusPoints)
  return {
    packageId,
    packageCode: item.packageCode || '',
    packageName: item.packageName || '充值套餐',
    amountFen,
    amountText: `${formatAmountFen(amountFen)} 元`,
    basePoints: toNumber(item.basePoints),
    bonusPoints,
    bonusText: bonusPoints > 0 ? `多送 ${bonusPoints} 积分` : '基础套餐',
    totalPoints: toNumber(item.totalPoints),
    selected: false
  }
}

function selectRechargePackage(rechargeData = {}, packageId) {
  const normalizedId = normalizeId(packageId)
  const packages = Array.isArray(rechargeData.packages)
    ? rechargeData.packages.map((item) => Object.assign({}, item, {
      selected: normalizedId !== null && item.packageId === normalizedId
    }))
    : []
  return Object.assign({}, rechargeData, {
    packages,
    selectedPackageId: normalizedId,
    selectedPackage: packages.find((item) => item.selected) || null
  })
}

function normalizeRechargePage(raw = {}) {
  const packages = Array.isArray(raw.packages) ? raw.packages.map(normalizePackage) : []
  const defaultPackage = packages.find((item) => item.amountFen === DEFAULT_RECHARGE_AMOUNT_FEN) || packages[0] || null
  const lowBalanceThreshold = toNumber(raw.lowBalanceThreshold)
  return selectRechargePackage({
    balance: toNumber(raw.balance),
    balanceText: String(toNumber(raw.balance)),
    lowBalance: Boolean(raw.lowBalance),
    lowBalanceThreshold,
    warningText: raw.lowBalance ? `余额低于 ${lowBalanceThreshold} 积分，建议及时充值` : '',
    packages,
    selectedPackageId: null,
    selectedPackage: null
  }, defaultPackage && defaultPackage.packageId)
}

function applyRechargeBalance(rechargeData = {}, balance) {
  if (balance === null || balance === undefined || balance === '') {
    return rechargeData
  }
  const normalizedBalance = Number(balance)
  if (!Number.isFinite(normalizedBalance)) {
    return rechargeData
  }
  const threshold = toNumber(rechargeData.lowBalanceThreshold)
  const lowBalance = normalizedBalance < threshold
  return Object.assign({}, rechargeData, {
    balance: normalizedBalance,
    balanceText: String(normalizedBalance),
    lowBalance,
    warningText: lowBalance ? `余额低于 ${threshold} 积分，建议及时充值` : ''
  })
}

function buildRequestPaymentOptions(order = {}) {
  return {
    timeStamp: order.timeStamp || '',
    nonceStr: order.nonceStr || '',
    package: order.packageValue || '',
    signType: order.signType || 'RSA',
    paySign: order.paySign || ''
  }
}

function isPaymentCancelled(error) {
  return /(?:^|\s|:)cancel(?:\s|$)/i.test(String(error && error.errMsg ? error.errMsg : ''))
}

function statusTone(status) {
  if (status === 'PAID') {
    return 'success'
  }
  if (status === 'PENDING_PAYMENT') {
    return 'pending'
  }
  return 'muted'
}

function normalizeRechargeOrder(record = {}) {
  const bonusPoints = toNumber(record.bonusPoints)
  const totalPoints = toNumber(record.totalPoints)
  return {
    merchantOrderNo: record.merchantOrderNo || '',
    packageName: record.packageName || '充值套餐',
    amountFen: toNumber(record.amountFen),
    amountText: `${formatAmountFen(record.amountFen)} 元`,
    basePoints: toNumber(record.basePoints),
    bonusPoints,
    bonusText: bonusPoints > 0 ? `赠送 ${bonusPoints} 积分` : '无赠送积分',
    totalPoints,
    pointsText: `到账 ${totalPoints} 积分`,
    status: record.status || '',
    statusText: record.statusText || '状态确认中',
    statusTone: statusTone(record.status),
    createdAt: record.createdAt || '',
    paidAt: record.paidAt || '',
    timeText: record.paidAt || record.createdAt || '-'
  }
}

function normalizeRechargeOrders(raw = {}) {
  const page = toPositiveNumber(raw.page, DEFAULT_PAGE)
  return {
    page,
    pageSize: toPositiveNumber(raw.pageSize, DEFAULT_PAGE_SIZE),
    total: toNumber(raw.total),
    hasMore: Boolean(raw.hasMore),
    nextPage: page + 1,
    records: Array.isArray(raw.records) ? raw.records.map(normalizeRechargeOrder) : []
  }
}

function appendRechargeOrders(current = {}, next = {}) {
  const currentData = normalizeRechargeOrders(current)
  const nextData = normalizeRechargeOrders(next)
  const seen = {}
  const records = []
  currentData.records.concat(nextData.records).forEach((item) => {
    if (item.merchantOrderNo && seen[item.merchantOrderNo]) {
      return
    }
    if (item.merchantOrderNo) {
      seen[item.merchantOrderNo] = true
    }
    records.push(item)
  })
  return Object.assign({}, nextData, { records })
}

function buildRechargeOrderQuery(options = {}) {
  return {
    page: toPositiveNumber(options.page, DEFAULT_PAGE),
    pageSize: toPositiveNumber(options.pageSize, DEFAULT_PAGE_SIZE)
  }
}

function applyRechargeOrderSyncs(orderData = {}, syncResults = []) {
  const resultByOrderNo = {}
  syncResults.forEach((item) => {
    if (item && item.merchantOrderNo) {
      resultByOrderNo[item.merchantOrderNo] = item
    }
  })
  return Object.assign({}, orderData, {
    records: (orderData.records || []).map((record) => {
      const sync = resultByOrderNo[record.merchantOrderNo]
      if (!sync) {
        return record
      }
      return Object.assign({}, record, {
        status: sync.status || record.status,
        statusText: sync.statusText || record.statusText,
        statusTone: statusTone(sync.status || record.status)
      })
    })
  })
}

module.exports = {
  appendRechargeOrders,
  applyRechargeBalance,
  applyRechargeOrderSyncs,
  buildRechargeOrderQuery,
  buildRequestPaymentOptions,
  isPaymentCancelled,
  normalizeRechargeOrders,
  normalizeRechargePage,
  selectRechargePackage
}
