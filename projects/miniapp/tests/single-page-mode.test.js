const assert = require('node:assert/strict')
const test = require('node:test')

const {
  WECHAT_TIMELINE_SINGLE_PAGE_SCENE,
  createAnonymousSessionId,
  isWechatTimelineSinglePage
} = require('../utils/single-page-mode')

test('detects WeChat timeline single-page scene from current enter options', () => {
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

test('single-page detection safely rejects unavailable or failing runtime APIs', () => {
  assert.equal(isWechatTimelineSinglePage({}), false)
  assert.equal(isWechatTimelineSinglePage({
    getEnterOptionsSync() {
      throw new Error('unavailable')
    }
  }), false)
})

test('creates a bounded high-entropy anonymous session id', () => {
  const first = createAnonymousSessionId()
  const second = createAnonymousSessionId()

  assert.match(first, /^timeline-[a-z0-9-]{32,119}$/)
  assert.ok(first.length <= 128)
  assert.notEqual(first, second)
})
