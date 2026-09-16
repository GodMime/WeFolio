const assert = require('node:assert/strict')
const test = require('node:test')

const {
  ANIMATION_MAX_BYTES,
  IMAGE_MAX_BYTES,
  MAX_BATCH_COUNT,
  VIDEO_MAX_BYTES,
  VIDEO_MAX_DURATION_SECONDS,
  THUMB_MAX_BYTES,
  buildUploadProgressSummary,
  buildUploadCompleteFailureMessage,
  buildUploadCompletePayload,
  buildAspectRatio,
  applyUploadCompleteResults,
  applyAnimationSingleFrameFallbacks,
  buildCoverUploadTicketPayload,
  buildUploadTicketPayload,
  createChooseCoverImageOptions,
  createChooseMediaOptions,
  classifyChosenMediaFiles,
  measureChosenMediaFiles,
  prepareWorkMainFiles,
  releasePreparedWorkFiles,
  enrichVideoFileMetadata,
  normalizeChosenMediaFiles,
  readAudioDuration,
  prepareLocalCoverUploadFile,
  prepareCoverUploadFiles,
  runWorkUploadQueue,
  validateChosenMediaFiles
} = require('../pages/works/utils/work-upload')

test('音频复用原票据和队列，保留时长且不产生封面任务', async () => {
  const [file] = normalizeChosenMediaFiles([
    { path: 'wxfile://song', name: '歌曲.M4A', fileType: 'audio', size: 1024, durationMs: 12345 }
  ])
  assert.equal(file.mediaType, 'AUDIO')
  assert.equal(file.fileType, 'audio')
  assert.equal(file.mimeType, 'audio/mp4')
  assert.equal(validateChosenMediaFiles([file]).valid, true)
  const item = buildUploadTicketPayload([file]).files[0]
  assert.equal(item.durationMs, 12345)
  assert.equal(Object.hasOwn(item, 'width'), false)
  assert.equal(Object.hasOwn(item, 'bitRateBps'), false)
  assert.deepEqual(buildCoverUploadTicketPayload([file]).files, [])
  const uploaded = []
  await runWorkUploadQueue([file], async (target) => uploaded.push(target.fileName))
  assert.deepEqual(uploaded, ['歌曲.M4A'])
})

test('音频仅限制后缀、大小和时长，不限制码率', () => {
  const file = { mediaType: 'AUDIO', fileName: 'a.wav', size: 50 * 1024 * 1024, durationMs: 600000 }
  assert.equal(validateChosenMediaFiles([file]).valid, true)
  assert.equal(validateChosenMediaFiles([{ ...file, bitrate: 1411200 }]).valid, true)
  for (const patch of [{ fileName: 'a.exe' }, { size: 0 }, { size: file.size + 1 }, { durationMs: 0 }, { durationMs: NaN }, { durationMs: 600001 }]) {
    assert.equal(validateChosenMediaFiles([{ ...file, ...patch }]).valid, false)
  }
})

test('原生时长读取不播放，成功后销毁实例', async () => {
  let destroyed = 0
  const context = {
    duration: 12.345,
    onCanplay(callback) { this.ready = callback },
    onError(callback) { this.error = callback },
    set src(value) { assert.equal(value, 'wxfile://song'); this.ready() },
    play() { assert.fail('读取时长不能播放出声') },
    destroy() { destroyed++ }
  }
  assert.equal(await readAudioDuration('wxfile://song', { wxApi: { createInnerAudioContext: () => context } }), 12345)
  assert.equal(context.autoplay, false)
  assert.equal(destroyed, 1)
})

test('原生时长失败、超时和取消均销毁实例，迟到事件无效', async () => {
  for (const outcome of ['error', 'timeout', 'cancel']) {
    let destroyed = 0
    let cancel
    const context = {
      duration: 0,
      onCanplay(callback) { this.ready = callback },
      onError(callback) { this.error = callback },
      set src(value) { if (outcome === 'error') this.error() },
      destroy() { destroyed++ }
    }
    const pending = readAudioDuration('wxfile://song', {
      wxApi: { createInnerAudioContext: () => context }, timeoutMs: 10,
      onCancelReady(callback) { cancel = callback }
    })
    if (outcome === 'cancel') cancel()
    await assert.rejects(pending, /无法读取音频信息|cancel/)
    context.duration = 20
    context.ready()
    assert.equal(destroyed, 1)
  }
})

test('chooseMedia options use mixed album picker and cap count at 9', () => {
  assert.deepEqual(createChooseMediaOptions(20), {
    count: MAX_BATCH_COUNT,
    mediaType: ['mix'],
    sourceType: ['album'],
    sizeType: ['original']
  })
  assert.equal(createChooseMediaOptions(3).count, 3)
})

test('classifies GIF and animated WebP below 32MB while keeping static WebP as image', async () => {
  const payloads = {
    'wxfile://tmp/a.gif': Buffer.from('GIF89a', 'ascii'),
    'wxfile://tmp/a.webp': Buffer.from([
      0x52, 0x49, 0x46, 0x46, 0x10, 0, 0, 0,
      0x57, 0x45, 0x42, 0x50,
      0x56, 0x50, 0x38, 0x58, 0x0a, 0, 0, 0,
      0x02
    ]),
    'wxfile://tmp/static.webp': Buffer.from([
      0x52, 0x49, 0x46, 0x46, 0x08, 0, 0, 0,
      0x57, 0x45, 0x42, 0x50,
      0x56, 0x50, 0x38, 0x20
    ])
  }
  const readPaths = []
  const wxApi = {
    getFileSystemManager() {
      return {
        readFile(options) {
          readPaths.push(options.filePath)
          const source = payloads[options.filePath]
          // 模拟开发者工具超过 10MB 后把传输描述对象误当 Base64 的行为。
          if (!options.length || options.length > 10 * 1024 * 1024) {
            atob(String({ __bufferDataKey: 'large-file', dataType: 'binary' }))
          }
          const data = source.subarray(options.position, options.position + options.length)
          options.success({
            data: data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength)
          })
        }
      }
    }
  }
  const files = normalizeChosenMediaFiles([
    { tempFilePath: 'wxfile://tmp/a.gif', size: 32 * 1024 * 1024 - 1, fileType: 'image' },
    { tempFilePath: 'wxfile://tmp/a.webp', size: 32 * 1024 * 1024 - 1, fileType: 'image' },
    { tempFilePath: 'wxfile://tmp/static.webp', size: payloads['wxfile://tmp/static.webp'].length, fileType: 'image' }
  ])

  const classified = await classifyChosenMediaFiles(files, { wxApi })

  assert.deepEqual(readPaths, [
    'wxfile://tmp/a.gif',
    'wxfile://tmp/a.webp',
    'wxfile://tmp/static.webp'
  ])
  assert.equal(classified[0].mediaType, 'ANIMATION')
  assert.equal(classified[0].mimeType, 'image/gif')
  assert.equal(classified[1].mediaType, 'ANIMATION')
  assert.equal(classified[1].mimeType, 'image/webp')
  assert.equal(classified[2].mediaType, 'IMAGE')
  assert.equal(classified[2].mimeType, 'image/webp')
})

test('识别 WebP 后续动画段时跳过大块数据并保留奇数长度填充', async () => {
  const metadataSize = 12 * 1024 * 1024 + 1
  const animationOffset = 12 + 8 + metadataSize + 1
  const fileSize = animationOffset + 8
  const header = Buffer.alloc(21)
  header.write('RIFF', 0)
  header.writeUInt32LE(fileSize - 8, 4)
  header.write('WEBP', 8)
  header.write('ICCP', 12)
  header.writeUInt32LE(metadataSize, 16)
  const animationHeader = Buffer.alloc(8)
  animationHeader.write('ANMF')
  const calls = []
  const wxApi = {
    getFileSystemManager() {
      return {
        readFile(options) {
          calls.push({ position: options.position, length: options.length })
          let data
          if (options.position === 0 && options.length <= header.length) {
            data = header.subarray(0, options.length)
          } else if (options.position === animationOffset && options.length === 8) {
            data = animationHeader
          } else {
            assert.fail('只能读取必要头部，不得读取中间大块元数据')
          }
          options.success({ data: data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) })
        }
      }
    }
  }

  const [file] = await classifyChosenMediaFiles(normalizeChosenMediaFiles([
    { tempFilePath: 'wxfile://tmp/large-metadata.webp', size: fileSize, fileType: 'image' }
  ]), { wxApi })

  assert.equal(file.mediaType, 'ANIMATION')
  assert.deepEqual(calls, [{ position: 0, length: 21 }, { position: animationOffset, length: 8 }])
})

test('短文件和损坏的 WebP 段长度不会触发越界读取', async () => {
  const brokenWebp = Buffer.alloc(20)
  brokenWebp.write('RIFF', 0)
  brokenWebp.writeUInt32LE(12, 4)
  brokenWebp.write('WEBP', 8)
  brokenWebp.write('ICCP', 12)
  brokenWebp.writeUInt32LE(0xffffffff, 16)
  for (const source of [Buffer.from('GIF89a'), brokenWebp]) {
    const calls = []
    const wxApi = {
      getFileSystemManager() {
        return {
          readFile(options) {
            calls.push(options)
            assert.ok(options.position + options.length <= source.length)
            const data = source.subarray(options.position, options.position + options.length)
            options.success({ data: data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) })
          }
        }
      }
    }
    const [file] = await classifyChosenMediaFiles(normalizeChosenMediaFiles([
      { tempFilePath: 'wxfile://tmp/short-image', size: source.length, fileType: 'image' }
    ]), { wxApi })

    assert.equal(file.mediaType, source === brokenWebp ? 'IMAGE' : 'ANIMATION')
    assert.equal(calls.length, 1)
  }
})

test('后续 WebP 段读取失败时沿用单文件回退并继续分类其它文件', async () => {
  const header = Buffer.alloc(21)
  header.write('RIFF', 0)
  header.writeUInt32LE(30, 4)
  header.write('WEBP', 8)
  header.write('VP8X', 12)
  header.writeUInt32LE(10, 16)
  const readPaths = []
  const wxApi = {
    getFileSystemManager() {
      return {
        readFile(options) {
          readPaths.push([options.filePath, options.position])
          if (options.position > 0) {
            options.fail({ errMsg: 'readFile:fail interrupted' })
            return
          }
          const data = options.filePath.endsWith('.webp') ? header : Buffer.from('GIF89a')
          options.success({ data: data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) })
        }
      }
    }
  }
  const files = normalizeChosenMediaFiles([
    { tempFilePath: 'wxfile://tmp/interrupted.webp', size: 38, fileType: 'image' },
    { tempFilePath: 'wxfile://tmp/next.gif', size: 6, fileType: 'image' }
  ])

  const classified = await classifyChosenMediaFiles(files, { wxApi })

  assert.equal(classified[0], files[0])
  assert.equal(classified[1].mediaType, 'ANIMATION')
  assert.deepEqual(readPaths, [
    ['wxfile://tmp/interrupted.webp', 0],
    ['wxfile://tmp/interrupted.webp', 30],
    ['wxfile://tmp/next.gif', 0]
  ])
})

test('does not read or compress GIF candidates at or above 32MB', async () => {
  let readCount = 0
  const wxApi = {
    getFileSystemManager() {
      return {
        readFile() {
          readCount += 1
        }
      }
    },
    compressImage() {
      assert.fail('oversized animation candidate must not be compressed')
    }
  }
  for (const extension of ['gif']) {
    for (const size of [32 * 1024 * 1024, 32 * 1024 * 1024 + 1]) {
      const files = normalizeChosenMediaFiles([
        {
          tempFilePath: `wxfile://tmp/too-large.${extension}`,
          name: `too-large.${extension}`,
          mimeType: `image/${extension}`,
          size,
          fileType: 'image'
        }
      ])

      await assert.rejects(
        classifyChosenMediaFiles(files, { wxApi }),
        /动图作品必须小于 32MB/
      )
    }
  }
  assert.equal(readCount, 0)
})

test('cover image picker only allows one album image', () => {
  assert.deepEqual(createChooseCoverImageOptions(), {
    count: 1,
    mediaType: ['image'],
    sourceType: ['album']
  })
})

test('builds simplified aspect ratio from positive dimensions', () => {
  assert.equal(buildAspectRatio(1920, 1080), '16:9')
  assert.equal(buildAspectRatio(1080, 1920), '9:16')
  assert.equal(buildAspectRatio(1200, 800), '3:2')
  assert.equal(buildAspectRatio(1000, 1000), '1:1')
  assert.equal(buildAspectRatio(0, 1000), '')
  assert.equal(buildAspectRatio('bad', 1000), '')
})

test('normalizes chosen media files with original titles and media types', () => {
  const files = normalizeChosenMediaFiles([
    {
      tempFilePath: 'wxfile://tmp/photo.jpg',
      name: 'photo.jpg',
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
  assert.equal(files[0].aspectRatio, '3:2')
  assert.equal(files[1].mediaType, 'VIDEO')
  assert.equal(files[1].durationMs, 12000)
  assert.equal(files[1].aspectRatio, '')
  assert.equal(files[1].coverPath, 'wxfile://tmp/thumb.jpg')
})

test('truncates default titles from long file names to thirty characters', () => {
  const longStem = '一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十额外'
  const files = normalizeChosenMediaFiles([
    {
      tempFilePath: `wxfile://tmp/${longStem}.jpg`,
      name: `${longStem}.jpg`,
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
  assert.equal(enrichedFiles[0].aspectRatio, '9:16')
  assert.equal(enrichedFiles[0].metaText, '默认标题 · 视频 00:13')
})

test('enriches missing image dimensions from getImageInfo', async () => {
  const files = normalizeChosenMediaFiles([
    {
      tempFilePath: 'wxfile://tmp/photo.jpg',
      size: 1024,
      fileType: 'image'
    }
  ])
  const wxApi = {
    getImageInfo(options) {
      assert.equal(options.src, 'wxfile://tmp/photo.jpg')
      options.success({
        width: 1200,
        height: 800
      })
    }
  }

  const enrichedFiles = await enrichVideoFileMetadata(files, { wxApi })

  assert.equal(enrichedFiles[0].width, 1200)
  assert.equal(enrichedFiles[0].height, 800)
  assert.equal(enrichedFiles[0].aspectRatio, '3:2')
})

test('validates upload file count size and duration limits', () => {
  assert.equal(validateChosenMediaFiles(new Array(10).fill({ mediaType: 'IMAGE', size: 1 })).message, '一次最多上传 9 个作品')
  assert.equal(validateChosenMediaFiles([{ mediaType: 'IMAGE', size: IMAGE_MAX_BYTES + 1 }]).message, '图片作品不能超过 10MB')
  assert.equal(validateChosenMediaFiles([{ mediaType: 'VIDEO', size: VIDEO_MAX_BYTES + 1, durationMs: 1000 }]).message, '视频作品不能超过 100MB')
  assert.equal(validateChosenMediaFiles([{ mediaType: 'VIDEO', size: 1, durationMs: (VIDEO_MAX_DURATION_SECONDS + 1) * 1000 }]).message, '视频作品不能超过 10 分钟')
  assert.equal(ANIMATION_MAX_BYTES, 32 * 1024 * 1024 - 1)
  assert.equal(validateChosenMediaFiles([{ mediaType: 'ANIMATION', size: 10 * 1024 * 1024 + 1 }]).valid, true)
  assert.equal(validateChosenMediaFiles([{ mediaType: 'ANIMATION', size: 32 * 1024 * 1024 - 1 }]).valid, true)
  for (const size of [32 * 1024 * 1024, 32 * 1024 * 1024 + 1]) {
    assert.equal(validateChosenMediaFiles([{ mediaType: 'ANIMATION', size }]).message, '动图作品必须小于 32MB')
  }
})

test('builds upload complete payload with per-file metadata', () => {
  const payload = buildUploadCompletePayload([
    {
      taskId: 99,
      title: ' 草坪婚礼 ',
      description: ' 晚宴快剪 ',
      tags: ['高端婚礼'],
      aspectRatio: '16:9',
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
        aspectRatio: '16:9',
        idempotencyKey: 'confirm-99'
      }
    ]
  })
})

test('builds upload complete payload aspect ratio from dimensions when cached ratio is missing', () => {
  const payload = buildUploadCompletePayload([
    {
      taskId: 99,
      title: '草坪婚礼',
      description: '',
      tags: [],
      width: 1200,
      height: 800,
      confirmIdempotencyKey: 'confirm-99'
    }
  ])

  assert.equal(payload.items[0].aspectRatio, '3:2')
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
        aspectRatio: '',
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

test('preserves item error code and downgrades a single-frame animation only once', () => {
  const [failed] = applyUploadCompleteResults([
    {
      id: 'a',
      clientId: 'animation-a',
      taskId: 99,
      mediaType: 'ANIMATION',
      mimeType: 'image/gif',
      tempFilePath: 'wxfile://tmp/a.gif',
      title: '动图',
      status: 'UPLOADED',
      sha256: 'sha'
    }
  ], [
    {
      taskId: 99,
      success: false,
      errorCode: 'ANIMATION_SINGLE_FRAME',
      message: '单帧文件按图片上传'
    }
  ])
  assert.equal(failed.errorCode, 'ANIMATION_SINGLE_FRAME')

  const [fallback] = applyAnimationSingleFrameFallbacks([failed], 1234)
  assert.equal(fallback.mediaType, 'IMAGE')
  assert.equal(fallback.mimeType, 'image/gif')
  assert.equal(fallback.taskId, null)
  assert.equal(fallback.status, 'READY')
  assert.equal(fallback.animationFallbackAttempted, true)
  assert.notEqual(fallback.clientId, 'animation-a')
  assert.deepEqual(applyAnimationSingleFrameFallbacks([
    Object.assign({}, fallback, { errorCode: 'ANIMATION_SINGLE_FRAME' })
  ], 5678), [Object.assign({}, fallback, { errorCode: 'ANIMATION_SINGLE_FRAME' })])
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
  assert.equal(compressCalls[0].compressedWidth, 960)
  assert.equal(Object.prototype.hasOwnProperty.call(compressCalls[0], 'compressedHeight'), false)
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
    aspectRatio: '',
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
  const activeByType = { IMAGE: 0, VIDEO: 0, ANIMATION: 0 }
  const maxByType = { IMAGE: 0, VIDEO: 0, ANIMATION: 0 }
  const order = []
  const items = [
    { id: 'i1', mediaType: 'IMAGE' },
    { id: 'i2', mediaType: 'IMAGE' },
    { id: 'i3', mediaType: 'IMAGE' },
    { id: 'v1', mediaType: 'VIDEO' },
    { id: 'v2', mediaType: 'VIDEO' },
    { id: 'a1', mediaType: 'ANIMATION' },
    { id: 'a2', mediaType: 'ANIMATION' },
    { id: 'a3', mediaType: 'ANIMATION' }
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
  assert.equal(maxByType.ANIMATION, 2)
  assert.deepEqual(order.filter((id) => id.startsWith('v')), ['v1', 'v2'])
})


// 仅读取容器头，避免测试自身分配大图片；错误段长不能获得静态确认。
function webpFixture(chunks) {
  const encoded = chunks.map(([type, payload]) => {
    const part = Buffer.alloc(8 + payload.length + payload.length % 2)
    part.write(type, 0)
    part.writeUInt32LE(payload.length, 4)
    payload.copy(part, 8)
    return part
  })
  const body = Buffer.concat(encoded)
  const header = Buffer.alloc(12)
  header.write('RIFF', 0)
  header.writeUInt32LE(body.length + 4, 4)
  header.write('WEBP', 8)
  return Buffer.concat([header, body])
}

function classifiedWebpFixture(source, extra = {}) {
  const reads = []
  const wxApi = {
    getImageInfo() { assert.fail('分类不能解码') },
    compressImage() { assert.fail('分类不能编码') },
    getFileSystemManager() {
      return {
        statSync() { return { size: extra.size || source.length } },
        readFile(args) {
          reads.push([args.position, args.length])
          assert.ok(Number.isInteger(args.position) && args.position >= 0)
          assert.ok(args.length > 0 && args.length <= 21)
          const data = source.subarray(args.position, args.position + args.length)
          args.success({ data: data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) })
        }
      }
    }
  }
  const file = { clientId: 'webp', mediaType: 'IMAGE', tempFilePath: 'wxfile://source.webp',
    fileName: 'source.webp', mimeType: 'image/webp', size: source.length, ...extra }
  return { file, wxApi, reads }
}

test('实测大小修正仅清主SHA，保留两种封面指纹，stat失败不回退picker', () => {
  const file = { mediaType: 'IMAGE', tempFilePath: 'source', size: 1, sha256: 'old',
    coverSha256: 'cover', customCoverPath: 'custom', customCoverSha256: 'custom-sha' }
  const wxApi = { getFileSystemManager: () => ({ statSync: () => ({ size: 2 }) }) }
  const [result] = measureChosenMediaFiles([file], { wxApi })
  assert.deepEqual(result, { ...file, size: 2, sha256: '' })
  assert.equal(file.size, 1)
  for (const size of [undefined, NaN, -1, 1.5]) {
    assert.throws(() => measureChosenMediaFiles([file], {
      wxApi: { getFileSystemManager: () => ({ statSync: () => ({ size }) }) }
    }), /无法读取作品文件大小/)
  }
})

test('大静态WebP有界分类且stat覆盖picker少报大小', async () => {
  const size = 32 * 1024 * 1024 + 10
  const header = Buffer.alloc(21)
  header.write('RIFF', 0)
  header.writeUInt32LE(size - 8, 4)
  header.write('WEBP', 8)
  header.write('VP8 ', 12)
  header.writeUInt32LE(size - 20, 16)
  const { file, wxApi, reads } = classifiedWebpFixture(header, { size })
  const [measured] = measureChosenMediaFiles([{ ...file, size: 100, sha256: 'old' }], { wxApi })
  const [result] = await classifyChosenMediaFiles([measured], { wxApi })
  assert.equal(result.size, size)
  assert.equal(result.sha256, '')
  assert.equal(result.mediaType, 'IMAGE')
  assert.equal(result.staticImageVerified, true)
  assert.equal(result.webpAlpha, 'opaque')
  assert.ok(reads.reduce((n, read) => n + read[1], 0) <= 64 * 1024)
  header.writeUInt32LE(size + 1, 16)
  await assert.rejects(classifyChosenMediaFiles([measured], { wxApi }), /无法确认.*WebP.*JPEG.*PNG.*GIF/)
})

test('WebP透明标志区别EXIF且VP8L提示不能证明不透明', async () => {
  const vp8x = flag => { const bytes = Buffer.alloc(10); bytes[0] = flag; return bytes }
  const vp8l = hint => Buffer.from([0x2f, 0, 0, 0, hint ? 0x10 : 0])
  const cases = [
    { chunks: [['VP8 ', Buffer.from([1, 2])]], alpha: 'opaque', hint: null },
    { chunks: [['VP8X', vp8x(0x10)], ['ALPH', Buffer.from([0, 1])], ['VP8 ', Buffer.from([1, 2])]], alpha: 'present', hint: null },
    { chunks: [['VP8X', vp8x(0x08)], ['VP8 ', Buffer.from([1, 2])], ['EXIF', Buffer.from([1, 2])]], alpha: 'opaque', hint: null },
    { chunks: [['VP8L', vp8l(false)]], alpha: 'unknown', hint: false },
    { chunks: [['VP8L', vp8l(true)]], alpha: 'present', hint: true },
    { chunks: [['VP8X', vp8x(0)], ['ALPH', Buffer.from([0, 1])], ['VP8 ', Buffer.from([1, 2])]], alpha: 'unknown', hint: null }
  ]
  for (const { chunks, alpha, hint } of cases) {
    const { file, wxApi } = classifiedWebpFixture(webpFixture(chunks))
    const [result] = await classifyChosenMediaFiles([file], { wxApi })
    assert.equal(result.staticImageVerified, true)
    assert.equal(result.webpAlpha, alpha)
    assert.equal(result.webpAlphaHint, hint)
  }
})

test('空容器、只有元数据、坏VP8L不能确认为静态WebP', async () => {
  for (const chunks of [[], [['EXIF', Buffer.from([0, 0])]], [['VP8L', Buffer.from([0, 0, 0, 0, 0])]]]) {
    const { file, wxApi } = classifiedWebpFixture(webpFixture(chunks))
    const [result] = await classifyChosenMediaFiles([file], { wxApi })
    assert.notEqual(result.staticImageVerified, true)
  }
})

test('WebP扫描最多1024段，不读整文件，不把未扫描结束当静态', async () => {
  const chunks = Array.from({ length: 1024 }, () => ['EXIF', Buffer.alloc(0)])
  chunks.push(['VP8 ', Buffer.from([1, 2])])
  const { file, wxApi, reads } = classifiedWebpFixture(webpFixture(chunks))
  const [result] = await classifyChosenMediaFiles([file], { wxApi })
  assert.notEqual(result.staticImageVerified, true)
  assert.equal(reads.length, 1024)
  assert.ok(reads.reduce((n, read) => n + read[1], 0) <= 64 * 1024)
})

test('超过32MB但达到扫描上限的WebP不能误报为动图超限', async () => {
  const size = 33 * 1024 * 1024
  const chunks = Array.from({ length: 1024 }, () => ['JUNK', Buffer.alloc(0)])
  chunks.push(['VP8 ', Buffer.from([1, 2])])
  const source = webpFixture(chunks)
  source.writeUInt32LE(size - 8, 4)
  const { file, wxApi, reads } = classifiedWebpFixture(source, { size })

  await assert.rejects(classifyChosenMediaFiles([file], { wxApi }), /无法确认.*WebP.*JPEG.*PNG.*GIF/)
  assert.equal(reads.length, 1024)
})

test('超限WebP无法完整识别时提示转换格式，未超限仍原样保留', async () => {
  const { createCompressionSession } = require('../pages/works/utils/work-compression-runtime')
  for (const size of [9 * 1024 * 1024, 11 * 1024 * 1024]) {
    const source = webpFixture(Array.from({ length: 1024 }, () => ['JUNK', Buffer.alloc(0)]).concat([['ANIM', Buffer.alloc(6)]]))
    source.writeUInt32LE(size - 8, 4)
    const { file, wxApi } = classifiedWebpFixture(source, { size })
    wxApi.getImageInfo = args => args.success({ width: 1920, height: 1080, type: 'webp' })
    const session = createCompressionSession({ wxApi, protectedPaths: [file.tempFilePath] })
    const files = await classifyChosenMediaFiles([file], { wxApi, session })
    assert.equal(files[0].staticImageVerified, false)
    if (size > IMAGE_MAX_BYTES) {
      await assert.rejects(prepareWorkMainFiles(files, { wxApi, session }), /无法确认.*WebP.*JPEG.*PNG.*GIF/)
    } else {
      const result = await prepareWorkMainFiles(files, { wxApi, session })
      assert.equal(result.files[0].tempFilePath, file.tempFilePath)
      assert.equal(result.files[0].size, size)
      assert.equal(result.files[0].mediaType, 'IMAGE')
    }
    session.dispose()
  }
})

test('超10MB且带标准动画标志的WebP仍按动图原样处理', async () => {
  const { createCompressionSession } = require('../pages/works/utils/work-compression-runtime')
  const flags = Buffer.alloc(10); flags[0] = 2
  const source = webpFixture([['VP8X', flags], ['ANIM', Buffer.alloc(6)]])
  const size = 11 * 1024 * 1024
  source.writeUInt32LE(size - 8, 4)
  const { file, wxApi, reads } = classifiedWebpFixture(source, { size })
  const session = createCompressionSession({ wxApi })
  const files = await classifyChosenMediaFiles([file], { wxApi, session })
  const result = await prepareWorkMainFiles(files, { wxApi, session })
  assert.equal(result.files[0].mediaType, 'ANIMATION')
  assert.equal(result.files[0].tempFilePath, file.tempFilePath)
  assert.equal(reads.length, 1)
  session.dispose()
})


function preparationFixture({ failSecond = false, invalidSecond = false, sizes = null } = {}) {
  const calls = []
  const removed = []
  const files = [0, 1].map(index => ({ clientId: `file-${index}`, mediaType: 'IMAGE',
    tempFilePath: `wxfile://source-${index}`, fileName: `图片${index}.jpeg`, title: `图片${index}`,
    mimeType: 'image/jpeg', size: IMAGE_MAX_BYTES + 1, width: 6000, height: 4000, sha256: 'old' }))
  if (invalidSecond) Object.assign(files[1], { mediaType: 'VIDEO', size: VIDEO_MAX_BYTES + 1 })
  const wxApi = {
    getImageInfo(args) { args.success({ width: 6000, height: 4000, type: 'jpeg' }) },
    getVideoInfo(args) { args.success({ width: 1920, height: 1080, duration: 601, fps: 30, type: 'mp4' }) },
    compressImage(args) {
      calls.push(args)
      if (failSecond && args.src.endsWith('-1')) args.fail({ errMsg: 'encoder failed' })
      else args.success({ tempFilePath: args.src.replace('source', 'output') })
    },
    compressVideo() { assert.fail('时长超限不能编码') },
    getFileSystemManager() {
      return {
        statSync(path) { return { size: sizes && Object.hasOwn(sizes, path) ? sizes[path]
          : path.includes('source') ? IMAGE_MAX_BYTES + 1 : Math.floor(IMAGE_MAX_BYTES * 0.98) } },
        unlinkSync(path) { removed.push(path) },
        readFile() { assert.fail('预处理不读SHA') }
      }
    }
  }
  return { files, wxApi, calls, removed }
}

test('统一预处理全批预检，第二个视频过长时第一项编码为零', async () => {
  const { files, wxApi, calls } = preparationFixture({ invalidSecond: true })
  const { createCompressionSession } = require('../pages/works/utils/work-compression-runtime')
  const session = createCompressionSession({ wxApi, protectedPaths: files.map(file => file.tempFilePath) })
  await assert.rejects(prepareWorkMainFiles(files, { wxApi, session }), /视频作品不能超过 10 分钟/)
  assert.equal(calls.length, 0)
  session.dispose()
})

test('统一预处理运行期第二项失败清理首项，重选重新编码', async () => {
  const { files, wxApi, calls, removed } = preparationFixture({ failSecond: true })
  const { createCompressionSession } = require('../pages/works/utils/work-compression-runtime')
  for (let attempt = 0; attempt < 2; attempt++) {
    const session = createCompressionSession({ wxApi, protectedPaths: files.map(file => file.tempFilePath) })
    await assert.rejects(prepareWorkMainFiles(files, { wxApi, session }))
    assert.equal(calls.length, (attempt + 1) * 2)
    assert.deepEqual(removed, Array(attempt + 1).fill('wxfile://output-0'))
  }
})

test('统一预处理转移生成文件所有权，摘要复用格式器且票据只发既有字段', async () => {
  const { files, wxApi, calls, removed } = preparationFixture()
  const { createCompressionSession } = require('../pages/works/utils/work-compression-runtime')
  const session = createCompressionSession({ wxApi, protectedPaths: files.map(file => file.tempFilePath) })
  const progress = []
  const result = await prepareWorkMainFiles(files, { wxApi, session, onProgress: event => progress.push(event) })
  session.dispose()
  assert.deepEqual(removed, [])
  assert.equal(calls.length, 2)
  assert.equal(result.files[0].compressionText, '已压缩：10.0MB → 9.8MB')
  assert.equal(result.files[0].sha256, '')
  assert.deepEqual(result.ownedPathsByClientId, { 'file-0': ['wxfile://output-0'], 'file-1': ['wxfile://output-1'] })
  assert.deepEqual(progress.map(event => [event.index, event.total, event.stage]),
    [[1, 2, 'reading'], [1, 2, 'compressing'], [2, 2, 'reading'], [2, 2, 'compressing']])
  const item = buildUploadTicketPayload([{ ...result.files[0], staticImageVerified: true, webpAlpha: 'opaque' }]).files[0]
  assert.deepEqual(Object.keys(item).sort(), ['clientId', 'mediaType', 'fileName', 'mimeType', 'fileSize', 'durationMs', 'width', 'height', 'idempotencyKey'].sort())
  assert.equal(item.fileSize, Math.floor(IMAGE_MAX_BYTES * 0.98))
  assert.equal(item.fileName, '图片0.jpg')
  assert.equal(item.mimeType, 'image/jpeg')
  releasePreparedWorkFiles({ wxApi, ownedPathsByClientId: result.ownedPathsByClientId, clientIds: ['file-0'] })
  assert.deepEqual(removed, ['wxfile://output-0'])
})

test('未超限图片只读大小，无压缩API也通过且不转移原片', async () => {
  const { createCompressionSession } = require('../pages/works/utils/work-compression-runtime')
  for (const size of [IMAGE_MAX_BYTES - 1, IMAGE_MAX_BYTES]) {
    const file = { clientId: 'small', mediaType: 'IMAGE', tempFilePath: 'source', size, sha256: 'same', coverSha256: 'cover' }
    const wxApi = { getFileSystemManager: () => ({ statSync: () => ({ size }) }) }
    const session = createCompressionSession({ wxApi, protectedPaths: ['source'] })
    const result = await prepareWorkMainFiles([file], { wxApi, session })
    assert.equal(result.files[0].tempFilePath, 'source')
    assert.equal(result.files[0].sha256, 'same')
    assert.equal(result.files[0].coverSha256, 'cover')
    assert.equal(result.files[0].compressionText, '')
    assert.deepEqual(result.ownedPathsByClientId, { small: [] })
    session.dispose()
  }
})

test('统一预处理拒绝空文件，音频动图沿用既有规则且不编码', async () => {
  const { createCompressionSession } = require('../pages/works/utils/work-compression-runtime')
  const wxApi = { getFileSystemManager: () => ({ statSync: () => ({ size: 1024 }) }) }
  const session = createCompressionSession({ wxApi })
  const files = [
    { clientId: 'audio', mediaType: 'AUDIO', fileName: 'audio.mp3', tempFilePath: 'audio', size: 1024, durationMs: 5000 },
    { clientId: 'gif', mediaType: 'ANIMATION', tempFilePath: 'gif', size: 1024 }
  ]
  const result = await prepareWorkMainFiles(files, { wxApi, session })
  assert.deepEqual(result.files.map(file => file.tempFilePath), ['audio', 'gif'])
  assert.deepEqual(result.ownedPathsByClientId, { audio: [], gif: [] })
  await assert.rejects(prepareWorkMainFiles([{ ...files[1], size: 0 }], { wxApi, session }), /文件不能为空/)
})

test('大WebP区分已识别动画与短读读取失败，始终不解码未知文件', async () => {
  const size = 32 * 1024 * 1024
  for (const mode of ['dynamic', 'short', 'failure']) {
    const header = Buffer.alloc(21)
    header.write('RIFF', 0)
    header.writeUInt32LE(size - 8, 4)
    header.write('WEBP', 8)
    header.write('VP8X', 12)
    header.writeUInt32LE(10, 16)
    header[20] = 0x02
    let reads = 0
    const wxApi = { getFileSystemManager: () => ({
      readFile(args) {
        reads++
        assert.ok(args.length <= 21)
        if (mode === 'failure') args.fail({ errMsg: 'read failed' })
        else args.success({ data: mode === 'short' ? new ArrayBuffer(1)
          : header.buffer.slice(header.byteOffset, header.byteOffset + header.byteLength) })
      }
    }), getImageInfo() { assert.fail('大WebP不得解码') } }
    await assert.rejects(classifyChosenMediaFiles([{ mediaType: 'IMAGE', fileName: 'big.webp',
      tempFilePath: 'big', size }], { wxApi }), mode === 'dynamic'
      ? /动图作品必须小于 32MB/ : /无法确认.*WebP.*JPEG.*PNG.*GIF/)
    assert.equal(reads, 1)
  }
})

test('实测比picker更小的大GIF按真实大小分类', async () => {
  let reads = 0
  const wxApi = { getFileSystemManager: () => ({
    statSync: () => ({ size: 6 }),
    readFile(args) { reads++; args.success({ data: Uint8Array.from(Buffer.from('GIF89a')).buffer }) }
  }) }
  const files = measureChosenMediaFiles([{ mediaType: 'IMAGE', fileName: 'source.gif',
    tempFilePath: 'source', size: 40 * 1024 * 1024 }], { wxApi })
  const [result] = await classifyChosenMediaFiles(files, { wxApi })
  assert.equal(result.mediaType, 'ANIMATION')
  assert.equal(result.size, 6)
  assert.equal(reads, 1)
})

test('WebP有界读取取消/超时不被分类回退吞掉', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'] })
  const { createCompressionSession } = require('../pages/works/utils/work-compression-runtime')
  for (const mode of ['cancel', 'timeout']) {
    let callback
    const wxApi = { getFileSystemManager: () => ({ readFile(args) { callback = args } }) }
    const session = createCompressionSession({ wxApi })
    const pending = classifyChosenMediaFiles([{ mediaType: 'IMAGE', fileName: 'source.webp',
      tempFilePath: 'source', size: 1024 }], { wxApi, session })
    const rejection = assert.rejects(pending, { code: mode === 'cancel' ? 'MEDIA_COMPRESSION_CANCELLED' : 'MEDIA_COMPRESSION_TIMEOUT' })
    if (mode === 'cancel') session.cancel()
    else t.mock.timers.tick(10000)
    await rejection
    callback.success({ data: new ArrayBuffer(0) })
    session.dispose()
  }
})

test('超限预检缺失元数据使用明确提示且不开始编码', async () => {
  const { createCompressionSession } = require('../pages/works/utils/work-compression-runtime')
  for (const mediaType of ['IMAGE', 'VIDEO']) {
    const wxApi = {
      getImageInfo(args) { args.success(null) },
      getVideoInfo(args) { args.success(null) },
      compressImage() { assert.fail('不得编码') },
      compressVideo() { assert.fail('不得编码') }
    }
    const session = createCompressionSession({ wxApi })
    await assert.rejects(prepareWorkMainFiles([{ clientId: 'bad', mediaType,
      tempFilePath: 'source', size: VIDEO_MAX_BYTES + 1 }], { wxApi, session }), /无法读取.*信息|图片处理失败/)
  }
})

test('真实视频预处理显示实测原大小到最终大小摘要且不泄露到票据', async () => {
  const { createCompressionSession } = require('../pages/works/utils/work-compression-runtime')
  const sourceSize = 120 * 1024 * 1024
  const finalSize = 97 * 1024 * 1024
  const wxApi = {
    getVideoInfo(args) { args.success({ width: 1920, height: 1080, duration: 60, fps: 30, bitrate: 18000, type: 'mp4' }) },
    compressVideo(args) { args.success({ tempFilePath: 'wxfile://video-result' }) },
    getFileSystemManager() { return {
      statSync(path) { return { size: path === 'wxfile://video-source' ? sourceSize : finalSize } },
      unlinkSync() {}
    } }
  }
  const session = createCompressionSession({ wxApi, protectedPaths: ['wxfile://video-source'] })
  const result = await prepareWorkMainFiles([{ clientId: 'video', mediaType: 'VIDEO',
    tempFilePath: 'wxfile://video-source', fileName: '演出.mov', size: VIDEO_MAX_BYTES + 1 }], { wxApi, session })
  assert.equal(result.files[0].compressed, true)
  assert.equal(result.files[0].originalSize, sourceSize)
  assert.equal(result.files[0].compressionText, '已压缩：120.0MB → 97.0MB')
  const item = buildUploadTicketPayload(result.files).files[0]
  assert.equal(item.fileSize, finalSize)
  assert.equal(Object.hasOwn(item, 'compressed'), false)
  assert.equal(Object.hasOwn(item, 'originalSize'), false)
  assert.equal(Object.hasOwn(item, 'compressionText'), false)
  releasePreparedWorkFiles({ wxApi, ownedPathsByClientId: result.ownedPathsByClientId })
  session.dispose()
})

test('未超限视频保留picker元数据且不新增getVideoInfo或压缩能力门槛', async () => {
  const { createCompressionSession } = require('../pages/works/utils/work-compression-runtime')
  for (const statSize of [1024, VIDEO_MAX_BYTES]) {
    const wxApi = { getFileSystemManager: () => ({ statSync: () => ({ size: statSize }) }) }
    const file = { clientId: 'small-video', mediaType: 'VIDEO', tempFilePath: 'source',
      fileName: 'source.mov', mimeType: 'video/quicktime', size: 1024, sha256: 'old',
      width: 1920, height: 1080, durationMs: 60000, coverSha256: 'cover' }
    const session = createCompressionSession({ wxApi, protectedPaths: ['source'] })
    const result = await prepareWorkMainFiles([file], { wxApi, session })
    assert.equal(result.files[0].tempFilePath, 'source')
    assert.equal(result.files[0].size, statSize)
    assert.equal(result.files[0].sha256, statSize === file.size ? 'old' : '')
    assert.equal(result.files[0].coverSha256, 'cover')
    assert.equal(result.files[0].durationMs, 60000)
    assert.equal(result.files[0].compressionText, '')
    assert.deepEqual(result.ownedPathsByClientId, { 'small-video': [] })
    session.dispose()
  }
})

test('大视频预检接受数字字符串且复用已读取的源信息完成压缩', async () => {
  const { createCompressionSession } = require('../pages/works/utils/work-compression-runtime')
  const infoPaths = []
  const wxApi = {
    getVideoInfo(args) {
      infoPaths.push(args.src)
      args.success({ width: '1920', height: '1080', duration: '60', fps: 30, bitrate: 18000, type: 'mp4' })
    },
    compressVideo(args) { args.success({ tempFilePath: 'wxfile://result' }) },
    getFileSystemManager() { return {
      statSync(path) { return { size: path === 'wxfile://source' ? VIDEO_MAX_BYTES + 1 : 97 * 1024 * 1024 } },
      unlinkSync() {}
    } }
  }
  const session = createCompressionSession({ wxApi, protectedPaths: ['wxfile://source'] })
  let result
  try {
    result = await prepareWorkMainFiles([{ clientId: 'video', mediaType: 'VIDEO',
      tempFilePath: 'wxfile://source', fileName: '演出.mov', size: VIDEO_MAX_BYTES + 1 }], { wxApi, session })
    assert.equal(result.files[0].compressed, true)
    assert.equal(result.files[0].durationMs, 60000)
    assert.deepEqual(infoPaths, ['wxfile://source', 'wxfile://result'])
    assert.equal(buildUploadTicketPayload(result.files).files[0].fileSize, 97 * 1024 * 1024)
  } finally {
    if (result) releasePreparedWorkFiles({ wxApi, ownedPathsByClientId: result.ownedPathsByClientId })
    session.dispose()
  }
})

test('原生时长缺失时仍拒绝选择器返回的任何真实超十分钟视频', async (t) => {
  const { createCompressionSession } = require('../pages/works/utils/work-compression-runtime')
  for (const duration of [600.0001, 600.001, 601]) {
    await t.test(String(duration), async () => {
      const files = normalizeChosenMediaFiles([{ tempFilePath: 'wxfile://source', fileType: 'video',
        size: VIDEO_MAX_BYTES + 1, width: 1920, height: 1080, duration }])
      const wxApi = {
        getVideoInfo(args) { args.success({ width: 1920, height: 1080, duration: 0 }) },
        compressVideo() { assert.fail('超时长视频不得编码') }
      }
      const session = createCompressionSession({ wxApi })
      await assert.rejects(prepareWorkMainFiles(files, { wxApi, session }), /视频作品不能超过 10 分钟/)
    })
  }
})
