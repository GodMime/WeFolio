const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { createRequire } = require('node:module')
const vm = require('node:vm')
const test = require('node:test')

const { normalizeVideoAiContent } = require('../pages/works/utils/work-video-ai')

const fullContent = {
  hasContent: true,
  summary: { summary: '视频摘要', observations: ['画面要点'], limitations: ['远景不清晰'] },
  audioText: '音频识别文字',
  frames: [{ timestampMs: 62500, width: 1280, height: 720, url: 'https://cdn/frame.jpg?sign=one' }]
}

test('AI 展示归一化保持音频空值语义，仅有效内容显示入口', () => {
  const empty = normalizeVideoAiContent({ hasContent: false, audioText: null })
  assert.equal(empty.hasContent, false)
  assert.equal(empty.audioText, null)
  assert.equal(empty.audioEmptyText, '暂未生成音频文字')
  const silent = normalizeVideoAiContent({ hasContent: false, audioText: '' })
  assert.equal(silent.audioText, '')
  assert.equal(silent.audioEmptyText, '未识别到可转写文字')
  assert.equal(normalizeVideoAiContent({ hasContent: true, audioText: ' \n ' }).hasContent, false)
  assert.equal(normalizeVideoAiContent({ hasContent: true, summary: { limitations: ['无正文'] } }).hasContent, false)
  assert.equal(normalizeVideoAiContent({ hasContent: true, summary: { observations: ['仅观察要点'] } }).hasContent, true)
  assert.equal(normalizeVideoAiContent({ hasContent: true, audioText: '只有音频' }).hasContent, true)
  assert.equal(normalizeVideoAiContent({ hasContent: true, frames: fullContent.frames }).hasContent, true)
  assert.equal(normalizeVideoAiContent({ ...fullContent, hasContent: false }).hasContent, false)
})

test('AI 展示保留全文与帧时间，复制摘要包含观察要点和识别局限', () => {
  const input = structuredClone(fullContent)
  const content = normalizeVideoAiContent(input)
  assert.equal(content.summary.summary, '视频摘要')
  assert.match(content.summaryCopyText, /视频摘要[\s\S]*画面要点[\s\S]*远景不清晰/)
  assert.equal(content.frames[0].timeText, '01:02')
  assert.equal(content.frames[0].url, fullContent.frames[0].url)
  assert.deepEqual(input, fullContent)
})

function deferred() {
  let resolve
  let reject
  const promise = new Promise((onResolve, onReject) => { resolve = onResolve; reject = onReject })
  return { promise, resolve, reject }
}

function flush() {
  return new Promise((resolve) => setImmediate(resolve))
}

// 隔离 Page 与微信 API，并用虚拟时钟验证 999 / 1000 毫秒边界。
function loadPage(fakeRequest, wxOverrides = {}) {
  const pagePath = path.join(__dirname, '../pages/works/works.js')
  const pageRequire = createRequire(pagePath)
  let page
  let now = 0
  let timerId = 0
  const timers = new Map()
  const wxApi = { showToast() {}, ...wxOverrides }
  vm.runInNewContext(fs.readFileSync(pagePath, 'utf8'), {
    require(name) {
      if (name === '../../utils/request') return { request: fakeRequest }
      if (name === '../../utils/session') return { handleMaintainerAuthRequired() {}, hasLocalToken: () => true }
      return pageRequire(name)
    },
    Page(definition) { page = definition },
    wx: wxApi,
    setTimeout(callback, delay) {
      const id = ++timerId
      timers.set(id, { at: now + delay, callback })
      return id
    },
    clearTimeout(id) { timers.delete(id) }
  }, { filename: pagePath })
  page.data = structuredClone(page.data)
  page.setData = function(patch) {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.')
      let target = this.data
      for (const part of parts.slice(0, -1)) target = target[part]
      target[parts.at(-1)] = value
    }
  }
  page.advance = function(milliseconds) {
    now += milliseconds
    for (const [id, timer] of timers) {
      if (timer.at > now) continue
      timers.delete(id)
      timer.callback()
    }
  }
  return page
}

function video(id = 18) {
  return { id, mediaType: 'VIDEO', title: '原作品标题', description: '原作品说明', tags: [] }
}

const event = (dataset) => ({ currentTarget: { dataset } })

test('打开弹层仅查询一次，每次展开固定一秒且收起、切换标签立即响应', async () => {
  const calls = []
  const page = loadPage(async (options) => { calls.push(options); return fullContent })
  page.openVideoEditSheet(video())
  assert.equal(page.data.videoAiContent.hasContent, false)
  await flush()
  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, '/api/mine/works/18/ai-content')
  assert.equal(calls[0].method, 'GET')
  assert.equal(page.data.videoAiContent.hasContent, true)
  page.handleToggleVideoAi()
  assert.equal(page.data.videoAiLoading, true)
  page.advance(999)
  assert.equal(page.data.videoAiLoading, true)
  page.advance(1)
  assert.equal(page.data.videoAiLoading, false)
  page.handleVideoAiTab(event({ tab: 'frames' }))
  assert.equal(page.data.videoAiTab, 'frames')
  page.handleToggleVideoAi()
  assert.equal(page.data.videoAiExpanded, false)
  page.handleToggleVideoAi()
  assert.equal(page.data.videoAiTab, 'summary')
  assert.equal(page.data.videoAiLoading, true)
  page.advance(999)
  assert.equal(page.data.videoAiLoading, true)
  page.handleToggleVideoAi()
  page.advance(1)
  assert.equal(page.data.videoAiLoading, false)
  assert.equal(page.data.videoAiExpanded, false)
  assert.equal(calls.length, 1)
})

test('关闭、切换作品及卸载后丢弃旧查询，关闭后重开重新查询', async () => {
  const requests = []
  const page = loadPage(() => { const pending = deferred(); requests.push(pending); return pending.promise })
  page.openVideoEditSheet(video(18))
  page.openVideoEditSheet(video(19))
  requests[1].resolve({ hasContent: true, audioText: '第二个作品' })
  await flush()
  requests[0].resolve(fullContent)
  await flush()
  assert.equal(page.data.videoAiContent.audioText, '第二个作品')
  page.handleToggleVideoAi()
  page.handleCloseVideoEditor()
  page.advance(1000)
  assert.equal(page.data.videoAiExpanded, false)
  assert.equal(page.data.videoAiContent.hasContent, false)
  page.openVideoEditSheet(video(19))
  assert.equal(requests.length, 3)
  page.onUnload()
  requests[2].resolve(fullContent)
  await flush()
  assert.equal(page.data.videoAiContent.hasContent, false)
})

test('全部无内容不展示，失败仅手动重试且不影响原表单', async () => {
  let calls = 0
  const page = loadPage(async () => {
    calls += 1
    if (calls === 1) throw new Error('网络断开')
    return { hasContent: false, summary: null, audioText: '', frames: [] }
  })
  page.openVideoEditSheet(video())
  await flush()
  assert.equal(page.data.videoAiContent.hasContent, false)
  assert.ok(page.data.videoAiErrorText)
  assert.equal(page.data.videoEditForm.title, '原作品标题')
  page.advance(10000)
  assert.equal(calls, 1)
  await page.handleRetryVideoAi()
  assert.equal(calls, 2)
  assert.equal(page.data.videoAiErrorText, '')
  assert.equal(page.data.videoAiContent.hasContent, false)
  page.handleToggleVideoAi()
  assert.equal(page.data.videoAiExpanded, false)
})

test('关键帧失败只显示重试，重新查询地址后可预览且不修改封面', async () => {
  let calls = 0
  let preview
  const page = loadPage(async () => {
    calls += 1
    return { ...fullContent, frames: [{ ...fullContent.frames[0], url: `https://cdn/frame.jpg?sign=${calls}` }] }
  }, { previewImage(options) { preview = options } })
  page.openVideoEditSheet(video())
  await flush()
  const originalForm = structuredClone(page.data.videoEditForm)
  page.handleToggleVideoAi()
  page.advance(1000)
  page.handleVideoAiTab(event({ tab: 'frames' }))
  page.handleVideoAiFrameError(event({ index: 0, url: page.data.videoAiContent.frames[0].url }))
  assert.equal(page.data.videoAiFrameError, true)
  assert.equal(calls, 1)
  await page.handleRetryVideoAi()
  assert.equal(page.data.videoAiTab, 'frames')
  assert.equal(page.data.videoAiExpanded, true)
  assert.equal(page.data.videoAiFrameError, false)
  page.handlePreviewVideoAiFrame(event({ index: 0 }))
  assert.equal(preview.current, 'https://cdn/frame.jpg?sign=2')
  assert.deepEqual(structuredClone(page.data.videoEditForm), originalForm)
})

test('复制只读取 AI 数据，完成请求始终不包含 AI 字段', async () => {
  const requests = []
  let copied
  const page = loadPage(async (options) => { requests.push(options); return options.url.endsWith('/ai-content') ? fullContent : {} }, {
    setClipboardData(options) { copied = options.data }
  })
  page.openVideoEditSheet(video())
  await flush()
  const originalForm = structuredClone(page.data.videoEditForm)
  page.handleCopyVideoAi(event({ type: 'summary' }))
  assert.match(copied, /视频摘要/)
  assert.deepEqual(structuredClone(page.data.videoEditForm), originalForm)
  // 即使表单被额外塞入 AI 字段，原保存白名单仍不能把它们送出。
  Object.assign(page.data.videoEditForm, { summary: fullContent.summary, audioText: '改写', frames: [], videoAudioText: '改写', videoSummaryJson: '{}', videoFramesJson: '[]' })
  page.loadWorks = async () => {}
  await page.handleConfirmVideoEdit()
  const update = requests.find((request) => request.method === 'PUT')
  assert.ok(update)
  assert.deepEqual(Object.keys(update.data).sort(), ['description', 'title'])
  assert.equal(page.data.videoAiContent.hasContent, false)
})

test('AI 区域只使用展示节点，关键帧仅在标签打开后挂载', () => {
  const wxml = fs.readFileSync(path.join(__dirname, '../pages/works/works.wxml'), 'utf8')
  const start = wxml.indexOf('<!-- AI 内容只读展示')
  const end = wxml.indexOf('<!-- AI 内容展示结束 -->', start)
  assert.ok(start >= 0 && end > start)
  const ai = wxml.slice(start, end)
  assert.doesNotMatch(ai, /<(?:input|textarea|editor)\b|contenteditable|bindinput|bindchange|handle(?:Confirm|Export|Choose).*Cover/)
  assert.match(ai, /videoAiTab === 'frames'/)
  assert.match(ai, /bindtap="handlePreviewVideoAiFrame"/)
  assert.match(ai, /binderror="handleVideoAiFrameError"/)
  assert.match(wxml, /wx:if="\{\{videoAiContent.hasContent\}\}"[^>]*class="video-ai-trigger/)
})
