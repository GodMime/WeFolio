const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const MODULES = ['portfolios', 'team-portfolios'].map(name =>
  path.join(__dirname, `../pages/${name}/utils/portfolio-video-player.js`))
const VIDEO = { url: 'https://cdn.example.com/work.mp4', poster: 'https://cdn.example.com/cover.jpg' }
const FAILURE_TOAST = { title: '视频打开失败，请重试', icon: 'none' }

test('视频播放页导航保留在各自分包，两个实现保持一致', () => {
  assert.equal(fs.readFileSync(MODULES[0], 'utf8'), fs.readFileSync(MODULES[1], 'utf8'))
})

for (const filename of MODULES) {
  const label = path.relative(__dirname, filename)
  const route = `/pages/${path.basename(path.dirname(path.dirname(filename)))}/video-player/video-player`

  test(`${label}: 等待页面打开后通过事件通道交付视频与原会话，路由不拼接媒体或凭据`, async () => {
    const { VIDEO_PLAYER_EVENT, openVideoPlayer } = require(filename)
    const browserContext = { idempotencyKey: 'test-video-context', visitorToken: 'test-visitor-token' }
    // 循环引用确保会话仍为页面间的同一个对象，不经过 JSON 序列化。
    browserContext.self = browserContext
    let options
    let settled = false
    let nativePreviewCalls = 0
    const events = []
    const result = openVideoPlayer({ ...VIDEO, route, title: '婚礼作品', browserContext }, {
      navigateTo(value) { options = value },
      previewMedia() { nativePreviewCalls += 1 }
    })
    result.then(() => { settled = true })
    assert.equal(typeof result.then, 'function')
    await Promise.resolve()
    assert.equal(settled, false)
    assert.equal(options.url, route)
    assert.equal(options.url.includes('?'), false)
    options.success({ eventChannel: { emit: (...args) => events.push(args) } })
    assert.equal(await result, true)
    assert.equal(VIDEO_PLAYER_EVENT, 'portfolioVideoPlayer')
    assert.deepEqual(events, [[VIDEO_PLAYER_EVENT, {
      ...VIDEO,
      title: '婚礼作品',
      browserContext,
      contextId: 'test-video-context'
    }]])
    assert.equal(events[0][1].browserContext, browserContext)
    assert.equal(nativePreviewCalls, 0)
  })

  test(`${label}: 无封面、标题和访客会话时发送安全默认值`, async () => {
    const { VIDEO_PLAYER_EVENT, openVideoPlayer } = require(filename)
    const events = []
    assert.equal(await openVideoPlayer({ url: VIDEO.url, route }, {
      navigateTo: options => options.success({ eventChannel: { emit: (...args) => events.push(args) } })
    }), true)
    assert.deepEqual(events, [[VIDEO_PLAYER_EVENT, {
      url: VIDEO.url,
      poster: '',
      title: '',
      browserContext: null,
      contextId: ''
    }]])
  })

  test(`${label}: 会话尚无幂等键时保留会话对象并发送空标识`, async () => {
    const { openVideoPlayer } = require(filename)
    const browserContext = { visitorToken: 'test-visitor-token' }
    let payload
    assert.equal(await openVideoPlayer({ ...VIDEO, route, browserContext }, {
      navigateTo: options => options.success({ eventChannel: { emit: (event, value) => { payload = value } } })
    }), true)
    assert.equal(payload.browserContext, browserContext)
    assert.equal(payload.contextId, '')
  })

  test(`${label}: 页面跳转或事件通道失败只提示一次并返回失败，不回退到原生预览`, async () => {
    const { openVideoPlayer } = require(filename)
    const failures = [
      undefined,
      options => options.fail({ errMsg: 'navigateTo:fail' }),
      () => { throw new Error('navigation unavailable') },
      options => options.success(),
      options => options.success({}),
      options => options.success({ eventChannel: {} }),
      options => options.success({ eventChannel: { emit() { throw new Error('event unavailable') } } })
    ]
    for (const navigateTo of failures) {
      const toasts = []
      let nativePreviewCalls = 0
      assert.equal(await openVideoPlayer({ ...VIDEO, route }, {
        navigateTo,
        previewMedia() { nativePreviewCalls += 1 },
        showToast: value => toasts.push(value)
      }), false)
      assert.deepEqual(toasts, [FAILURE_TOAST])
      assert.equal(nativePreviewCalls, 0)
    }
  })

  test(`${label}: 异步跳转失败仍返回失败且只提示一次`, async () => {
    const { openVideoPlayer } = require(filename)
    let options
    const toasts = []
    const result = openVideoPlayer({ ...VIDEO, route }, {
      navigateTo(value) { options = value },
      showToast: value => toasts.push(value)
    })
    await Promise.resolve()
    options.fail({ errMsg: 'navigateTo:fail page limit' })
    assert.equal(await result, false)
    assert.deepEqual(toasts, [FAILURE_TOAST])
  })

  test(`${label}: 视频地址或播放页路由为空时不会跳转`, async () => {
    const { openVideoPlayer } = require(filename)
    for (const input of [{ route }, { ...VIDEO }, { ...VIDEO, route: '' }, { url: '', route }]) {
      let navigationCalls = 0
      let nativePreviewCalls = 0
      assert.equal(await openVideoPlayer(input, {
        navigateTo() { navigationCalls += 1 },
        previewMedia() { nativePreviewCalls += 1 },
        showToast() {}
      }), false)
      assert.equal(navigationCalls, 0)
      assert.equal(nativePreviewCalls, 0)
    }
  })

  test(`${label}: 用户取消不显示播放失败提示`, async () => {
    const { openVideoPlayer } = require(filename)
    const toasts = []
    assert.equal(await openVideoPlayer({ ...VIDEO, route }, {
      navigateTo: options => options.fail({ errMsg: 'navigateTo:fail cancel' }),
      showToast: value => toasts.push(value)
    }), false)
    assert.deepEqual(toasts, [])
  })

  test(`${label}: 导航错误中的测试凭据不会进入日志或提示`, async t => {
    const { openVideoPlayer } = require(filename)
    const logs = ['log', 'info', 'warn', 'error', 'debug'].map(method => t.mock.method(console, method, () => {}))
    const toasts = []
    const credential = 'test-video-private-credential'
    assert.equal(await openVideoPlayer({
      ...VIDEO,
      route,
      browserContext: { visitorToken: credential, idempotencyKey: 'test-video-context' }
    }, {
      navigateTo: options => options.fail({ errMsg: `navigateTo:fail ${credential}` }),
      showToast: value => toasts.push(value)
    }), false)
    assert.deepEqual(toasts, [FAILURE_TOAST])
    for (const logger of logs) {
      assert.equal(JSON.stringify(logger.mock.calls.map(call => call.arguments)).includes(credential), false)
    }
  })
}
