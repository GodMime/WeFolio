const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const test = require('node:test')

// 使用真实页面和充值数据归一化逻辑，只替换网络边界。
function loadPage(request) {
  let definition
  const filename = path.join(__dirname, '../pages/recharge-records/recharge-records.js')
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
    Page(value) { definition = value },
    wx: {},
    require(name) {
      if (name.endsWith('/request')) return { request }
      if (name.endsWith('/session')) return { hasLocalToken: () => true, handleMaintainerAuthRequired() {} }
      if (name.endsWith('/recharge')) return require('../utils/recharge')
      throw new Error(`未声明的测试依赖: ${name}`)
    }
  }, { filename })
  definition.syncedOrderNos = {}
  definition.setData = function (data) { Object.assign(this.data, data) }
  return definition
}

test('已关闭订单仍主动确认迟到支付，已入账订单不重复发起公开查单', async () => {
  const calls = []
  const page = loadPage(async (options) => {
    calls.push(options.url)
    return { merchantOrderNo: 'closed-order', status: 'PAID', confirmed: true }
  })
  await page.syncPendingOrders([
    { merchantOrderNo: 'closed-order', status: 'CLOSED' },
    { merchantOrderNo: 'paid-order', status: 'PAID' }
  ])
  assert.deepEqual(calls, ['/api/mine/recharges/orders/closed-order/sync'])
})

test('暂时查单失败释放本页重试标记，成功后同页只确认一次', async () => {
  let calls = 0
  const page = loadPage(async () => {
    calls += 1
    if (calls === 1) throw new Error('暂时失败')
    return { merchantOrderNo: 'pending-order', status: 'PAID', confirmed: true }
  })
  const records = [{ merchantOrderNo: 'pending-order', status: 'PENDING_PAYMENT' }]
  await page.syncPendingOrders(records)
  await page.syncPendingOrders(records)
  await page.syncPendingOrders(records)
  assert.equal(calls, 2)
})
