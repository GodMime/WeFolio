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
const indexJs = fs.readFileSync(
  path.join(__dirname, '../pages/index/index.js'),
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

test('mine metric cards use Skyline compatible three column flex layout', () => {
  const metricGridRule = readRule('.metric-grid')
  const metricRule = readRule('.metric')

  assert.match(indexWxml, /class="metric-grid"[\s\S]*class="metric"/)
  assert.match(metricGridRule, /display:\s*flex/)
  assert.match(metricGridRule, /gap:\s*16rpx/)
  assert.doesNotMatch(metricGridRule, /display:\s*grid/)
  assert.doesNotMatch(metricGridRule, /grid-template-columns/)
  assert.match(metricRule, /flex:\s*1\s+1\s+0/)
  assert.match(metricRule, /min-width:\s*0/)
})

test('mine action entries use COS image logos and keep content left aligned', () => {
  const entryRowRule = readRule('.entry-row')
  const entryIconRule = readRule('.entry-icon')

  assert.match(indexJs, /iconUrl:\s*'https:\/\/cos\.we-folio\.dingchenyong\.top\/system\/wefolio-visitor-record-icon\.png'/)
  assert.match(indexJs, /iconUrl:\s*'https:\/\/cos\.we-folio\.dingchenyong\.top\/system\/wefolio-team-icon\.png'/)
  assert.match(indexJs, /const MESSAGE_ICON_URL = 'https:\/\/cos\.we-folio\.dingchenyong\.top\/system\/wefolio-message-icon\.png'/)
  assert.match(indexJs, /iconUrl:\s*MESSAGE_ICON_URL/)
  assert.match(indexJs, /title:\s*'我的消息'[\s\S]*desc:\s*'系统提醒、团队邀请'/)
  assert.doesNotMatch(indexJs, /desc:\s*'系统提醒、团队邀请、积分不足'/)
  assert.match(indexWxml, /<view\b[^>]*class="entry-row"[^>]*bindtap="handleEntryTap"[^>]*>/)
  assert.doesNotMatch(indexWxml, /<button\b[^>]*class="entry-row"/)
  assert.match(indexWxml, /<image[\s\S]*class="entry-icon"[\s\S]*src="\{\{item\.iconUrl\}\}"[\s\S]*mode="aspectFit"/)

  assert.match(entryRowRule, /justify-content:\s*flex-start/)
  assert.match(entryRowRule, /margin:\s*0/)
  assert.match(entryIconRule, /display:\s*block/)
  assert.match(entryIconRule, /width:\s*92rpx/)
  assert.match(entryIconRule, /height:\s*92rpx/)
  assert.doesNotMatch(indexWxss, /\.visit-icon::before/)
  assert.doesNotMatch(indexWxss, /\.team-icon::before/)
})
