const test = require('node:test')
const assert = require('node:assert/strict')
const { createCoverFiles, downloadKeyframeCover, readCoverImage, hasUsableKeyframes } = require('../pages/works/utils/work-keyframe-cover')

function runtime() {
  const removed = []
  let download
  let aborted = false
  const wxApi = {
    getFileSystemManager: () => ({
      statSync: () => ({ size: 42000 }),
      unlink: ({ filePath }) => removed.push(filePath)
    }),
    getImageInfo: ({ success }) => success({ width: 1280, height: 720, type: 'png' }),
    downloadFile(options) { download = options; return { abort() { aborted = true } } }
  }
  return { wxApi, removed, get download() { return download }, get aborted() { return aborted } }
}

test('关键帧入口仅依赖查询成功且有未失败帧，不能仅看 AI hasContent', () => {
  const content = { hasContent: false, frames: [{ url: 'https://cdn/frame' }] }
  assert.equal(hasUsableKeyframes(content), true)
  assert.equal(hasUsableKeyframes(content, true), false)
  assert.equal(hasUsableKeyframes(content, false, '失败'), false)
  assert.equal(hasUsableKeyframes({ hasContent: true, frames: [] }), false)
  assert.equal(hasUsableKeyframes({ frames: [{ url: 'https://cdn/frame', loadFailed: true }] }), false)
})

test('下载校验后返回本地文件及真实图片格式，不使用原 URL 作为封面', async () => {
  const env = runtime()
  const files = createCoverFiles(env.wxApi)
  const job = downloadKeyframeCover('https://cdn/frame?sign=private', { wxApi: env.wxApi, files })
  env.download.success({ statusCode: 200, tempFilePath: 'wxfile://frame' })
  const cover = await job.promise
  assert.equal(cover.filePath, 'wxfile://frame')
  assert.equal(cover.mimeType, 'image/png')
  assert.equal(cover.fileName, 'cover.png')
  assert.equal(cover.width, 1280)
  assert.equal(cover.fileSize, 42000)
  files.releaseExcept([cover.filePath])
  files.release(cover.filePath)
  assert.deepEqual(env.removed, [])
  files.dispose()
  assert.deepEqual(env.removed, ['wxfile://frame'])
})

test('准备取消后迟到的下载和解码结果仅清理，不交付草稿', async () => {
  const env = runtime()
  const files = createCoverFiles(env.wxApi)
  const job = downloadKeyframeCover('https://cdn/frame', { wxApi: env.wxApi, files })
  job.cancel()
  await assert.rejects(job.promise, { cancelled: true })
  assert.equal(env.aborted, true)
  env.download.success({ statusCode: 200, tempFilePath: 'wxfile://late' })
  assert.deepEqual(env.removed, ['wxfile://late'])

  let decode
  env.wxApi.getImageInfo = (options) => { decode = options }
  const decoding = downloadKeyframeCover('https://cdn/frame', { wxApi: env.wxApi, files })
  env.download.success({ statusCode: 200, tempFilePath: 'wxfile://decoding' })
  decoding.cancel()
  decode.success({ width: 10, height: 20, type: 'jpeg' })
  await assert.rejects(decoding.promise, { cancelled: true })
  await new Promise(resolve => setImmediate(resolve))
  assert.ok(env.removed.includes('wxfile://decoding'))
})

test('下载错误、失效链接与不可解码文件都不产生草稿', async () => {
  for (const statusCode of [403, 404, 500]) {
    const env = runtime()
    const job = downloadKeyframeCover('https://cdn/frame', { wxApi: env.wxApi, files: createCoverFiles(env.wxApi) })
    env.download.success({ statusCode, tempFilePath: 'wxfile://error-body' })
    await assert.rejects(job.promise, /封面准备失败/)
    assert.deepEqual(env.removed, ['wxfile://error-body'])
  }
  const env = runtime()
  env.wxApi.getImageInfo = ({ fail }) => fail({ errMsg: 'decode failed' })
  const job = downloadKeyframeCover('https://cdn/frame', { wxApi: env.wxApi, files: createCoverFiles(env.wxApi) })
  env.download.success({ statusCode: 200, tempFilePath: 'wxfile://invalid' })
  await assert.rejects(job.promise, /封面准备失败/)
  assert.deepEqual(env.removed, ['wxfile://invalid'])
})

test('只有明确过期响应提示链接失效，开发工具 tmp 路径可用', async () => {
  const env = runtime()
  const files = createCoverFiles(env.wxApi)
  const local = await readCoverImage('http://tmp/cover', env.wxApi)
  assert.equal(local.filePath, 'http://tmp/cover')
  env.wxApi.getFileSystemManager = () => ({
    statSync: () => ({ size: 100 }),
    readFileSync: () => '<Error><Code>AccessDenied</Code><Message>Request has expired</Message></Error>',
    unlink: ({ filePath }) => env.removed.push(filePath)
  })
  const job = downloadKeyframeCover('https://cdn/frame', { wxApi: env.wxApi, files })
  env.download.success({ statusCode: 403, tempFilePath: 'http://tmp/expired' })
  await assert.rejects(job.promise, { linkExpired: true })
})

test('文件管理只清理登记的本地生成文件，失效会话也清理迟到文件', () => {
  const env = runtime()
  const files = createCoverFiles(env.wxApi)
  files.track('https://cdn/original-keyframe')
  files.track('wxfile://owned')
  files.release('wxfile://album-original')
  files.dispose()
  files.track('wxfile://late-compressed')
  assert.deepEqual(env.removed, ['wxfile://owned', 'wxfile://late-compressed'])
})

test('本地文件丢失或远程路径不能作为上传输入', async () => {
  const env = runtime()
  await assert.rejects(readCoverImage('https://cdn/frame', env.wxApi), /封面文件已失效/)
  env.wxApi.getFileSystemManager = () => ({ statSync() { throw new Error('missing') } })
  await assert.rejects(readCoverImage('wxfile://missing', env.wxApi), /封面文件已失效/)
})
