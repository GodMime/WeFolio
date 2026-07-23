const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const implementations = [
  require('../pages/portfolios/utils/single-page-mode'),
  require('../pages/team-portfolios/utils/single-page-mode')
]

test('keeps single-page helpers in their subpackages instead of the main package', () => {
  assert.equal(fs.existsSync(path.join(__dirname, '../utils/single-page-mode.js')), false)
  assert.equal(fs.existsSync(path.join(__dirname, '../pages/portfolios/utils/single-page-mode.js')), true)
  assert.equal(fs.existsSync(path.join(__dirname, '../pages/team-portfolios/utils/single-page-mode.js')), true)
})

test('detects WeChat timeline single-page scene from current enter options', () => {
  implementations.forEach(({ WECHAT_TIMELINE_SINGLE_PAGE_SCENE, isWechatTimelineSinglePage }) => {
    assert.equal(WECHAT_TIMELINE_SINGLE_PAGE_SCENE, 1154)
    assert.equal(isWechatTimelineSinglePage({
      getEnterOptionsSync() {
        return { scene: 1154 }
      }
    }), true)
    assert.equal(isWechatTimelineSinglePage({
      getEnterOptionsSync() {
        return { scene: 1001 }
      }
    }), false)
  })
})

test('single-page detection safely rejects unavailable or failing runtime APIs', () => {
  implementations.forEach(({ isWechatTimelineSinglePage }) => {
    assert.equal(isWechatTimelineSinglePage({}), false)
    assert.equal(isWechatTimelineSinglePage({
      getEnterOptionsSync() {
        throw new Error('unavailable')
      }
    }), false)
  })
})

test('creates a bounded high-entropy anonymous session id', () => {
  implementations.forEach(({ createAnonymousSessionId }) => {
    const first = createAnonymousSessionId()
    const second = createAnonymousSessionId()

    assert.match(first, /^timeline-[a-z0-9-]{32,119}$/)
    assert.ok(first.length <= 128)
    assert.notEqual(first, second)
  })
})
