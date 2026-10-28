const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const { openMockVideoPlayer, VIDEO_PLAYER_EVENT } = require('../pages/mock/utils/mock-video-player')
const { createMockVideoPlayerPage } = require('../pages/mock/utils/mock-video-player-page')
const VIDEO = { url: 'https://example.test/mock.mp4', poster: 'https://example.test/cover.jpg', title: '体验作品' }
const FAILURE_TOAST = { title: '视频打开失败，请重试', icon: 'none' }

function harness(options = {}) {
  const calls = [], timers = new Map(), listeners = new Map()
  let timerId = 0
  const video = Object.fromEntries(['pause', 'play', 'stop', 'exitFullScreen', 'requestFullScreen'].map(method =>
    [method, argument => { calls.push([method, argument]); if (options.throws === method) throw new Error(method) }]))
  const wxApi = {
    createVideoContext(id, page) { calls.push(['createVideoContext', id, page]); return video },
    hideShareMenu(value) { calls.push(['hideShareMenu', value]) },
    showToast(value) { calls.push(['showToast', value]) },
    navigateBack(value) { calls.push(['navigateBack', value]) },
    reLaunch(value) { calls.push(['reLaunch', value]) }
  }
  const channel = {
    on(event, handler) { listeners.set(event, handler) },
    off(event, handler) { assert.equal(listeners.get(event), handler); listeners.delete(event); calls.push(['off', event]) }
  }
  const definition = createMockVideoPlayerPage({ wxApi,
    setTimer(callback, delay) { assert.ok([1000, 3000].includes(delay)); timers.set(++timerId, callback); return timerId },
    clearTimer(id) { timers.delete(id) } })
  const page = Object.assign({}, definition, { data: structuredClone(definition.data),
    setData(patch) { calls.push(['setData', patch]); Object.assign(this.data, patch) },
    getOpenerEventChannel() { return options.withoutChannel ? null : channel } })
  page.onLoad({ url: 'https://example.test/query.mp4' })
  const receiver = listeners.get(VIDEO_PLAYER_EVENT)
  return { page, calls, timers, listeners,
    receive(value = VIDEO) { receiver(value) },
    timeout() { for (const callback of [...timers.values()]) callback() },
    methods() { return calls.map(call => call[0]).filter(name => !['setData', 'createVideoContext'].includes(name)) } }
}

test('mock 视频通过独立分包页的事件通道传递资源，不拼入路由', async () => {
  let navigation
  const events = []
  const result = openMockVideoPlayer(VIDEO, { navigateTo(options) { navigation = options } })
  assert.equal(navigation.url, '/pages/mock/video-player/video-player')
  navigation.success({ eventChannel: { emit: (...args) => events.push(args) } })
  assert.equal(await result, true)
  assert.equal(VIDEO_PLAYER_EVENT, 'mockVideoPlayer')
  assert.deepEqual(events, [[VIDEO_PLAYER_EVENT, VIDEO]])
  events.length = 0
  assert.equal(await openMockVideoPlayer({ url: VIDEO.url }, {
    navigateTo: options => options.success({ eventChannel: { emit: (...args) => events.push(args) } })
  }), true)
  assert.deepEqual(events, [[VIDEO_PLAYER_EVENT, { url: VIDEO.url, poster: '', title: '' }]])
})

test('mock 打开失败统一提示且不回退到旧预览，取消不提示', async () => {
  for (const navigateTo of [undefined, options => options.fail({ errMsg: 'navigateTo:fail' }),
    () => { throw new Error('unavailable') }, options => options.success({}),
    options => options.success({ eventChannel: { emit() { throw new Error('unavailable') } } })]) {
    const toasts = []
    assert.equal(await openMockVideoPlayer(VIDEO, { navigateTo, showToast: value => toasts.push(value),
      previewMedia() { assert.fail('不能回退到原生预览') } }), false)
    assert.deepEqual(toasts, [FAILURE_TOAST])
  }
  assert.equal(await openMockVideoPlayer(VIDEO, {
    navigateTo: options => options.fail({ errMsg: 'navigateTo:fail cancel' }),
    showToast() { assert.fail('取消不提示失败') }
  }), false)
  for (const input of [undefined, {}, { url: '' }, { url: '   ' }]) {
    assert.equal(await openMockVideoPlayer(input, {
      navigateTo() { assert.fail('缺少视频不跳转') }, showToast() {}
    }), false)
  }
})

test('mock 播放页只接受通道资源并关闭分享入口', () => {
  const state = harness()
  assert.equal(state.page.data.url, '')
  assert.equal(state.page.data.loadingContext, true)
  state.receive()
  assert.equal(state.page.data.url, VIDEO.url)
  assert.equal(state.page.data.available, true)
  assert.equal(state.page.data.loadingContext, false)
  assert.equal(state.timers.size, 0)
  assert.deepEqual(state.calls.find(call => call[0] === 'hideShareMenu')[1].menus, ['shareAppMessage', 'shareTimeline'])
  state.page.onUnload()
})

test('mock 播放页缺失、超时、无效或卸载后的资源不会启动视频', () => {
  const missing = harness({ withoutChannel: true })
  assert.equal(missing.page.data.loadingContext, false)
  assert.equal(missing.page.data.available, false)
  missing.page.onUnload()
  const late = harness()
  late.timeout(); late.receive()
  assert.equal(late.page.data.available, false)
  assert.equal(late.page.data.loadingContext, false)
  late.page.onUnload()
  for (const invalid of [null, {}, { url: '' }, { url: 'javascript:bad' }, { url: 'https://bad url' }]) {
    const state = harness()
    state.receive(invalid)
    assert.equal(state.page.data.available, false)
    assert.equal(state.page.data.loadingContext, false)
    assert.equal(state.timers.size, 0)
    state.page.onUnload()
  }
  const unloaded = harness()
  unloaded.page.onUnload(); unloaded.receive(); unloaded.timeout()
  assert.equal(unloaded.page.data.available, false)
  assert.equal(unloaded.timers.size, 0)
})

test('mock 正常退出原生全屏保留 video 挂载，可再次全屏播放', () => {
  const state = harness()
  state.receive(); state.page.onShow()
  state.page.handleEnterFullscreen()
  assert.deepEqual(state.calls.find(call => call[0] === 'requestFullScreen')[1], { direction: 90 })
  state.page.handleFullscreenChange({ detail: { fullScreen: true } })
  state.page.handleFullscreenChange({ detail: { fullScreen: false } })
  assert.equal(state.page.data.fullscreen, false)
  assert.equal(state.page.data.available, true)
  assert.equal(state.page.data.url, VIDEO.url)
  assert.equal(state.methods().includes('navigateBack'), false)
  state.page.handleEnterFullscreen()
  assert.equal(state.methods().filter(name => name === 'requestFullScreen').length, 2)
  state.page.onUnload()
})

test('mock 首次 onShow 前的自动播放只补偿一次，后台暂停后不自动恢复声音', () => {
  const state = harness()
  state.receive(); state.page.handleVideoPlay()
  assert.equal(state.methods().at(-1), 'pause')
  state.page.onShow()
  assert.equal(state.methods().at(-1), 'play')
  state.page.handleVideoPlay(); state.page.onHide(); state.page.handleVideoPlay()
  assert.equal(state.methods().at(-1), 'pause')
  const playCount = state.methods().filter(name => name === 'play').length
  state.page.onShow()
  assert.equal(state.methods().filter(name => name === 'play').length, playCount)
  state.page.onUnload()
})

test('mock 主动返回等待真实全屏退出事件，重复或畸形事件不提前跳转', () => {
  const state = harness()
  state.receive(); state.page.onShow()
  state.page.handleFullscreenChange({ detail: { fullScreen: true } })
  state.calls.length = 0
  state.page.handleBack()
  assert.deepEqual(state.methods(), ['pause', 'exitFullScreen'])
  assert.equal(state.page.data.available, true)
  for (const event of [undefined, {}, { detail: {} }, { detail: { fullScreen: 'false' } }]) {
    state.page.handleFullscreenChange(event)
    assert.equal(state.methods().includes('navigateBack'), false)
  }
  state.page.handleFullscreenChange({ detail: { fullScreen: false } })
  state.page.handleFullscreenChange({ detail: { fullScreen: false } })
  assert.equal(state.methods().filter(name => name === 'navigateBack').length, 1)
  state.page.onUnload()
})

test('mock 全屏打开无响应时可超时恢复返回，全屏退出异常提示返回作品', () => {
  const waiting = harness()
  waiting.receive(); waiting.page.onShow(); waiting.page.handleEnterFullscreen(); waiting.page.handleBack()
  assert.equal(waiting.methods().includes('navigateBack'), false)
  waiting.timeout()
  assert.equal(waiting.methods().includes('navigateBack'), true)
  assert.equal(waiting.timers.size, 0)
  waiting.page.onUnload()
  const failed = harness({ throws: 'exitFullScreen' })
  failed.receive(); failed.page.onShow(); failed.page.handleFullscreenChange({ detail: { fullScreen: true } })
  failed.page.handleBack()
  assert.deepEqual(failed.calls.find(call => call[0] === 'showToast')[1], { title: '请先退出全屏，再返回作品', icon: 'none' })
  assert.equal(failed.methods().includes('navigateBack'), false)
  failed.page.handleFullscreenChange({ detail: { fullScreen: false } })
  assert.equal(failed.methods().includes('navigateBack'), true)
  failed.page.onUnload()
})

test('mock 播放错误只在前台提示，卸载时退出异常仍停止并清理监听', () => {
  const state = harness({ throws: 'exitFullScreen' })
  state.receive(); state.page.handleVideoError()
  assert.equal(state.methods().includes('showToast'), false)
  state.page.onShow(); state.page.handleVideoError()
  assert.deepEqual(state.calls.find(call => call[0] === 'showToast')[1], { title: '视频暂不可用', icon: 'none' })
  state.page.onHide()
  assert.equal(state.methods().at(-1), 'pause')
  state.page.onUnload()
  assert.equal(state.methods().includes('exitFullScreen'), true)
  assert.equal(state.methods().includes('stop'), true)
  assert.equal(state.listeners.size, 0)
  assert.equal(state.timers.size, 0)
  const count = state.calls.length
  state.receive(); state.page.handleVideoError(); state.page.handleVideoPlay()
  state.page.handleFullscreenChange({ detail: { fullScreen: false } })
  assert.equal(state.calls.length, count)
})

test('mock 返回失败只兜底到体验作品页，重试与卸载后回调均安全', () => {
  const state = harness({ withoutChannel: true })
  state.page.handleBack()
  const back = state.calls.find(call => call[0] === 'navigateBack')[1]
  back.fail()
  const fallback = state.calls.find(call => call[0] === 'reLaunch')[1]
  assert.equal(fallback.url, '/pages/mock/works/works')
  fallback.fail(); state.page.handleBack()
  assert.equal(state.methods().filter(name => name === 'navigateBack').length, 2)
  state.page.onUnload()
  const count = state.calls.length
  back.fail(); fallback.fail()
  assert.equal(state.calls.length, count)
})

test('mock 播放页保持独立 webview 原生导航与控件，无底栏、弹窗和后台依赖', () => {
  const read = relative => fs.readFileSync(path.join(__dirname, '../pages/mock', relative), 'utf8')
  const config = JSON.parse(read('video-player/video-player.json'))
  assert.deepEqual(config, require('../pages/works/video-player/video-player.json'))
  const markup = read('video-player/video-player.wxml')
  assert.match(markup, /<video\b[^>]*wx:if="\{\{available\}\}"[^>]*id="mockVideoPlayer"/)
  assert.match(markup, /direction="\{\{90\}\}"/)
  assert.match(markup, /bindfullscreenchange="handleFullscreenChange"/)
  for (const control of ['controls', 'show-fullscreen-btn', 'show-play-btn', 'show-center-play-btn', 'autoplay']) {
    assert.match(markup, new RegExp(`${control}="\\{\\{true\\}\\}"`))
  }
  assert.match(markup, /bindtap="handleBack">返回作品<\/button>/)
  assert.doesNotMatch(markup, /bottom-nav|mock-tabbar|video-modal|catchtouch|catchtap|<scroll-view|<cover-view/)
  // 体验版与正式端保持相同的默认播放外观，业务行为各自独立。
  const styleRules = source => Array.from(source.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{([^{}]*)\}/g),
    match => ({ selector: match[1].trim(), declarations: match[2].trim() }))
  const formalStyles = fs.readFileSync(path.join(__dirname, '../pages/works/video-player/video-player.wxss'), 'utf8')
  const formalDefaultRules = styleRules(formalStyles)
    .map(rule => ({ ...rule, selector: rule.selector.replace(/portfolio-video/g, 'mock-video') }))
  assert.deepEqual(styleRules(read('video-player/video-player.wxss')), formalDefaultRules)
  for (const file of ['video-player/video-player.js', 'utils/mock-video-player.js', 'utils/mock-video-player-page.js']) {
    const source = read(file)
    assert.doesNotMatch(source, /request\(|uploadFile|\/api\/|visitor-session|browserContext|utils\/(?:request|session|avatar|team-avatar)/)
    for (const match of source.matchAll(/require\(['"]([^'"]+)['"]\)/g)) {
      const target = path.resolve(__dirname, '../pages/mock', path.dirname(file), match[1])
      assert.ok(target.startsWith(path.resolve(__dirname, '../pages/mock') + path.sep), `依赖应留在 mock 分包: ${target}`)
    }
  }
})
