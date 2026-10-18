const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')
const { createHash } = require('node:crypto')

const flush = () => new Promise(resolve => setImmediate(resolve))
const event = dataset => ({ currentTarget: { dataset } })
const frameCdnBase = 'https://cdn.test/WFOWNER/others/video-frames/18/'
const frames = [0, 18000, 24000].map(timestampMs => ({ timestampMs, url: `${frameCdnBase}${timestampMs}.jpg` }))
const work = { id: '18', mediaType: 'VIDEO', title: '作品', description: '原说明', coverUrl: 'https://covers/old.jpg', width: 1920, height: 1080, durationMs: 60000, tags: [] }

function setup() {
  const requests = []
  const removed = []
  const uploads = []
  let download
  let page
  let content = { hasContent: true, frames }
  let saveFails = false
  let uploadFails = false
  let missing = false
  let saveGate
  const sourceBytes = new Uint8Array([7, 8, 9]).buffer
  const wxApi = {
    showToast() {},
    getFileSystemManager: () => ({
      statSync() { if (missing) throw Error('missing'); return { size: 90000 } },
      readFile: ({ success }) => success({ data: sourceBytes }),
      unlink: ({ filePath }) => removed.push(filePath)
    }),
    getImageInfo: ({ success }) => success({ width: 640, height: 360, type: 'jpeg' }),
    downloadFile(options) { download = options; return { abort() {} } },
    uploadFile(options) {
      uploads.push(options)
      queueMicrotask(() => uploadFails ? options.fail({ errMsg: 'upload failed' }) : options.success({ statusCode: 204 }))
      return { onProgressUpdate(callback) { callback({ progress: 60 }) }, abort() {} }
    }
  }
  const pagePath = path.join(__dirname, '../pages/works/works.js')
  const localRequire = createRequire(pagePath)
  vm.runInNewContext(fs.readFileSync(pagePath, 'utf8'), {
    Page(definition) { page = definition }, wx: wxApi, setTimeout, clearTimeout,
    require(name) {
      if (name === '../../utils/request') return { request: async options => {
        requests.push(options)
        if (options.url.endsWith('/ai-content')) return content
        if (options.method === 'POST') return { taskId: '302', uploadUrl: 'https://upload/new-cover', formData: { key: 'work/video/new-cover.jpg' } }
        if (saveGate) await saveGate
        if (saveFails) throw Error('作品保存失败，请重试')
        return { work: { ...work, coverUrl: 'https://covers/new.jpg' } }
      } }
      if (name === '../../utils/session') return { hasLocalToken: () => true, handleMaintainerAuthRequired() {} }
      return localRequire(name)
    }
  })
  page.data = structuredClone(page.data)
  page.setData = function(patch, callback) {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.')
      let target = this.data
      for (const part of parts.slice(0, -1)) target = target[part]
      target[parts.at(-1)] = value
    }
    if (callback) callback()
  }
  page.uploadTasks = {}
  page.loadWorks = async () => {}
  return { page, wxApi, requests, uploads, removed, sourceBytes, get download() { return download }, set content(value) { content = value }, set saveGate(value) { saveGate = value }, set saveFails(value) { saveFails = value }, set uploadFails(value) { uploadFails = value }, set missing(value) { missing = value } }
}

async function open(env) { env.page.openVideoEditSheet(work); await flush() }
function select(page, index = 1) {
  page.handleOpenKeyframeCover()
  page.handleSelectKeyframeCover(event({ key: page.data.videoAiContent.frames[index].key }))
}
function previewLoaded(page) {
  page.handleKeyframePreviewLoad(event({ token: page.data.videoKeyframePreviewToken }))
}
async function apply(env) {
  select(env.page)
  previewLoaded(env.page)
  const pending = env.page.handleApplyKeyframeCover()
  env.download.success({ statusCode: 200, tempFilePath: 'wxfile://chosen' })
  await pending
}

test('仅有可用帧显示入口，全部失败、查询中、刷新无帧均隐藏', async () => {
  const env = setup()
  env.page.openVideoEditSheet(work)
  assert.equal(env.page.data.videoKeyframeAvailable, false)
  await flush()
  assert.equal(env.page.data.videoKeyframeAvailable, true)
  env.page.data.videoAiContent.frames.forEach((frame, index) => env.page.handleVideoAiFrameError(event({ index, url: frame.url, key: frame.key })))
  assert.equal(env.page.data.videoKeyframeAvailable, false)
  env.content = { hasContent: true, audioText: '只有音频', frames: [] }
  await env.page.handleRetryVideoAi()
  assert.equal(env.page.data.videoKeyframeAvailable, false)
})

test('单选必须等预览完成；使用此帧只下载，取消候选保留表单和封面', async () => {
  const env = setup()
  await open(env)
  const baseline = structuredClone(env.page.data.videoEditForm)
  select(env.page)
  await env.page.handleApplyKeyframeCover()
  assert.equal(env.download, undefined)
  env.page.handleCloseKeyframeCover()
  assert.deepEqual(structuredClone(env.page.data.videoEditForm), baseline)
  await apply(env)
  assert.equal(env.download.url, frames[1].url)
  assert.equal(env.download.header, undefined)
  assert.equal(env.requests.length, 1)
  assert.equal(env.uploads.length, 0)
  assert.equal(env.page.data.videoEditForm.previewPath, 'wxfile://chosen')
  assert.equal(env.page.data.videoEditForm.coverFrameSelected, false)
  assert.equal(env.page.data.videoEditForm.coverDraftSource, 'keyframe')
  assert.equal(env.page.data.videoEditForm.width, 1920)
})

test('完成时独立上传本地文件，更新仅传 coverTaskId，不传关键帧地址或时间', async () => {
  const env = setup()
  await open(env)
  await apply(env)
  await env.page.handleConfirmVideoEdit()
  const ticket = env.requests.find(item => item.method === 'POST')
  const update = env.requests.find(item => item.method === 'PUT')
  assert.equal(ticket.data.sha256, createHash('sha256').update(new Uint8Array(env.sourceBytes)).digest('hex'))
  assert.equal(ticket.data.width, 640)
  assert.equal(ticket.data.height, 360)
  assert.equal(env.uploads[0].filePath, 'wxfile://chosen')
  assert.equal(env.uploads[0].formData.key, 'work/video/new-cover.jpg')
  assert.deepEqual(Object.keys(update.data).sort(), ['coverTaskId', 'description', 'title'])
  assert.equal(update.data.coverTaskId, 302)
  assert.ok(!JSON.stringify(update.data).includes(frameCdnBase))
  assert.equal(env.page.data.videoEditSheetVisible, false)
  assert.ok(env.removed.includes('wxfile://chosen'))
})

test('准备失败保留原草稿；取消后迟到结果不会修改下一作品', async () => {
  const env = setup()
  await open(env)
  await apply(env)
  select(env.page, 2)
  previewLoaded(env.page)
  const failing = env.page.handleApplyKeyframeCover()
  env.download.fail({ errMsg: 'network' })
  await failing
  assert.equal(env.page.data.videoEditForm.localCoverPath, 'wxfile://chosen')
  const pending = env.page.handleApplyKeyframeCover()
  const oldDownload = env.download
  env.page.handleCloseKeyframeCover()
  env.page.openVideoEditSheet({ ...work, id: '19' })
  oldDownload.success({ statusCode: 200, tempFilePath: 'wxfile://late' })
  await pending
  assert.equal(env.page.data.videoEditForm.id, '19')
  assert.equal(env.page.data.videoEditForm.localCoverPath, '')
  assert.ok(env.removed.includes('wxfile://late'))
})

test('刷新清空候选且旧图片事件失效；已下载草稿不依赖原链接', async () => {
  const env = setup()
  await open(env)
  await apply(env)
  select(env.page)
  const token = env.page.data.videoKeyframePreviewToken
  const oldFrame = env.page.data.videoAiContent.frames[1]
  env.content = { hasContent: false, frames: [] }
  await env.page.handleRetryVideoAi()
  env.page.handleKeyframePreviewLoad(event({ token }))
  env.page.handleSelectKeyframeCover(event({ key: oldFrame.key }))
  assert.equal(env.page.data.videoKeyframeCandidate, null)
  env.page.handleCloseKeyframeCover()
  assert.equal(env.page.data.videoEditForm.localCoverPath, 'wxfile://chosen')
  await env.page.handleConfirmVideoEdit()
  assert.ok(env.requests.some(item => item.method === 'PUT'))
})

test('撤销只恢复封面，保留标题说明标签；手工选帧未应用不丢失关键帧草稿', async () => {
  const env = setup()
  await open(env)
  await apply(env)
  env.page.data.videoEditForm.title = '新标题'
  env.page.data.videoEditForm.description = '新说明'
  env.page.data.videoEditForm.tagOptions = [{ id: 'tag', selected: true }]
  await env.page.handleStartVideoCoverEdit()
  env.page.handleCancelVideoCoverEdit()
  assert.equal(env.page.data.videoEditForm.localCoverPath, 'wxfile://chosen')
  env.page.handleUndoVideoCover()
  assert.equal(env.page.data.videoEditForm.previewPath, work.coverUrl)
  assert.equal(env.page.data.videoEditForm.title, '新标题')
  assert.equal(env.page.data.videoEditForm.description, '新说明')
  assert.equal(env.page.data.videoEditForm.tagOptions[0].selected, true)
  assert.ok(env.removed.includes('wxfile://chosen'))
})

test('上传失败不保存，保存失败可重试，期间禁止关闭和重复操作', async () => {
  const env = setup()
  await open(env)
  await apply(env)
  env.uploadFails = true
  const pending = env.page.handleConfirmVideoEdit()
  env.page.handleCloseVideoEditor()
  env.page.handleUndoVideoCover()
  env.page.handleOpenKeyframeCover()
  await env.page.handleConfirmVideoEdit()
  await pending
  assert.equal(env.page.data.videoEditSheetVisible, true)
  assert.equal(env.page.data.videoKeyframePickerVisible, false)
  assert.equal(env.requests.filter(item => item.method === 'PUT').length, 0)
  env.uploadFails = false
  env.saveFails = true
  await env.page.handleConfirmVideoEdit()
  assert.equal(env.page.data.videoEditForm.localCoverPath, 'wxfile://chosen')
  assert.deepEqual(env.removed, [])
  env.saveFails = false
  await env.page.handleConfirmVideoEdit()
  assert.equal(env.page.data.videoEditSheetVisible, false)
})

test('本地草稿文件丢失、标题为空时禁止上传与保存', async () => {
  const env = setup()
  await open(env)
  await apply(env)
  env.missing = true
  await env.page.handleConfirmVideoEdit()
  assert.match(env.page.data.videoEditErrorText, /封面文件已失效/)
  assert.equal(env.requests.length, 1)
  env.page.data.videoEditForm.title = ''
  await env.page.handleConfirmVideoEdit()
  assert.equal(env.requests.length, 1)
})

test('压缩后上传元数据和摘要匹配最终字节，重试不重复压缩且清理中间文件', async () => {
  const env = setup()
  const compressedBytes = new Uint8Array([40, 41, 42, 43]).buffer
  let compressCount = 0
  env.wxApi.getFileSystemManager = () => ({
    statSync: filePath => ({ size: filePath === 'wxfile://small' ? 80000 : 220000 }),
    readFile: ({ success }) => success({ data: compressedBytes }),
    unlink: ({ filePath }) => env.removed.push(filePath)
  })
  env.wxApi.compressImage = ({ success }) => {
    compressCount += 1
    success({ tempFilePath: compressCount === 1 ? 'wxfile://large' : 'wxfile://small' })
  }
  env.wxApi.getImageInfo = ({ src, success }) => success({ type: src === 'wxfile://chosen' ? 'png' : 'jpeg', width: src === 'wxfile://small' ? 320 : 1280, height: src === 'wxfile://small' ? 180 : 720 })
  await open(env)
  await apply(env)
  env.saveFails = true
  await env.page.handleConfirmVideoEdit()
  const first = env.requests.find(item => item.method === 'POST').data
  assert.equal(first.sha256, createHash('sha256').update(new Uint8Array(compressedBytes)).digest('hex'))
  assert.equal(first.mimeType, 'image/jpeg')
  assert.equal(first.fileName, 'cover.jpg')
  assert.equal(first.width, 320)
  assert.equal(first.height, 180)
  assert.equal(first.fileSize, 80000)
  assert.equal(env.uploads[0].filePath, 'wxfile://small')
  assert.deepEqual(env.removed, ['wxfile://large'])
  env.saveFails = false
  await env.page.handleConfirmVideoEdit()
  assert.equal(compressCount, 2)
  const second = env.requests.filter(item => item.method === 'POST')[1].data
  assert.deepEqual(structuredClone(second), structuredClone(first))
  assert.ok(env.removed.includes('wxfile://small'))
  assert.ok(env.removed.includes('wxfile://chosen'))
})

test('预览迟到事件不启用另一候选，返回保留表单滚动和 AI 页签', async () => {
  const env = setup()
  await open(env)
  env.page.data.videoAiExpanded = true
  env.page.data.videoAiTab = 'frames'
  env.page.handleVideoEditScroll({ detail: { scrollTop: 315 } })
  select(env.page, 0)
  const firstToken = env.page.data.videoKeyframePreviewToken
  env.page.handleSelectKeyframeCover(event({ key: env.page.data.videoAiContent.frames[1].key }))
  env.page.handleKeyframePreviewLoad(event({ token: firstToken }))
  assert.equal(env.page.data.videoKeyframePreviewStatus, 'loading')
  env.page.handleKeyframePreviewError(event({ token: env.page.data.videoKeyframePreviewToken }))
  assert.equal(env.page.data.videoKeyframePreviewStatus, 'failed')
  await env.page.handleApplyKeyframeCover()
  assert.equal(env.download, undefined)
  env.page.handleRetryKeyframePreview()
  previewLoaded(env.page)
  env.page.handleKeyframeScroll({ detail: { scrollTop: 180 } })
  env.page.handleVideoEditScroll({ detail: { scrollTop: 0 } })
  env.page.handleCloseKeyframeCover()
  assert.equal(env.page.data.videoEditScrollTop, 315)
  assert.equal(env.page.data.videoAiExpanded, true)
  assert.equal(env.page.data.videoAiTab, 'frames')
  env.page.handleOpenKeyframeCover()
  assert.equal(env.page.data.videoKeyframeScrollTop, 180)
  assert.equal(env.page.data.videoKeyframeCandidate, null)
})

test('本地相册取消保留关键帧草稿；新来源应用后只清理自有文件', async () => {
  const env = setup()
  await open(env)
  await apply(env)
  env.wxApi.chooseMedia = ({ fail }) => fail({ errMsg: 'chooseMedia:fail cancel' })
  await env.page.handleChooseVideoCoverUpload()
  assert.equal(env.page.data.videoEditForm.localCoverPath, 'wxfile://chosen')
  assert.deepEqual(env.removed, [])
  env.wxApi.chooseMedia = ({ success }) => success({ tempFiles: [{ tempFilePath: 'wxfile://album-original', width: 500, height: 500, size: 45000 }] })
  await env.page.handleChooseVideoCoverUpload()
  assert.equal(env.page.data.videoEditForm.localCoverPath, 'wxfile://album-original')
  assert.equal(env.page.data.videoEditForm.coverDraftSource, 'local')
  assert.deepEqual(env.removed, ['wxfile://chosen'])
  env.page.handleCloseVideoEditor()
  assert.deepEqual(env.removed, ['wxfile://chosen'])
})

test('手工选帧确认才替换草稿，播放进度不改已应用时间且仍用旧请求参数', async () => {
  const env = setup()
  await open(env)
  await apply(env)
  await env.page.handleStartVideoCoverEdit()
  env.page.updateVideoCoverFrameTime(14000, false)
  assert.equal(env.page.data.videoEditForm.localCoverPath, 'wxfile://chosen')
  await env.page.handleExportVideoCover()
  env.page.updateVideoCoverFrameTime(25000, false)
  await env.page.handleConfirmVideoEdit()
  const update = env.requests.find(item => item.method === 'PUT')
  assert.equal(update.data.coverFrameTimeMs, 14000)
  assert.equal(update.data.width, 1920)
  assert.equal(update.data.height, 1080)
  assert.equal(env.uploads.length, 0)
  assert.ok(!Object.hasOwn(update.data, 'coverTaskId'))
})

test('切换作品及卸载后，迟到的保存或下载不关闭新编辑会话', async () => {
  const env = setup()
  await open(env)
  await apply(env)
  let resolveSave
  env.saveGate = new Promise(resolve => { resolveSave = resolve })
  const pendingSave = env.page.handleConfirmVideoEdit()
  await flush()
  assert.equal(env.page.data.videoCoverSaveStage, 'saving')
  env.page.openVideoEditSheet({ ...work, id: '19' })
  resolveSave()
  await pendingSave
  assert.equal(env.page.data.videoEditForm.id, '19')
  assert.equal(env.page.data.videoEditSheetVisible, true)
  await flush()
  select(env.page)
  previewLoaded(env.page)
  const pendingDownload = env.page.handleApplyKeyframeCover()
  const download = env.download
  env.page.onUnload()
  download.success({ statusCode: 200, tempFilePath: 'wxfile://after-unload' })
  await pendingDownload
  assert.ok(env.removed.includes('wxfile://after-unload'))
  assert.equal(env.page.data.videoEditForm.localCoverPath, '')
})

test('1、8、12、24 张均完整保留且按时间排序；同时间不同帧可独立选择', async () => {
  for (const count of [1, 8, 12, 24]) {
    const env = setup()
    env.content = { hasContent: true, frames: Array.from({ length: count }, (_, index) => ({ timestampMs: Math.floor(index / 2) * 1000, url: `https://frames/${index}` })).reverse() }
    await open(env)
    assert.equal(env.page.data.videoAiContent.frames.length, count)
    assert.equal(new Set(env.page.data.videoAiContent.frames.map(frame => frame.key)).size, count)
    assert.equal(env.page.data.videoAiContent.frames[0].timestampMs, 0)
    select(env.page, count - 1)
    assert.equal(env.page.data.videoKeyframeCandidate.key, env.page.data.videoAiContent.frames[count - 1].key)
    env.page.handleCloseVideoEditor()
    env.page.handleCloseVideoEditor()
  }
})

test('刷新保留相同 URL 也不能按旧序号恢复候选', async () => {
  const env = setup()
  await open(env)
  await apply(env)
  const oldFrame = env.page.data.videoAiContent.frames[1]
  await env.page.handleRetryVideoAi()
  env.page.handleVideoAiFrameError(event({ index: 1, url: oldFrame.url, key: oldFrame.key }))
  assert.equal(env.page.data.videoAiContent.frames[1].loadFailed, false)
  env.page.handleOpenKeyframeCover()
  assert.equal(env.page.data.videoKeyframeCandidate, null)
  env.page.handleCloseKeyframeCover()
  assert.equal(env.page.data.videoEditForm.localCoverPath, 'wxfile://chosen')
})
