const assert = require('node:assert/strict')
const test = require('node:test')

const {
  IMAGE_MAX_BYTES,
  MAX_BATCH_COUNT,
  VIDEO_MAX_BYTES,
  VIDEO_MAX_DURATION_SECONDS,
  buildUploadCompleteFailureMessage,
  buildUploadCompletePayload,
  createChooseMediaOptions,
  normalizeChosenMediaFiles,
  runWorkUploadQueue,
  validateChosenMediaFiles
} = require('../utils/work-upload')

test('chooseMedia options only allow album and cap count at 9', () => {
  assert.deepEqual(createChooseMediaOptions(20), {
    count: MAX_BATCH_COUNT,
    mediaType: ['image', 'video'],
    sourceType: ['album']
  })
  assert.equal(createChooseMediaOptions(3).count, 3)
})

test('normalizes chosen media files with default titles and media types', () => {
  const files = normalizeChosenMediaFiles([
    {
      tempFilePath: 'wxfile://tmp/photo.jpg',
      size: 1024,
      fileType: 'image',
      width: 1200,
      height: 800
    },
    {
      tempFilePath: 'wxfile://tmp/film.mp4',
      size: 2048,
      fileType: 'video',
      duration: 12,
      thumbTempFilePath: 'wxfile://tmp/thumb.jpg'
    }
  ])

  assert.equal(files[0].mediaType, 'IMAGE')
  assert.equal(files[0].title, 'photo')
  assert.equal(files[1].mediaType, 'VIDEO')
  assert.equal(files[1].durationMs, 12000)
  assert.equal(files[1].coverPath, 'wxfile://tmp/thumb.jpg')
})

test('normalizes chosen media files for add page display rows', () => {
  const files = normalizeChosenMediaFiles([
    {
      tempFilePath: 'wxfile://tmp/photo.jpg',
      size: 1024,
      fileType: 'image'
    },
    {
      tempFilePath: 'wxfile://tmp/clip.mp4',
      size: 2048,
      fileType: 'video',
      duration: 42
    }
  ])

  assert.equal(files[0].fileName, 'photo.jpg')
  assert.equal(files[0].metaText, '默认标题 · 图片')
  assert.equal(files[0].isVideo, false)
  assert.equal(files[1].fileName, 'clip.mp4')
  assert.equal(files[1].metaText, '默认标题 · 视频 00:42')
  assert.equal(files[1].isVideo, true)
})

test('validates upload file count size and duration limits', () => {
  assert.equal(validateChosenMediaFiles(new Array(10).fill({ mediaType: 'IMAGE', size: 1 })).message, '一次最多上传 9 个作品')
  assert.equal(validateChosenMediaFiles([{ mediaType: 'IMAGE', size: IMAGE_MAX_BYTES + 1 }]).message, '图片作品不能超过 10MB')
  assert.equal(validateChosenMediaFiles([{ mediaType: 'VIDEO', size: VIDEO_MAX_BYTES + 1, durationMs: 1000 }]).message, '视频作品不能超过 100MB')
  assert.equal(validateChosenMediaFiles([{ mediaType: 'VIDEO', size: 1, durationMs: (VIDEO_MAX_DURATION_SECONDS + 1) * 1000 }]).message, '视频作品不能超过 10 分钟')
})

test('builds upload complete payload with per-file metadata', () => {
  const payload = buildUploadCompletePayload([
    {
      taskId: 99,
      title: ' 草坪婚礼 ',
      description: ' 晚宴快剪 ',
      tags: ['高端婚礼'],
      confirmIdempotencyKey: 'confirm-99'
    }
  ])

  assert.deepEqual(payload, {
    items: [
      {
        taskId: 99,
        title: '草坪婚礼',
        description: '晚宴快剪',
        tagNames: ['高端婚礼'],
        idempotencyKey: 'confirm-99'
      }
    ]
  })
})

test('builds upload complete failure message with every failed item', () => {
  const message = buildUploadCompleteFailureMessage([
    { taskId: 11, success: false, message: '上传文件读取失败，请重新上传' },
    { taskId: 12, success: true, message: '上传成功' },
    { taskId: 13, success: false, message: '标签名称不能超过 10 个字' },
    { taskId: 14, success: false, message: '' }
  ])

  assert.equal(
    message,
    '上传文件读取失败，请重新上传；标签名称不能超过 10 个字；部分作品确认失败'
  )
})

test('runs image uploads with concurrency two and video uploads serially', async () => {
  const activeByType = { IMAGE: 0, VIDEO: 0 }
  const maxByType = { IMAGE: 0, VIDEO: 0 }
  const order = []
  const items = [
    { id: 'i1', mediaType: 'IMAGE' },
    { id: 'i2', mediaType: 'IMAGE' },
    { id: 'i3', mediaType: 'IMAGE' },
    { id: 'v1', mediaType: 'VIDEO' },
    { id: 'v2', mediaType: 'VIDEO' }
  ]

  await runWorkUploadQueue(items, async (item) => {
    activeByType[item.mediaType] += 1
    maxByType[item.mediaType] = Math.max(maxByType[item.mediaType], activeByType[item.mediaType])
    order.push(item.id)
    await new Promise((resolve) => setTimeout(resolve, 5))
    activeByType[item.mediaType] -= 1
    return item.id
  })

  assert.equal(maxByType.IMAGE, 2)
  assert.equal(maxByType.VIDEO, 1)
  assert.deepEqual(order.filter((id) => id.startsWith('v')), ['v1', 'v2'])
})
