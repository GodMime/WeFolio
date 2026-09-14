const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const packages = ['portfolios', 'team-portfolios']
const payload = overrides => Object.assign({ url: 'https://example.test/video.mp4', poster: 'https://example.test/cover.jpg',
  title: '视频作品', browserContext: null, contextId: '' }, overrides)

function harness(utility, options = {}) {
  const calls = [], timers = new Map(), listeners = new Map()
  let timerId = 0
  const video = Object.fromEntries(['pause', 'play', 'stop', 'exitFullScreen', 'requestFullScreen'].map(method =>
    [method, argument => { calls.push([method, argument]); if (options.throws === method) throw new Error(method) }]))
  const wxApi = { createVideoContext(id, page) { calls.push(['createVideoContext', id, page]); return video },
    hideShareMenu(value) { calls.push(['hideShareMenu', value]) }, showToast(value) { calls.push(['showToast', value]) },
    navigateBack(value) { calls.push(['navigateBack', value]) }, reLaunch(value) { calls.push(['reLaunch', value]) } }
  const channel = { on(event, handler) { listeners.set(event, handler) },
    off(event, handler) { assert.equal(listeners.get(event), handler); listeners.delete(event); calls.push(['off', event]) } }
  const definition = utility.createVideoPlayerPage({ wxApi,
    setTimer(callback, delay) { assert.ok([1000, 3000].includes(delay)); timers.set(++timerId, callback); return timerId },
    clearTimer(id) { timers.delete(id) } })
  const page = Object.assign({}, definition, { data: structuredClone(definition.data),
    setData(patch, done) { calls.push(['setData', patch]); Object.assign(this.data, patch); if (done) done() },
    getOpenerEventChannel() { return options.withoutChannel ? null : channel } })
  page.onLoad({ url: 'https://example.test/untrusted.mp4', contextId: 'untrusted' })
  const receiver = listeners.get('portfolioVideoPlayer')
  return { page, calls, timers, listeners, video,
    receive(value = payload()) { receiver(value) },
    timeout() { for (const callback of [...timers.values()]) callback() },
    methods() { return calls.map(call => call[0]).filter(name => !['setData', 'createVideoContext'].includes(name)) } }
}

for (const packageName of packages) {
  const utility = require(`../pages/${packageName}/utils/portfolio-video-player-page`)

  test(`${packageName} only an in-memory channel payload starts playback and hides sharing`, () => {
    const state = harness(utility)
    assert.equal(state.page.data.loadingContext, true)
    assert.equal(state.page.data.available, false)
    assert.equal(state.page.data.url, '')
    state.receive()
    assert.equal(state.page.data.available, true)
    assert.equal(state.page.data.loadingContext, false)
    assert.equal(state.page.data.url, payload().url)
    assert.equal(state.timers.size, 0)
    assert.ok(state.methods().includes('hideShareMenu'))
    assert.equal(state.calls.find(call => call[0] === 'hideShareMenu')[1].menus.includes('shareAppMessage'), true)
    state.page.onUnload()
  })

  test(`${packageName} missing, invalid or late context stays unavailable`, () => {
    const missing = harness(utility, { withoutChannel: true })
    assert.equal(missing.page.data.loadingContext, false)
    assert.equal(missing.page.data.available, false)
    missing.page.handleBack()
    assert.equal(missing.methods().filter(name => name === 'navigateBack').length, 1)
    missing.page.onUnload()
    const late = harness(utility)
    late.timeout(); late.receive()
    assert.equal(late.page.data.loadingContext, false)
    assert.equal(late.page.data.available, false)
    late.page.onUnload()
    for (const invalid of [null, {}, payload({ url: '' }), payload({ url: 'javascript:bad' }),
      payload({ browserContext: { idempotencyKey: 'session' }, contextId: '' }),
      payload({ browserContext: { idempotencyKey: 'session' }, contextId: 'other' }),
      payload({ browserContext: { idempotencyKey: 'session', isInvalid: () => true }, contextId: 'session' }),
      payload({ browserContext: { idempotencyKey: 'session', isDisposed: () => true }, contextId: 'session' }),
      payload({ browserContext: null, contextId: 'session' })]) {
      const state = harness(utility)
      state.receive(invalid)
      assert.equal(state.page.data.available, false)
      assert.equal(state.page.data.loadingContext, false)
      assert.equal(state.timers.size, 0)
      state.page.onUnload()
    }
  })

  test(`${packageName} landscape request uses direction 90 and native events drive the play toggle`, () => {
    const state = harness(utility)
    state.receive(); state.page.onShow()
    state.page.handleEnterFullscreen()
    assert.equal(state.calls.find(call => call[0] === 'requestFullScreen')[1].direction, 90)
    state.page.handleVideoPlay()
    assert.equal(state.page.data.playing, true)
    state.page.handleTogglePlayback()
    assert.equal(state.page.data.playing, true)
    assert.equal(state.methods().at(-1), 'pause')
    state.page.handleVideoPause()
    assert.equal(state.page.data.playing, false)
    state.page.handleTogglePlayback()
    assert.equal(state.methods().at(-1), 'play')
    assert.equal(state.page.data.playing, false)
    state.page.handleVideoPlay(); state.page.handleVideoEnded()
    assert.equal(state.page.data.playing, false)
    state.page.onUnload()
  })

  test(`${packageName} normal native fullscreen exit keeps the player page and video mounted`, () => {
    const state = harness(utility)
    state.receive(); state.page.onShow()
    state.page.handleFullscreenChange({ detail: { fullScreen: true } })
    assert.equal(state.page.data.fullscreen, true)
    assert.equal(state.page.data.available, true)
    state.page.handleFullscreenChange({ detail: { fullScreen: false } })
    assert.equal(state.page.data.fullscreen, false)
    assert.equal(state.page.data.available, true)
    assert.equal(state.methods().includes('navigateBack'), false)
    state.page.onUnload()
  })

  test(`${packageName} malformed fullscreen events never navigate before a real exit event`, () => {
    const state = harness(utility)
    state.receive(); state.page.onShow()
    state.page.handleFullscreenChange({ detail: { fullScreen: true } })
    state.page.handleBack()
    for (const event of [undefined, {}, { detail: {} }, { detail: { fullScreen: 'false' } }, { detail: { fullScreen: 0 } }]) {
      state.page.handleFullscreenChange(event)
      assert.equal(state.page.data.fullscreen, true)
      assert.equal(state.methods().includes('navigateBack'), false)
    }
    state.page.handleFullscreenChange({ detail: { fullScreen: false } })
    assert.equal(state.methods().includes('navigateBack'), true)
    state.page.onUnload()
  })

  test(`${packageName} an unacknowledged fullscreen request cannot leave back pending forever`, () => {
    const state = harness(utility)
    state.receive(); state.page.onShow(); state.page.handleEnterFullscreen()
    assert.deepEqual(state.calls.find(call => call[0] === 'requestFullScreen')[1], { direction: 90 })
    state.page.handleBack()
    assert.equal(state.methods().includes('navigateBack'), false)
    state.timeout()
    assert.equal(state.methods().includes('navigateBack'), true)
    assert.equal(state.timers.size, 0)
    state.page.onUnload()
    const entered = harness(utility)
    entered.receive(); entered.page.onShow(); entered.page.handleEnterFullscreen()
    entered.page.handleFullscreenChange({ detail: { fullScreen: true } })
    assert.equal(entered.timers.size, 0)
    entered.page.onUnload()
  })

  test(`${packageName} navigation without a source page returns to the registered home page`, () => {
    const state = harness(utility, { withoutChannel: true })
    state.page.handleBack()
    state.calls.find(call => call[0] === 'navigateBack')[1].fail()
    const fallback = state.calls.find(call => call[0] === 'reLaunch')[1]
    const config = require('../app.json')
    assert.equal(fallback.url, `/${config.pages[0]}`)
    state.page.onUnload()
    const count = state.calls.length
    fallback.fail()
    state.calls.find(call => call[0] === 'navigateBack')[1].fail()
    assert.equal(state.calls.length, count)
  })

  test(`${packageName} autoplay before first show resumes once while later hide keeps playback paused`, () => {
    const state = harness(utility)
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

  test(`${packageName} explicit back pauses and exits before navigating from fullscreen`, () => {
    const state = harness(utility)
    state.receive(); state.page.onShow()
    state.page.handleFullscreenChange({ detail: { fullScreen: true } })
    state.calls.length = 0
    state.page.handleBack()
    assert.deepEqual(state.methods(), ['pause', 'exitFullScreen'])
    assert.equal(state.page.data.available, true)
    state.page.handleFullscreenChange({ detail: { fullScreen: false } })
    assert.deepEqual(state.methods(), ['pause', 'exitFullScreen', 'navigateBack'])
    state.page.handleFullscreenChange({ detail: { fullScreen: false } })
    assert.equal(state.methods().filter(name => name === 'navigateBack').length, 1)
    state.page.onUnload()
  })

  test(`${packageName} hiding pauses and unloading detaches every resource despite native exit exceptions`, () => {
    const state = harness(utility, { throws: 'exitFullScreen' })
    state.receive(); state.page.onShow()
    state.page.handleFullscreenChange({ detail: { fullScreen: true } })
    state.page.onHide()
    assert.equal(state.methods().at(-1), 'pause')
    state.page.onUnload()
    assert.equal(state.methods().includes('exitFullScreen'), true)
    assert.equal(state.methods().includes('stop'), true)
    assert.equal(state.listeners.size, 0)
    assert.equal(state.timers.size, 0)
    const calls = state.calls.length
    state.receive(); state.page.handleVideoPlay(); state.page.handleFullscreenChange({ detail: { fullScreen: false } })
    assert.equal(state.calls.length, calls)
    const waiting = harness(utility)
    waiting.page.onUnload(); waiting.receive(); waiting.timeout()
    assert.equal(waiting.page.data.available, false)
    assert.equal(waiting.timers.size, 0)
  })

  test(`${packageName} visitor activity follows page visibility without disposing the source context`, () => {
    const activity = []
    const context = { idempotencyKey: 'session', show(owner) { activity.push(['show', owner]) },
      hide(owner) { activity.push(['hide', owner]) }, dispose() { assert.fail('来源页持有访客会话') } }
    const state = harness(utility)
    state.page.onShow()
    state.receive(payload({ browserContext: context, contextId: 'session' }))
    assert.equal(state.page.data.browserContext, undefined)
    state.page.onHide(); state.page.onShow(); state.page.onUnload()
    assert.deepEqual(activity.map(value => value[0]), ['show', 'hide', 'show', 'hide'])
    assert.ok(activity.every(value => value[1] === state.page))
  })

  test(`${packageName} source refresh returns only after fullscreen exits and restores the callback`, () => {
    const refreshed = []
    const original = function (...args) { refreshed.push([this, args]); return 'original result' }
    const context = { idempotencyKey: 'session', onRefresh: original, show() {}, hide() {} }
    const state = harness(utility)
    state.receive(payload({ browserContext: context, contextId: 'session' })); state.page.onShow()
    state.page.handleFullscreenChange({ detail: { fullScreen: true } })
    const wrapped = context.onRefresh
    assert.equal(wrapped({ version: 2 }), 'original result')
    assert.equal(refreshed.length, 1)
    assert.equal(refreshed[0][0], context)
    assert.deepEqual(refreshed[0][1], [{ version: 2 }])
    assert.equal(state.page.data.available, true)
    assert.equal(state.methods().includes('navigateBack'), false)
    state.page.handleVideoPlay()
    assert.equal(state.page.data.playing, false)
    state.page.handleFullscreenChange({ detail: { fullScreen: false } })
    assert.equal(state.methods().includes('navigateBack'), true)
    state.page.onUnload()
    assert.equal(context.onRefresh, original)
    wrapped(); assert.equal(refreshed.length, 1)
  })

  test(`${packageName} invalid or replaced visitor context is rejected when returning to the page`, () => {
    for (const invalidate of [context => { context.isInvalid = () => true }, context => { context.isDisposed = () => true },
      context => { context.idempotencyKey = 'replacement' }]) {
      const context = { idempotencyKey: 'session', show() {}, hide() {} }
      const state = harness(utility)
      state.receive(payload({ browserContext: context, contextId: 'session' })); state.page.onShow(); state.page.onHide()
      invalidate(context)
      state.page.onShow()
      assert.equal(state.methods().includes('navigateBack'), true)
      assert.equal(state.methods().includes('pause'), true)
      state.page.onUnload()
    }
  })
}

test('both subpackages retain identical player implementation and independent webview pages', () => {
  const read = (packageName, relative) => fs.readFileSync(path.join(__dirname, `../pages/${packageName}/${relative}`), 'utf8')
  for (const relative of ['utils/portfolio-video-player-page.js', 'video-player/video-player.js',
    'video-player/video-player.json', 'video-player/video-player.wxml', 'video-player/video-player.wxss']) {
    assert.equal(read(packages[0], relative), read(packages[1], relative))
  }
  for (const packageName of packages) {
    const config = JSON.parse(read(packageName, 'video-player/video-player.json'))
    assert.equal(config.renderer, 'webview')
    assert.equal(config.navigationStyle, 'default')
    assert.equal(config.navigationBarTitleText, '视频播放')
    assert.equal(config.navigationBarBackgroundColor, '#000000')
    assert.equal(config.navigationBarTextStyle, 'white')
    assert.equal(config.pageOrientation, 'portrait')
    assert.equal(config.disableScroll, true)
    assert.equal(config.enablePassiveEvent, false)
    assert.deepEqual(config.usingComponents, {})
    const markup = read(packageName, 'video-player/video-player.wxml')
    assert.match(markup, /<video\b[^>]*wx:if="\{\{available\}\}"/)
    assert.match(markup, /direction="\{\{90\}\}"/)
    for (const control of ['controls', 'show-fullscreen-btn', 'show-play-btn', 'show-center-play-btn', 'autoplay']) {
      assert.match(markup, new RegExp(`${control}="\\{\\{true\\}\\}"`))
    }
    assert.match(markup, /object-fit="contain"/)
    assert.match(markup, /bindfullscreenchange="handleFullscreenChange"/)
    assert.doesNotMatch(markup, /bindtap="handleEnterFullscreen"|bindtap="handleTogglePlayback"|portfolio-video-toolbar|portfolio-video-actions|portfolio-video-back/)
    assert.match(markup, /<view\b[^>]*class="portfolio-video-title"[^>]*>\{\{title\}\}<\/view>/)
    assert.match(markup, /<view wx:elif="\{\{!available\}\}"[^>]*>[\s\S]*?<button\b[^>]*bindtap="handleBack"[^>]*>返回作品集<\/button><\/view>/)
    assert.equal((markup.match(/<button\b/g) || []).length, 1)
    assert.doesNotMatch(markup, /catchtouch|catchtap|<scroll-view|<cover-view|hidden="\{\{fullscreen\}\}"[^>]*src=/)
    const styles = read(packageName, 'video-player/video-player.wxss')
    assert.doesNotMatch(styles, /pointer-events|overflow|transform|portfolio-video-toolbar|portfolio-video-actions|portfolio-video-back/)
    assert.match(read(packageName, 'video-player/video-player.js'), /Page\(createVideoPlayerPage\(\{ wxApi: wx \}\)\)/)
  }
})
