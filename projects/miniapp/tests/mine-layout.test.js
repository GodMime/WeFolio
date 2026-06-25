const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const indexWxml = fs.readFileSync(
  path.join(__dirname, '../pages/index/index.wxml'),
  'utf8'
)
const indexWxss = fs.readFileSync(
  path.join(__dirname, '../pages/index/index.wxss'),
  'utf8'
)

function readRule(selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = indexWxss.match(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`))
  return match ? match[1] : ''
}

test('mine recharge button matches design draft button shape', () => {
  const buttonRule = readRule('.light-button')

  assert.match(indexWxml, /class="light-button"[\s\S]*bindtap="handleRechargeTap"[\s\S]*>充值</)
  assert.match(buttonRule, /height:\s*88rpx/)
  assert.match(buttonRule, /display:\s*inline-flex/)
  assert.match(buttonRule, /align-items:\s*center/)
  assert.match(buttonRule, /justify-content:\s*center/)
  assert.match(buttonRule, /padding:\s*0\s+28rpx/)
  assert.match(buttonRule, /font-weight:\s*800/)
  assert.match(buttonRule, /border-radius:\s*16rpx/)
  assert.doesNotMatch(buttonRule, /border-radius:\s*999rpx/)
})

test('mine recharge button stays pinned to the right of balance band', () => {
  const balanceTopRule = readRule('.balance-top')
  const balanceCopyRule = readRule('.balance-copy')
  const buttonRule = readRule('.light-button')

  assert.match(
    indexWxml,
    /class="balance-top"[\s\S]*class="balance-copy"[\s\S]*class="light-button"/
  )
  assert.match(balanceTopRule, /width:\s*100%/)
  assert.match(balanceCopyRule, /flex:\s*1/)
  assert.match(balanceCopyRule, /min-width:\s*0/)
  assert.match(buttonRule, /flex:\s*none/)
  assert.match(buttonRule, /margin-left:\s*auto/)
})
