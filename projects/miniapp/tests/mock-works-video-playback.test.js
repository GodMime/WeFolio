const assert = require('node:assert/strict')
const fs = require('node:fs')
const { createRequire } = require('node:module')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')

const ROOT = path.join(__dirname, '..')
const PAGE_PATH = path.join(ROOT, 'pages/mock/works/works.js')
const PLAYER_ROUTE = '/pages/mock/video-player/video-player'
const { MOCK_WORK_LIBRARY } = require('../pages/mock/utils/mock-experience')
const VIDEO = MOCK_WORK_LIBRARY.works.find(work => work.mediaType === 'VIDEO')
const tapVideo = page => page.handleWorkPreviewTap({ currentTarget: { dataset: { id: VIDEO.id } } })

// 调用真实 mock 素材库入口，断言视频不再与 Skyline 底栏同页挂载。
function loadPage() {
  const calls = []
  const patches = []
  let page
  vm.runInNewContext(fs.readFileSync(PAGE_PATH, 'utf8'), {
    require: createRequire(PAGE_PATH),
    Page(value) { page = value },
    wx: {
      navigateTo(options) { calls.push({ action: 'navigateTo', options }) },
      showToast(options) { calls.push({ action: 'toast', options }) },
      request() { assert.fail('mock 播放不能访问后端') },
      createVideoContext() { assert.fail('来源页不能操控独立播放器') }
    }
  }, { filename: PAGE_PATH })
  page.data = structuredClone(page.data)
  page.setData = function (patch) { patches.push(patch); Object.assign(this.data, patch) }
  page.mockAudio = Object.fromEntries(['pause', 'hide', 'show', 'destroy'].map(action => [action,
    () => calls.push({ action: `audio:${action}` })]))
  return { page, calls, patches }
}

function finishNavigation(call) {
  const events = []
  call.options.success({ eventChannel: { emit(event, payload) { events.push({ event, payload }) } } })
  return events
}

test('mock 作品缩略图和播放图标入口使用独立播放页，不挂载底栏上的视频弹层', async () => {
  const { page, calls, patches } = loadPage()
  const result = tapVideo(page)
  assert.equal(Boolean(page.data.videoPreviewVisible), false, 'mock 视频不能继续在 Skyline 底栏页面内播放')
  const navigation = calls.find(call => call.action === 'navigateTo')
  assert.ok(navigation)
  assert.equal(navigation.options.url, PLAYER_ROUTE)
  assert.ok(calls.findIndex(call => call.action === 'audio:pause') < calls.indexOf(navigation))
  assert.deepEqual(finishNavigation(navigation), [{ event: 'mockVideoPlayer', payload: {
    url: VIDEO.mediaUrl, poster: VIDEO.coverUrl, title: VIDEO.title
  } }])
  assert.equal(await result, true)
  page.onHide()
  page.onShow()
  assert.equal(patches.some(patch => patch.videoPreview || patch.videoPreviewVisible), false)
})

test('mock 视频返回保留搜索和筛选结果，连续点击不叠加页面，返回后可重播', async () => {
  const { page, calls } = loadPage()
  page.handleSearchInput({ detail: { value: VIDEO.title } })
  const visibleWorks = page.data.visibleWorks
  const first = tapVideo(page)
  assert.equal(await tapVideo(page), false)
  const navigations = () => calls.filter(call => call.action === 'navigateTo')
  assert.equal(navigations().length, 1)
  finishNavigation(navigations()[0])
  assert.equal(await first, true)
  page.onHide()
  assert.equal(await tapVideo(page), false)
  page.onShow()
  assert.equal(page.data.keyword, VIDEO.title)
  assert.equal(page.data.visibleWorks, visibleWorks)
  const second = tapVideo(page)
  assert.equal(navigations().length, 2)
  finishNavigation(navigations()[1])
  assert.equal(await second, true)
})

test('mock 播放页打开失败允许重试，不回退旧弹层', async () => {
  const { page, calls, patches } = loadPage()
  const first = tapVideo(page)
  calls.find(call => call.action === 'navigateTo').options.fail({ errMsg: 'navigateTo:fail' })
  assert.equal(await first, false)
  assert.equal(calls.filter(call => call.action === 'toast').length, 1)
  const second = tapVideo(page)
  const navigations = calls.filter(call => call.action === 'navigateTo')
  assert.equal(navigations.length, 2)
  finishNavigation(navigations[1])
  assert.equal(await second, true)
  assert.equal(patches.some(patch => patch.videoPreview || patch.videoPreviewVisible), false)
})

test('mock 旧导航的迟到失败不能解除新播放会话的重复点击保护', async () => {
  const { page, calls } = loadPage()
  const first = tapVideo(page)
  const oldNavigation = calls.find(call => call.action === 'navigateTo')
  page.onHide()
  page.onShow()
  const current = tapVideo(page)
  oldNavigation.options.fail({ errMsg: 'navigateTo:fail cancel' })
  assert.equal(await first, false)
  assert.equal(await tapVideo(page), false)
  const navigations = calls.filter(call => call.action === 'navigateTo')
  assert.equal(navigations.length, 2)
  finishNavigation(navigations[1])
  assert.equal(await current, true)
})

test('mock 来源页卸载后迟到的失败不回写界面，也不再响应播放', async () => {
  const { page, calls, patches } = loadPage()
  const pending = tapVideo(page)
  const navigation = calls.find(call => call.action === 'navigateTo')
  page.onUnload()
  patches.length = 0
  navigation.options.fail({ errMsg: 'navigateTo:fail cancel' })
  assert.equal(await pending, false)
  assert.equal(await tapVideo(page), false)
  assert.equal(calls.filter(call => call.action === 'navigateTo').length, 1)
  assert.equal(calls.filter(call => call.action === 'audio:destroy').length, 1)
  assert.deepEqual(patches, [])
})

test('mock 作品播放路由和页面均独立于正式分包，源页面不再包含视频弹层', () => {
  const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'))
  assert.ok(app.subPackages.find(pkg => pkg.root === 'pages/mock').pages.includes('video-player/video-player'))
  const config = JSON.parse(fs.readFileSync(path.join(ROOT, `${PLAYER_ROUTE}.json`), 'utf8'))
  assert.equal(config.renderer, 'webview')
  assert.equal(config.navigationStyle, 'default')
  const markup = fs.readFileSync(path.join(ROOT, 'pages/mock/works/works.wxml'), 'utf8')
  assert.doesNotMatch(markup, /<video\b|video-preview-|mockWorkVideo/)
  assert.equal((markup.match(/catchtap="handleWorkPreviewTap"/g) || []).length, 2)
})
