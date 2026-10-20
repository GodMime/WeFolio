const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { createRequire } = require('node:module')
const test = require('node:test')
const vm = require('node:vm')

const PAGE_PATH = path.join(__dirname, '../pages/mock/portfolio-standard-preview/portfolio-standard-preview.js')
const PLAYER_ROUTE = '/pages/mock/video-player/video-player'
const WORK = { workId: 107, mediaType: 'VIDEO', mediaUrl: 'https://example.test/mock.mp4', coverUrl: 'https://example.test/mock.jpg', title: '体验视频' }

function loadPage(type = 'WORK_LIST') {
  let page
  const calls = [], patches = []
  vm.runInNewContext(fs.readFileSync(PAGE_PATH, 'utf8'), {
    Page(value) { page = value }, require: createRequire(PAGE_PATH), setTimeout, clearTimeout,
    wx: {
      navigateTo(options) { calls.push({ action: 'navigate', options }) },
      showToast(options) { calls.push({ action: 'toast', options }) },
      request() { assert.fail('体验播放不能请求后端') },
      createVideoContext() { return { pause() { calls.push({ action: 'pauseInline' }) } } }
    }
  }, { filename: PAGE_PATH })
  page.data = structuredClone(page.data)
  page.data.portfolio = { activeMenuKey: 'works-menu', activeComponents: [{ componentKey: 'videos', componentType: type,
    ...(type === 'VIDEO_CAROUSEL' ? { works: [WORK] } : { groups: [{ works: [WORK] }] }) }] }
  page.setData = patch => { patches.push(patch); Object.assign(page.data, patch) }
  page.previewLoaded = true
  page.mockAudio = { pause() { calls.push({ action: 'pauseAudio' }) }, hide() {}, show() {}, destroy() {}, toggle() { calls.push({ action: 'toggleAudio' }) } }
  return { page, calls, patches }
}

function finishNavigation(navigation) {
  const events = []
  navigation.options.success({ eventChannel: { emit(event, payload) { events.push({ event, payload }) } } })
  return events
}

const playEvent = () => ({ detail: { componentKey: 'videos', workId: WORK.workId, mediaUrl: 'https://untrusted.test/forged.mp4' } })

for (const type of ['WORK_LIST', 'WORK_GRID', 'VIDEO_CAROUSEL']) {
  test(`${type} 视频进入 mock 独立页，使用本地作品地址并在返回时保留当前菜单`, async () => {
    const { page, calls, patches } = loadPage(type)
    page.data.activeSingleWorkVideoKey = 'inline-work'
    const portfolio = page.data.portfolio
    const pending = page.handleWorkTap(playEvent())
    const navigation = calls.find(call => call.action === 'navigate')
    assert.ok(navigation, '不能继续使用 Skyline 视频弹层')
    assert.equal(navigation.options.url, PLAYER_ROUTE)
    assert.ok(calls.findIndex(call => call.action === 'pauseAudio') < calls.indexOf(navigation))
    assert.equal(page.data.activeSingleWorkVideoKey, '')
    assert.equal(page.data.mediaActive, false)
    assert.deepEqual(finishNavigation(navigation), [{ event: 'mockVideoPlayer', payload: {
      url: WORK.mediaUrl, poster: WORK.coverUrl, title: WORK.title
    } }])
    assert.equal(await pending, true)
    page.onHide(); page.onShow()
    assert.equal(page.data.portfolio, portfolio)
    assert.equal(page.data.portfolio.activeMenuKey, 'works-menu')
    assert.equal(page.data.mediaActive, true)
    assert.equal(patches.some(patch => patch.videoPreview || patch.videoPreviewVisible), false)
    page.onUnload()
  })
}

test('导航期间禁止重复播放及背景音，失败可重试，隐藏期间不能重开', async () => {
  const { page, calls } = loadPage()
  const first = page.handleWorkTap(playEvent())
  assert.equal(await page.handleWorkTap(playEvent()), false)
  page.handleToggleAudio()
  assert.equal(calls.some(call => call.action === 'toggleAudio'), false)
  calls.find(call => call.action === 'navigate').options.fail({ errMsg: 'navigateTo:fail' })
  assert.equal(await first, false)
  assert.equal(page.data.mediaActive, true)
  const second = page.handleWorkTap(playEvent())
  const navigations = calls.filter(call => call.action === 'navigate')
  assert.equal(navigations.length, 2)
  finishNavigation(navigations[1]); assert.equal(await second, true)
  page.onHide()
  assert.equal(await page.handleWorkTap(playEvent()), false)
  page.onShow()
  const third = page.handleWorkTap(playEvent())
  finishNavigation(calls.filter(call => call.action === 'navigate')[2])
  assert.equal(await third, true)
  page.onUnload()
})

test('旧失败不清理新播放会话，卸载后的失败不更新页面', async () => {
  const { page, calls, patches } = loadPage()
  const old = page.handleWorkTap(playEvent())
  const first = calls.find(call => call.action === 'navigate')
  page.onHide(); page.onShow()
  const current = page.handleWorkTap(playEvent())
  first.options.fail({ errMsg: 'navigateTo:fail cancel' })
  assert.equal(await old, false)
  assert.equal(await page.handleWorkTap(playEvent()), false)
  page.onUnload(); patches.length = 0
  calls.filter(call => call.action === 'navigate')[1].options.fail({ errMsg: 'navigateTo:fail cancel' })
  assert.equal(await current, false)
  assert.equal(await page.handleWorkTap(playEvent()), false)
  assert.equal(patches.length, 0)
})
