const assert = require('node:assert/strict')
const test = require('node:test')

const {
  appendRechargeOrders,
  applyRechargeBalance,
  applyRechargeOrderSyncs,
  buildRequestPaymentOptions,
  isPaymentCancelled,
  normalizeRechargeOrders,
  normalizeRechargePage,
  selectRechargePackage
} = require('../utils/recharge')

test('normalizes recharge packages and selects fifty yuan package by default', () => {
  const rechargeData = normalizeRechargePage({
    balance: 286,
    lowBalance: false,
    lowBalanceThreshold: 50,
    packages: [
      { packageId: 1, packageCode: 'POINT_1', packageName: '1 元档', amountFen: 100, basePoints: 10, bonusPoints: 0, totalPoints: 10 },
      { packageId: 2, packageCode: 'POINT_10', packageName: '10 元档', amountFen: 1000, basePoints: 100, bonusPoints: 0, totalPoints: 100 },
      { packageId: 3, packageCode: 'POINT_50', packageName: '50 元档', amountFen: 5000, basePoints: 500, bonusPoints: 20, totalPoints: 520 },
      { packageId: 4, packageCode: 'POINT_100', packageName: '100 元档', amountFen: 10000, basePoints: 1000, bonusPoints: 100, totalPoints: 1100 }
    ]
  })

  assert.equal(rechargeData.balanceText, '286')
  assert.equal(rechargeData.selectedPackageId, 3)
  assert.equal(rechargeData.selectedPackage.amountText, '50 元')
  assert.equal(rechargeData.selectedPackage.bonusText, '多送 20 积分')
  assert.deepEqual(rechargeData.packages.map((item) => item.selected), [false, false, true, false])
})

test('falls back to first package and changes package selection', () => {
  const rechargeData = normalizeRechargePage({
    packages: [
      { packageId: 8, packageName: '10 元档', amountFen: 1000, totalPoints: 100 },
      { packageId: 9, packageName: '100 元档', amountFen: 10000, totalPoints: 1100 }
    ]
  })

  assert.equal(rechargeData.selectedPackageId, 8)
  const selected = selectRechargePackage(rechargeData, 9)
  assert.equal(selected.selectedPackageId, 9)
  assert.deepEqual(selected.packages.map((item) => item.selected), [false, true])
})

test('maps backend payment fields to wx.requestPayment options', () => {
  assert.deepEqual(buildRequestPaymentOptions({
    timeStamp: '1752721200',
    nonceStr: 'nonce-value',
    packageValue: 'prepay_id=wx123',
    signType: 'RSA',
    paySign: 'signature'
  }), {
    timeStamp: '1752721200',
    nonceStr: 'nonce-value',
    package: 'prepay_id=wx123',
    signType: 'RSA',
    paySign: 'signature'
  })
})

test('applies confirmed balance and recalculates low balance copy', () => {
  const rechargeData = normalizeRechargePage({
    balance: 80,
    lowBalance: false,
    lowBalanceThreshold: 50,
    packages: []
  })

  const result = applyRechargeBalance(rechargeData, 20)

  assert.equal(result.balanceText, '20')
  assert.equal(result.lowBalance, true)
  assert.equal(result.warningText, '余额低于 50 积分，建议及时充值')
})

test('recognizes only explicit payment cancellation', () => {
  assert.equal(isPaymentCancelled({ errMsg: 'requestPayment:fail cancel' }), true)
  assert.equal(isPaymentCancelled({ errMsg: 'requestPayment:fail system error' }), false)
})

test('normalizes and appends recharge records without duplicate order numbers', () => {
  const first = normalizeRechargeOrders({
    page: 1,
    pageSize: 2,
    total: 3,
    hasMore: true,
    records: [
      { merchantOrderNo: 'WFR1', packageName: '50 元档', amountFen: 5000, bonusPoints: 20, totalPoints: 520, status: 'PAID', statusText: '充值成功', createdAt: '2026-07-17 12:00:00' },
      { merchantOrderNo: 'WFR2', packageName: '10 元档', amountFen: 1000, totalPoints: 100, status: 'PENDING_PAYMENT', statusText: '待支付' }
    ]
  })
  const second = normalizeRechargeOrders({
    page: 2,
    pageSize: 2,
    total: 3,
    hasMore: false,
    records: [
      { merchantOrderNo: 'WFR2', packageName: '重复订单' },
      { merchantOrderNo: 'WFR3', packageName: '1 元档', amountFen: 100, totalPoints: 10, status: 'CLOSED', statusText: '已关闭' }
    ]
  })

  const result = appendRechargeOrders(first, second)

  assert.deepEqual(first.records.map((item) => item.statusTone), ['success', 'pending'])
  assert.equal(first.records[0].amountText, '50 元')
  assert.equal(first.records[0].bonusText, '赠送 20 积分')
  assert.deepEqual(result.records.map((item) => item.merchantOrderNo), ['WFR1', 'WFR2', 'WFR3'])
  assert.equal(result.nextPage, 3)
  assert.equal(result.hasMore, false)
})

test('applies active query result to matching pending order only', () => {
  const orderData = normalizeRechargeOrders({
    records: [
      { merchantOrderNo: 'WFR1', status: 'PENDING_PAYMENT', statusText: '待支付' },
      { merchantOrderNo: 'WFR2', status: 'PAID', statusText: '充值成功' }
    ]
  })

  const result = applyRechargeOrderSyncs(orderData, [{
    merchantOrderNo: 'WFR1',
    status: 'CLOSED',
    statusText: '已关闭',
    confirmed: true
  }])

  assert.equal(result.records[0].status, 'CLOSED')
  assert.equal(result.records[0].statusTone, 'muted')
  assert.equal(result.records[1].status, 'PAID')
})
