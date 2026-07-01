const assert = require('node:assert/strict')
const test = require('node:test')

const {
  IMAGE_MAX_BYTES,
  MAX_BATCH_COUNT,
  VIDEO_MAX_BYTES,
  VIDEO_MAX_DURATION_SECONDS,
  THUMB_MAX_BYTES,
  buildUploadProgressSummary,
  buildUploadCompleteFailureMessage,
  buildUploadCompletePayload,
  applyUploadCompleteResults,
  buildCoverUploadTicketPayload,
  buildUploadTicketPayload,
  createChooseCoverImageOptions,
  createChooseMediaOptions,
  enrichVideoFileMetadata,
  normalizeChosenMediaFiles,
  prepareLocalCoverUploadFile,
  prepareCoverUploadFiles,
  runWorkUploadQueue,
  validateChosenMediaFiles
} = require('../utils/work-upload')

test('chooseMedia options use mixed album picker and cap count at 9', () => {
  assert.deepEqual(createChooseMediaOptions(20), {
    count: MAX_BATCH_COUNT,
    mediaType: ['mix'],
    sourceType: ['album']
  })
  assert.equal(createChooseMediaOptions(3).count, 3)
})

test('cover image picker only allows one album image', () => {
  assert.deepEqual(createChooseCoverImageOptions(), {
    count: 1,
    mediaType: ['image'],
    sourceType: ['album']
  })
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

test('truncates default titles from long file names to thirty characters', () => {
  const longStem = '一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十额外'
  const files = normalizeChosenMediaFiles([
    {
      tempFilePath: `wxfile://tmp/${longStem}.jpg`,
      size: 1024,
      fileType: 'image'
    }
  ])

  assert.equal(files[0].title, longStem.slice(0, 30))
  assert.equal(files[0].title.length, 30)
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

test('enriches missing video dimensions from getVideoInfo', async () => {
  const files = normalizeChosenMediaFiles([
    {
      tempFilePath: 'wxfile://tmp/clip.mp4',
      size: 2048,
      fileType: 'video'
    }
  ])
  const wxApi = {
    getVideoInfo(options) {
      assert.equal(options.src, 'wxfile://tmp/clip.mp4')
      options.success({
        width: 1080,
        height: 1920,
        duration: 13
      })
    }
  }

  const enrichedFiles = await enrichVideoFileMetadata(files, { wxApi })

  assert.equal(enrichedFiles[0].width, 1080)
  assert.equal(enrichedFiles[0].height, 1920)
  assert.equal(enrichedFiles[0].durationMs, 13000)
  assert.equal(enrichedFiles[0].metaText, '默认标题 · 视频 00:13')
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

test('builds upload ticket payload with client sha256', () => {
  const payload = buildUploadTicketPayload([
    {
      clientId: 'image-a',
      mediaType: 'IMAGE',
      fileName: 'photo.jpg',
      mimeType: 'image/jpeg',
      size: 1024,
      width: 1200,
      height: 800,
      sha256: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
    },
    {
      clientId: 'video-a',
      mediaType: 'VIDEO',
      fileName: 'film.mp4',
      mimeType: 'video/mp4',
      size: 4096,
      durationMs: 60_000,
      width: 1080,
      height: 1920,
      sha256: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
    }
  ])

  assert.equal(payload.files[0].sha256, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')
  assert.equal(payload.files[1].width, 1080)
  assert.equal(payload.files[1].height, 1920)
})

test('skips confirmed files when building retry upload and complete payloads', () => {
  const files = [
    {
      clientId: 'saved-a',
      taskId: 99,
      status: 'CONFIRMED',
      mediaType: 'IMAGE',
      fileName: 'saved.jpg',
      mimeType: 'image/jpeg',
      size: 1024,
      title: '已保存'
    },
    {
      clientId: 'retry-b',
      taskId: 100,
      status: 'UPLOADED',
      mediaType: 'IMAGE',
      fileName: 'retry.jpg',
      mimeType: 'image/jpeg',
      size: 1024,
      title: '待重试',
      confirmIdempotencyKey: 'confirm-100'
    },
    {
      clientId: 'new-c',
      status: 'READY',
      mediaType: 'VIDEO',
      fileName: 'new.mp4',
      mimeType: 'video/mp4',
      size: 2048,
      durationMs: 3000
    }
  ]

  assert.deepEqual(buildUploadTicketPayload(files), {
    batchId: '',
    files: [
      {
        clientId: 'new-c',
        mediaType: 'VIDEO',
        fileName: 'new.mp4',
        mimeType: 'video/mp4',
        fileSize: 2048,
        durationMs: 3000,
        width: undefined,
        height: undefined,
        idempotencyKey: 'ticket-new-c'
      }
    ]
  })

  assert.deepEqual(buildUploadCompletePayload(files), {
    items: [
      {
        taskId: 100,
        title: '待重试',
        description: '',
        tagNames: [],
        idempotencyKey: 'confirm-100'
      }
    ]
  })

  assert.deepEqual(buildCoverUploadTicketPayload([
    {
      clientId: 'saved-cover',
      taskId: 201,
      customCoverTaskId: 301,
      status: 'CONFIRMED',
      mediaType: 'VIDEO',
      coverPath: 'wxfile://tmp/saved-thumb.jpg',
      coverSize: 1024,
      fileName: 'saved.mp4'
    },
    {
      clientId: 'retry-cover',
      taskId: 202,
      customCoverTaskId: 302,
      customCoverStatus: 'UPLOADED',
      status: 'UPLOADED',
      mediaType: 'VIDEO',
      coverPath: 'wxfile://tmp/retry-thumb.jpg',
      coverSize: 1024,
      fileName: 'retry.mp4'
    },
    {
      clientId: 'new-cover',
      taskId: 203,
      mediaType: 'IMAGE',
      coverPath: 'wxfile://tmp/new-thumb.jpg',
      coverSize: 1024,
      fileName: 'new.jpg',
      size: THUMB_MAX_BYTES + 1
    }
  ], 'cover-retry'), {
    batchId: 'cover-retry',
    files: [
      {
        clientId: 'new-cover-cover',
        mediaType: 'IMAGE',
        fileName: 'new-thumb.jpg',
        mimeType: 'image/jpeg',
        fileSize: 1024,
        durationMs: null,
        width: 0,
        height: 0,
        sourceTaskId: 203,
        idempotencyKey: 'cover-ticket-new-cover'
      }
    ]
  })
})

test('builds cover upload ticket payload with cover sha256', () => {
  const payload = buildCoverUploadTicketPayload([
    {
      clientId: 'image-a',
      taskId: 101,
      mediaType: 'IMAGE',
      coverPath: 'wxfile://tmp/image-thumb.jpg',
      coverSize: 4096,
      coverSha256: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
      fileName: 'photo.jpg',
      size: THUMB_MAX_BYTES + 1
    }
  ], 'cover-batch')

  assert.equal(payload.files[0].sha256, 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb')
})

test('applies partial upload complete success before retrying failures', () => {
  const files = [
    { id: 'a', taskId: 99, status: 'UPLOADED', progress: 100 },
    { id: 'b', taskId: 100, status: 'UPLOADED', progress: 100 },
    { id: 'c', taskId: 101, status: 'UPLOADED', progress: 100 }
  ]

  const nextFiles = applyUploadCompleteResults(files, [
    { taskId: 99, success: true, workId: 120 },
    { taskId: 100, success: true, workId: 121 },
    { taskId: 101, success: false, message: '标签名称不能超过 10 个字' }
  ])

  assert.equal(nextFiles[0].status, 'CONFIRMED')
  assert.equal(nextFiles[0].confirmedWorkId, 120)
  assert.equal(nextFiles[1].status, 'CONFIRMED')
  assert.equal(nextFiles[1].confirmedWorkId, 121)
  assert.equal(nextFiles[2].status, 'UPLOADED')
  assert.equal(nextFiles[2].errorMessage, '标签名称不能超过 10 个字')
  assert.deepEqual(buildUploadCompletePayload(nextFiles).items.map((item) => item.taskId), [101])
})

test('builds upload progress summary from main files and cover uploads', () => {
  assert.deepEqual(buildUploadProgressSummary([], false), {
    active: false,
    percent: 0,
    text: '保存作品'
  })

  assert.deepEqual(buildUploadProgressSummary([
    {
      status: 'UPLOADING',
      progress: 50
    },
    {
      status: 'UPLOADED',
      progress: 100,
      customCoverStatus: 'UPLOADING',
      customCoverProgress: 20
    },
    {
      status: 'READY',
      progress: 0
    }
  ], true), {
    active: true,
    percent: 43,
    text: '保存中 43%'
  })

  assert.equal(buildUploadProgressSummary([
    {
      status: 'UPLOADED',
      progress: 100,
      customCoverStatus: 'UPLOADED',
      customCoverProgress: 100
    }
  ], true).text, '确认作品')

  assert.deepEqual(buildUploadProgressSummary([
    {
      mediaType: 'IMAGE',
      size: THUMB_MAX_BYTES + 1,
      status: 'UPLOADED',
      progress: 100
    }
  ], true), {
    active: true,
    percent: 50,
    text: '保存中 50%'
  })

  assert.equal(buildUploadProgressSummary([
    {
      mediaType: 'VIDEO',
      status: 'UPLOADED',
      progress: 100,
      customCoverStatus: 'UPLOADING',
      customCoverProgress: 10
    }
  ], true).text, '确认作品')
})

test('prepares cover uploads by skipping small images, compressing large images, and leaving videos to backend', async () => {
  const compressCalls = []
  const sizes = {
    'wxfile://tmp/small.jpg': THUMB_MAX_BYTES,
    'wxfile://tmp/photo.jpg': THUMB_MAX_BYTES + 1,
    'wxfile://tmp/photo-thumb.jpg': 90000
  }
  const wxApi = {
    getFileSystemManager() {
      return {
        statSync(filePath) {
          return { size: sizes[filePath] || 0 }
        }
      }
    },
    compressImage(options) {
      compressCalls.push(options)
      options.success({
        tempFilePath: 'wxfile://tmp/photo-thumb.jpg'
      })
    }
  }

  const files = await prepareCoverUploadFiles([
    {
      clientId: 'image-small',
      mediaType: 'IMAGE',
      tempFilePath: 'wxfile://tmp/small.jpg',
      fileName: 'small.jpg',
      size: THUMB_MAX_BYTES
    },
    {
      clientId: 'image-large',
      taskId: 99,
      mediaType: 'IMAGE',
      tempFilePath: 'wxfile://tmp/photo.jpg',
      fileName: 'photo.png',
      size: THUMB_MAX_BYTES + 1
    },
    {
      clientId: 'video-a',
      mediaType: 'VIDEO',
      coverPath: 'wxfile://tmp/film-cover.jpg',
      fileName: 'film.mp4'
    }
  ], { wxApi })

  assert.equal(files[0].coverPath, '')
  assert.equal(files[1].coverPath, 'wxfile://tmp/photo-thumb.jpg')
  assert.equal(files[1].coverSize, 90000)
  assert.equal(files[1].coverFileName, 'photo-thumb.jpg')
  assert.equal(files[2].coverPath, 'wxfile://tmp/film-cover.jpg')
  assert.equal(files[2].coverSize, undefined)
  assert.equal(files[2].coverFileName, undefined)
  assert.equal(compressCalls.length, 1)
  assert.equal(compressCalls[0].src, 'wxfile://tmp/photo.jpg')
})

test('prepares local edited cover only when finish triggers upload', async () => {
  const compressCalls = []
  const sizes = {
    'wxfile://tmp/local-cover.jpg': THUMB_MAX_BYTES + 1,
    'wxfile://tmp/local-cover-compressed.jpg': 88000
  }
  const wxApi = {
    getFileSystemManager() {
      return {
        statSync(filePath) {
          return { size: sizes[filePath] || 0 }
        }
      }
    },
    compressImage(options) {
      compressCalls.push(options)
      options.success({ tempFilePath: 'wxfile://tmp/local-cover-compressed.jpg' })
    }
  }

  const coverFile = await prepareLocalCoverUploadFile('wxfile://tmp/local-cover.jpg', { wxApi })

  assert.equal(coverFile.filePath, 'wxfile://tmp/local-cover-compressed.jpg')
  assert.equal(coverFile.fileSize, 88000)
  assert.equal(compressCalls.length, 1)
  assert.equal(compressCalls[0].src, 'wxfile://tmp/local-cover.jpg')
})

test('builds cover upload tickets only for image thumbnails', () => {
  const payload = buildCoverUploadTicketPayload([
    {
      clientId: 'image-small',
      mediaType: 'IMAGE',
      tempFilePath: 'wxfile://tmp/small.jpg',
      fileName: 'small.jpg',
      size: THUMB_MAX_BYTES
    },
    {
      clientId: 'image-large',
      taskId: 99,
      mediaType: 'IMAGE',
      tempFilePath: 'wxfile://tmp/photo.jpg',
      coverPath: 'wxfile://tmp/photo-thumb.jpg',
      coverSize: 90000,
      coverFileName: 'photo-thumb.jpg',
      fileName: 'photo.png',
      size: THUMB_MAX_BYTES + 1
    }
  ], 'cover-batch')

  assert.deepEqual(payload, {
    batchId: 'cover-batch',
    files: [
      {
        clientId: 'image-large-cover',
        mediaType: 'IMAGE',
        fileName: 'photo-thumb.jpg',
        mimeType: 'image/jpeg',
        fileSize: 90000,
        durationMs: null,
        width: 0,
        height: 0,
        sourceTaskId: 99,
        idempotencyKey: 'cover-ticket-image-large'
      }
    ]
  })
})

test('builds upload complete payload with optional custom cover task id', () => {
  const payload = buildUploadCompletePayload([
    {
      taskId: 99,
      customCoverTaskId: 101,
      customCoverStatus: 'UPLOADED',
      title: ' 片头快剪 ',
      description: ' 现场仪式 ',
      tags: [],
      confirmIdempotencyKey: 'confirm-99'
    }
  ])

  assert.deepEqual(payload.items[0], {
    taskId: 99,
    coverTaskId: 101,
    title: '片头快剪',
    description: '现场仪式',
    tagNames: [],
    idempotencyKey: 'confirm-99'
  })
})

test('only confirms custom cover task after cover upload succeeds', () => {
  const payload = buildUploadCompletePayload([
    {
      taskId: 99,
      customCoverTaskId: 101,
      customCoverStatus: 'UPLOADING',
      title: '等待封面',
      description: '',
      tags: [],
      confirmIdempotencyKey: 'confirm-99'
    },
    {
      taskId: 100,
      customCoverTaskId: 102,
      customCoverStatus: 'UPLOADED',
      title: '封面完成',
      description: '',
      tags: [],
      confirmIdempotencyKey: 'confirm-100'
    }
  ])

  assert.equal(payload.items[0].coverTaskId, undefined)
  assert.equal(payload.items[1].coverTaskId, 102)
})

test('does not retry video cover tickets because backend generates video covers', () => {
  const payload = buildCoverUploadTicketPayload([
    {
      clientId: 'cover-uploading',
      taskId: 201,
      customCoverTaskId: 301,
      customCoverStatus: 'UPLOADING',
      mediaType: 'VIDEO',
      customCoverPath: 'wxfile://tmp/uploading-cover.jpg',
      customCoverSize: 90112,
      fileName: 'uploading.mp4'
    },
    {
      clientId: 'cover-failed',
      taskId: 202,
      customCoverTaskId: 302,
      customCoverStatus: 'FAILED',
      mediaType: 'VIDEO',
      customCoverPath: 'wxfile://tmp/failed-cover.jpg',
      customCoverSize: 88064,
      fileName: 'failed.mp4'
    },
    {
      clientId: 'cover-uploaded',
      taskId: 203,
      customCoverTaskId: 303,
      customCoverStatus: 'UPLOADED',
      mediaType: 'VIDEO',
      customCoverPath: 'wxfile://tmp/uploaded-cover.jpg',
      customCoverSize: 86016,
      fileName: 'uploaded.mp4'
    }
  ], 'cover-retry')

  assert.deepEqual(payload.files, [])
})

test('does not prepare unfinished video covers on retry', async () => {
  const compressCalls = []
  const sizes = {
    'wxfile://tmp/failed-cover.jpg': THUMB_MAX_BYTES + 4096,
    'wxfile://tmp/failed-cover-thumb.jpg': 86016
  }
  const wxApi = {
    getFileSystemManager() {
      return {
        statSync(filePath) {
          return { size: sizes[filePath] || 0 }
        }
      }
    },
    compressImage(options) {
      compressCalls.push(options)
      options.success({ tempFilePath: 'wxfile://tmp/failed-cover-thumb.jpg' })
    }
  }

  const files = await prepareCoverUploadFiles([
    {
      clientId: 'failed-cover',
      taskId: 204,
      customCoverTaskId: 304,
      customCoverStatus: 'FAILED',
      mediaType: 'VIDEO',
      customCoverPath: 'wxfile://tmp/failed-cover.jpg',
      customCoverSize: THUMB_MAX_BYTES + 4096,
      fileName: 'failed.mp4'
    },
    {
      clientId: 'uploaded-cover',
      taskId: 205,
      customCoverTaskId: 305,
      customCoverStatus: 'UPLOADED',
      mediaType: 'VIDEO',
      customCoverPath: 'wxfile://tmp/uploaded-cover.jpg',
      customCoverSize: 86016,
      fileName: 'uploaded.mp4'
    }
  ], { wxApi })

  assert.equal(files[0].customCoverPath, 'wxfile://tmp/failed-cover.jpg')
  assert.equal(files[0].customCoverSize, THUMB_MAX_BYTES + 4096)
  assert.equal(files[0].customCoverFileName, undefined)
  assert.equal(files[1].customCoverPath, 'wxfile://tmp/uploaded-cover.jpg')
  assert.equal(compressCalls.length, 0)
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
