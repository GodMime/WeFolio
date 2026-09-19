const assert = require('node:assert/strict')
const test = require('node:test')

const { createCompressionSession } = require('../pages/works/utils/work-compression-runtime')
const { compressImageToLimit } = require('../pages/works/utils/work-image-compress')

function createJpegHarness(options = {}) {
  const sourcePath = options.sourcePath || 'wxfile://source'
  const sourceSize = options.sourceSize === undefined ? 2000000 : options.sourceSize
  const sourceInfo = options.sourceInfo || {
    width: 6000,
    height: 4000,
    type: 'jpeg',
    orientation: 'up'
  }
  const outputs = new Map()
  const calls = []
  const imageInfoCalls = []
  const removed = []
  let session
  const wxApi = {
    compressImage(args) {
      calls.push(args)
      const index = calls.length
      const descriptor = options.encode
        ? options.encode(args, index, { session })
        : { size: 500000 }
      if (descriptor && descriptor.pending) {
        descriptor.capture(args)
        return
      }
      if (descriptor && descriptor.fail) {
        args.fail(descriptor.fail)
        return
      }
      const tempFilePath = (descriptor && descriptor.tempFilePath)
        || `wxfile://output-${index}`
      const width = (descriptor && descriptor.width)
        || args.compressedWidth
        || sourceInfo.width
      const height = (descriptor && descriptor.height)
        || Math.round(sourceInfo.height * width / sourceInfo.width)
      outputs.set(tempFilePath, {
        size: descriptor.size,
        info: {
          width,
          height,
          type: descriptor.type || 'jpeg',
          orientation: descriptor.orientation || 'up'
        }
      })
      args.success({ tempFilePath })
    },
    getImageInfo(args) {
      imageInfoCalls.push(args.src)
      if (args.src === sourcePath) {
        if (options.sourceInfoFailure) {
          args.fail(options.sourceInfoFailure)
        } else {
          args.success(sourceInfo)
        }
        return
      }
      const output = outputs.get(args.src)
      if (!output) {
        args.fail(new Error('missing output'))
        return
      }
      args.success(output.info)
    },
    getFileSystemManager() {
      return {
        statSync(path) {
          if (path === sourcePath) {
            if (options.statFailure) throw options.statFailure
            return { size: sourceSize }
          }
          const output = outputs.get(path)
          if (!output) throw new Error('missing output')
          return { size: output.size }
        },
        unlinkSync(path) {
          removed.push(path)
        }
      }
    }
  }
  session = createCompressionSession({ wxApi, protectedPaths: [sourcePath] })
  return { sourcePath, wxApi, session, calls, imageInfoCalls, outputs, removed }
}

function createImageFile(patch = {}) {
  return {
    mediaType: 'IMAGE',
    tempFilePath: 'wxfile://source',
    fileName: '婚礼.jpeg',
    title: '婚礼',
    size: 2000000,
    sha256: 'old',
    ...patch
  }
}

test('略超限图片保留原尺寸并选择最高合格编码质量', async () => {
  const limit = 1000000
  const outputs = new Map()
  const calls = []
  const wxApi = {
    compressImage(args) {
      calls.push(args)
      const tempFilePath = `wxfile://q-${args.quality}`
      outputs.set(tempFilePath, Math.round(1150000 * args.quality / 100))
      args.success({ tempFilePath })
    },
    getImageInfo(args) {
      args.success({ width: 6000, height: 4000, type: 'jpeg', orientation: 'up' })
    },
    getFileSystemManager() {
      return {
        statSync(path) { return { size: outputs.get(path) || 2000000 } },
        unlinkSync() {}
      }
    }
  }
  const session = createCompressionSession({ wxApi, protectedPaths: ['wxfile://source'] })
  const file = {
    mediaType: 'IMAGE',
    tempFilePath: 'wxfile://source',
    fileName: '婚礼.jpeg',
    title: '婚礼',
    size: 2000000,
    sha256: 'old'
  }

  const result = await compressImageToLimit(file, { wxApi, session, maxBytes: limit })

  assert.equal(calls[0].quality, 100)
  assert.ok(calls.every(args => args.src === file.tempFilePath && !args.compressedWidth))
  assert.equal(result.size, 989000)
  assert.equal(result.width, 6000)
  assert.equal(result.sha256, '')
  assert.equal(result.fileName, '婚礼.jpg')
  assert.equal(result.title, '婚礼')
  session.dispose()
})

test('按实测字节处理L-1、L、L+1并只在需要时编码', async () => {
  const limit = 1000000
  for (const sourceSize of [limit - 1, limit, limit + 1]) {
    const harness = createJpegHarness({ sourceSize })
    const result = await compressImageToLimit(createImageFile({ size: 123 }), {
      wxApi: harness.wxApi,
      session: harness.session,
      maxBytes: limit
    })
    assert.equal(harness.calls.length, sourceSize > limit ? 1 : 0)
    assert.equal(result.size, sourceSize > limit ? 500000 : sourceSize)
    assert.equal(result.sha256, '')
    harness.session.dispose()
  }
})

test('仅更正原文件大小时保留默认与自定义封面指纹', async () => {
  const harness = createJpegHarness({ sourceSize: 999999 })
  const result = await compressImageToLimit(createImageFile({
    size: 900000,
    coverSha256: 'default-cover',
    customCoverPath: 'wxfile://custom-cover',
    customCoverSha256: 'custom-cover'
  }), {
    wxApi: harness.wxApi,
    session: harness.session,
    maxBytes: 1000000
  })

  assert.equal(result.coverSha256, 'default-cover')
  assert.equal(result.customCoverSha256, 'custom-cover')
  assert.equal(result.sha256, '')
  assert.equal(harness.imageInfoCalls.length, 0)
  harness.session.dispose()
})

test('非图片保持原对象且不读取文件', async () => {
  const harness = createJpegHarness({ statFailure: new Error('不应读取') })
  const file = { mediaType: 'VIDEO', tempFilePath: harness.sourcePath }
  assert.equal(await compressImageToLimit(file, {
    wxApi: harness.wxApi,
    session: harness.session,
    maxBytes: 1000000
  }), file)
  harness.session.dispose()
})

test('最高质量已低于上限时只编码一次', async () => {
  const harness = createJpegHarness({
    sourceSize: 12000000,
    encode: () => ({ size: 3000000 })
  })
  const result = await compressImageToLimit(createImageFile({ size: 12000000 }), {
    wxApi: harness.wxApi,
    session: harness.session,
    maxBytes: 10000000
  })

  assert.equal(harness.calls.length, 1)
  assert.equal(harness.calls[0].quality, 100)
  assert.equal(result.size, 3000000)
  harness.session.dispose()
})

test('原尺寸quality85仍超限才缩放并保持竖图比例', async () => {
  const limit = 1000000
  const harness = createJpegHarness({
    sourceInfo: { width: 4000, height: 6000, type: 'jpg', orientation: 'up' },
    encode(args) {
      if (!args.compressedWidth) {
        return { size: args.quality === 100 ? 1500000 : 1300000 }
      }
      return { size: 900000 }
    }
  })
  const result = await compressImageToLimit(createImageFile(), {
    wxApi: harness.wxApi,
    session: harness.session,
    maxBytes: limit
  })

  assert.deepEqual(harness.calls.slice(0, 3).map(call => call.quality), [100, 85, 85])
  assert.equal(Object.hasOwn(harness.calls[0], 'compressedWidth'), false)
  assert.equal(Object.hasOwn(harness.calls[1], 'compressedWidth'), false)
  assert.equal(harness.calls[2].compressedWidth, 3472)
  assert.ok(harness.calls.slice(2).every(call => !Object.hasOwn(call, 'compressedHeight')))
  assert.equal(result.width, 3472)
  assert.equal(result.height, 5208)
  assert.equal(result.aspectRatio, '2:3')
  assert.ok(harness.calls.every(call => call.src === harness.sourcePath))
  harness.session.dispose()
})

test('缩放后的新尺寸会重新尝试quality100', async () => {
  const harness = createJpegHarness({
    encode(args) {
      if (!args.compressedWidth) return { size: 1500000 }
      return { size: args.quality === 100 ? 999000 : 900000 }
    }
  })
  const result = await compressImageToLimit(createImageFile(), {
    wxApi: harness.wxApi,
    session: harness.session,
    maxBytes: 1000000
  })

  const resizedCalls = harness.calls.filter(call => call.compressedWidth)
  assert.equal(resizedCalls.at(-1).quality, 100)
  assert.equal(result.size, 999000)
  harness.session.dispose()
})

test('非单调输出只按实测结果保存最高合格质量', async () => {
  const sizes = new Map([
    [100, 1200000],
    [85, 900000],
    [92, 1100000],
    [88, 950000],
    [90, 1010000],
    [89, 970000]
  ])
  const harness = createJpegHarness({ encode: args => ({ size: sizes.get(args.quality) }) })
  const result = await compressImageToLimit(createImageFile(), {
    wxApi: harness.wxApi,
    session: harness.session,
    maxBytes: 1000000
  })

  assert.deepEqual(harness.calls.map(call => call.quality), [100, 85, 92, 88, 90, 89])
  assert.equal(result.size, 970000)
  harness.session.dispose()
})

test('始终超限时遵守尺寸下限与总次数上限', async () => {
  const harness = createJpegHarness({ encode: () => ({ size: 2000000 }) })
  await assert.rejects(compressImageToLimit(createImageFile(), {
    wxApi: harness.wxApi,
    session: harness.session,
    maxBytes: 1000000
  }), /图片压缩后仍超过 10MB/)

  assert.ok(harness.calls.length <= 14)
  assert.ok(harness.calls
    .filter(call => call.compressedWidth)
    .every(call => call.compressedWidth >= 1024))
  assert.equal(new Set(harness.calls.map(call => `${call.compressedWidth || 'original'}:${call.quality}`)).size,
    harness.calls.length)
  harness.session.dispose()
})

test('零字节、原图元数据失败和原生API缺失给出明确错误', async () => {
  const zero = createJpegHarness({ sourceSize: 0 })
  await assert.rejects(compressImageToLimit(createImageFile(), {
    wxApi: zero.wxApi,
    session: zero.session,
    maxBytes: 1000000
  }), /无法读取作品文件大小/)
  zero.session.dispose()

  const noInfo = createJpegHarness({ sourceInfoFailure: new Error('read failed') })
  await assert.rejects(compressImageToLimit(createImageFile(), {
    wxApi: noInfo.wxApi,
    session: noInfo.session,
    maxBytes: 1000000
  }), /无法读取图片信息/)
  noInfo.session.dispose()

  const noCompress = createJpegHarness()
  delete noCompress.wxApi.compressImage
  await assert.rejects(compressImageToLimit(createImageFile(), {
    wxApi: noCompress.wxApi,
    session: noCompress.session,
    maxBytes: 1000000
  }), /当前微信版本不支持图片压缩，请升级微信/)
  noCompress.session.dispose()
})

test('拒绝GIF源图和非JPG压缩成品', async () => {
  const png = createJpegHarness({
    sourceInfo: { width: 1000, height: 1000, type: 'gif', orientation: 'up' }
  })
  await assert.rejects(compressImageToLimit(createImageFile(), {
    wxApi: png.wxApi,
    session: png.session,
    maxBytes: 1000000
  }), /当前图片格式暂不支持压缩/)
  png.session.dispose()

  const wrongOutput = createJpegHarness({ encode: () => ({ size: 500000, type: 'png' }) })
  await assert.rejects(compressImageToLimit(createImageFile(), {
    wxApi: wrongOutput.wxApi,
    session: wrongOutput.session,
    maxBytes: 1000000
  }), /无法读取压缩后的图片信息/)
  wrongOutput.session.dispose()
})

test('已有合格候选后精调失败会回退且不再读取元数据', async () => {
  const harness = createJpegHarness({
    encode(args) {
      if (args.quality === 100) return { size: 1200000 }
      if (args.quality === 85) return { size: 900000 }
      return { fail: new Error('refine failed') }
    }
  })
  const result = await compressImageToLimit(createImageFile(), {
    wxApi: harness.wxApi,
    session: harness.session,
    maxBytes: 1000000
  })

  assert.equal(result.size, 900000)
  assert.deepEqual(harness.calls.map(call => call.quality), [100, 85, 92])
  assert.equal(harness.imageInfoCalls.length, 3)
  harness.session.dispose()
})

test('取消优先于已保存候选且停止后续搜索', async () => {
  const harness = createJpegHarness({
    encode(args, index, context) {
      if (args.quality === 100) return { size: 1200000 }
      if (args.quality === 85) return { size: 900000 }
      context.session.cancel()
      return { fail: new Error('late failure') }
    }
  })
  await assert.rejects(compressImageToLimit(createImageFile(), {
    wxApi: harness.wxApi,
    session: harness.session,
    maxBytes: 1000000
  }), { code: 'MEDIA_COMPRESSION_CANCELLED' })
  assert.deepEqual(harness.calls.map(call => call.quality), [100, 85, 92])
  harness.session.dispose()
})

test('采用新主图时更新指纹并保留有效自定义封面指纹', async () => {
  const harness = createJpegHarness({ encode: () => ({ size: 500000 }) })
  const result = await compressImageToLimit(createImageFile({
    coverSha256: 'default-cover',
    customCoverPath: 'wxfile://custom-cover',
    customCoverSha256: 'custom-cover'
  }), {
    wxApi: harness.wxApi,
    session: harness.session,
    maxBytes: 1000000
  })

  assert.equal(result.coverSha256, '')
  assert.equal(result.customCoverSha256, 'custom-cover')
  assert.equal(result.originalSize, 2000000)
  assert.equal(result.compressed, true)
  harness.session.dispose()

  const orphan = createJpegHarness({ encode: () => ({ size: 500000 }) })
  const orphanResult = await compressImageToLimit(createImageFile({ customCoverSha256: 'orphan' }), {
    wxApi: orphan.wxApi,
    session: orphan.session,
    maxBytes: 1000000
  })
  assert.equal(orphanResult.customCoverSha256, '')
  orphan.session.dispose()
})

test('onCompress仅在实测超限且首次编码前调用一次', async () => {
  const events = []
  const harness = createJpegHarness({
    encode(args) {
      events.push(`encode:${args.quality}`)
      return { size: args.quality === 100 ? 1200000 : 900000 }
    }
  })
  await compressImageToLimit(createImageFile(), {
    wxApi: harness.wxApi,
    session: harness.session,
    maxBytes: 1000000,
    onCompress() { events.push('onCompress') }
  })
  assert.deepEqual(events.slice(0, 3), ['onCompress', 'encode:100', 'encode:85'])
  assert.equal(events.filter(event => event === 'onCompress').length, 1)
  harness.session.dispose()
})

test('原生编码能力缺失时不触发onCompress', async () => {
  let compressEvents = 0
  const harness = createJpegHarness()
  delete harness.wxApi.compressImage
  await assert.rejects(compressImageToLimit(createImageFile(), {
    wxApi: harness.wxApi,
    session: harness.session,
    maxBytes: 1000000,
    onCompress() { compressEvents++ }
  }), /当前微信版本不支持图片压缩，请升级微信/)
  assert.equal(compressEvents, 0)
  harness.session.dispose()
})

test('搜索过程中立即释放淘汰候选但保留最终成品', async () => {
  const sizes = new Map([
    [100, 1200000],
    [85, 900000],
    [92, 1100000],
    [88, 950000],
    [90, 1010000],
    [89, 970000]
  ])
  const harness = createJpegHarness({ encode: args => ({ size: sizes.get(args.quality) }) })
  const result = await compressImageToLimit(createImageFile(), {
    wxApi: harness.wxApi,
    session: harness.session,
    maxBytes: 1000000
  })

  assert.equal(result.tempFilePath, 'wxfile://output-6')
  assert.deepEqual(new Set(harness.removed), new Set([
    'wxfile://output-1',
    'wxfile://output-2',
    'wxfile://output-3',
    'wxfile://output-4',
    'wxfile://output-5'
  ]))
  assert.equal(harness.removed.includes(result.tempFilePath), false)
  harness.session.dispose()
})

test('原生JPG首轮无回调时立即以图片超时结束且不会自动重试', async (t) => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 0 })
  let nativeCallbacks
  const harness = createJpegHarness({
    encode: () => ({
      pending: true,
      capture(callbacks) { nativeCallbacks = callbacks }
    })
  })
  const pending = compressImageToLimit(createImageFile(), {
    wxApi: harness.wxApi,
    session: harness.session,
    maxBytes: 1000000
  })
  const rejection = assert.rejects(pending, error => {
    assert.equal(error.code, 'MEDIA_COMPRESSION_TIMEOUT')
    assert.equal(error.message, '图片压缩超时，请重试')
    return true
  })

  await new Promise(resolve => setImmediate(resolve))
  assert.equal(harness.calls.length, 1)
  t.mock.timers.tick(30000)
  await rejection
  assert.equal(harness.calls.length, 1)
  assert.equal(harness.imageInfoCalls.length, 1)

  t.mock.timers.tick(30000)
  assert.equal(harness.calls.length, 1)
  nativeCallbacks.success({ tempFilePath: 'wxfile://late-timeout' })
  assert.deepEqual(harness.removed, ['wxfile://late-timeout'])
  harness.session.dispose()
})

test('已有合格best后精调超时返回缓存元数据且不再发起调用', async (t) => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 0 })
  let nativeCallbacks
  const harness = createJpegHarness({
    encode(args) {
      if (args.quality === 100) return { size: 1200000 }
      if (args.quality === 85) return { size: 900000 }
      return {
        pending: true,
        capture(callbacks) { nativeCallbacks = callbacks }
      }
    }
  })
  const pending = compressImageToLimit(createImageFile(), {
    wxApi: harness.wxApi,
    session: harness.session,
    maxBytes: 1000000
  })

  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(harness.calls.map(call => call.quality), [100, 85, 92])
  t.mock.timers.tick(30000)
  const result = await pending
  assert.equal(result.size, 900000)
  assert.equal(harness.calls.length, 3)
  assert.equal(harness.imageInfoCalls.length, 3)

  t.mock.timers.tick(30000)
  assert.equal(harness.calls.length, 3)
  nativeCallbacks.success({ tempFilePath: 'wxfile://late-refine' })
  assert.equal(harness.removed.includes('wxfile://late-refine'), true)
  assert.equal(harness.removed.includes('wxfile://output-2'), false)
  assert.equal(result.tempFilePath, 'wxfile://output-2')
  harness.session.dispose()
})

test('候选大小或元数据验收失败后立即清理失败输出并保留已有best', async () => {
  for (const invalid of [{ size: 0 }, { size: 950000, width: -1 }]) {
    const harness = createJpegHarness({ encode(args, index) {
      return index === 1 ? { size: 1500000 } : index === 2 ? { size: 900000 } : invalid
    } })
    const result = await compressImageToLimit(createImageFile(), {
      wxApi: harness.wxApi, session: harness.session, maxBytes: 1000000
    })
    assert.equal(result.tempFilePath, 'wxfile://output-2')
    assert.deepEqual(harness.removed, ['wxfile://output-1', 'wxfile://output-3'])
    harness.session.dispose()
  }
})

function createCanvasHarness(options = {}) {
  const sourceInfo = { width: 6000, height: 4000, type: 'webp', orientation: 'right', ...options.sourceInfo }
  const draws = [], clears = [], scans = [], exports = [], gets = [], loads = [], removed = [], infoCalls = [], debug = [], assignments = []
  const outputs = new Map()
  let session, releases = 0, currentWidth = 1, currentHeight = 1
  const context = {
    clearRect(...args) { clears.push(args) },
    drawImage(...args) { draws.push({ args, width: canvas.width, height: canvas.height }) },
    fillRect() { assert.fail('禁止抹去透明度') },
    rotate() { assert.fail('禁止再次旋转已解码图像') }
  }
  if (options.alpha !== 'unavailable') {
    context.getImageData = (x, y, width, height) => {
      scans.push({ x, y, width, height })
      if (options.onScan) options.onScan({ session, y, height })
      if (options.alpha === 'throw') throw new Error('read failed')
      if (options.alpha === 'invalid') return { data: [] }
      const data = new Uint8ClampedArray(width * height * 4).fill(255)
      if (options.alpha === 'transparent' && y + height === canvas.height) data[data.length - 1] = 0
      return { data }
    }
  }
  const canvas = {
    get width() { return currentWidth },
    set width(value) { assignments.push(['width', value]); currentWidth = value },
    get height() { return currentHeight },
    set height(value) { assignments.push(['height', value]); currentHeight = value },
    getContext() { return context },
    createImage() {
      return { width: sourceInfo.width, height: sourceInfo.height, set src(value) {
        loads.push(value)
        if (options.loadPending) return
        if (options.loadFail) this.onerror()
        else this.onload()
      } }
    }
  }
  const lease = { canvas, release() { releases++; canvas.width = 1; canvas.height = 1 } }
  const getCanvas = async args => {
    gets.push(args)
    if (options.getFail) throw options.getFail
    canvas.width = args.width
    canvas.height = args.height
    return lease
  }
  const wxApi = {
    compressImage() { assert.fail('Canvas不能经原生JPEG中间件二次编码') },
    canvasToTempFilePath(args) {
      exports.push(args)
      const descriptor = options.encode ? options.encode(args, exports.length, { session }) : { size: 990000 }
      if (descriptor.pending) { descriptor.capture(args); return }
      if (descriptor.fail) { args.fail(descriptor.fail); return }
      const tempFilePath = `wxfile://canvas-${exports.length}`
      outputs.set(tempFilePath, { size: descriptor.size, info: {
        width: descriptor.width === undefined ? args.destWidth : descriptor.width,
        height: args.destHeight, type: descriptor.type || (args.fileType === 'jpg' ? 'jpeg' : 'png'), orientation: 'up'
      } })
      args.success({ tempFilePath })
    },
    getImageInfo(args) {
      infoCalls.push(args.src)
      args.success(args.src === 'wxfile://source' ? sourceInfo : outputs.get(args.src).info)
    },
    getFileSystemManager() { return {
      statSync(path) { return { size: path === 'wxfile://source' ? 2000000 : outputs.get(path).size } },
      unlinkSync(path) { removed.push(path) }
    } }
  }
  session = createCompressionSession({ wxApi, protectedPaths: ['wxfile://source'] })
  const file = createImageFile({ fileName: '婚礼.webp', mimeType: 'image/webp', staticImageVerified: true,
    isAnimation: false, webpAlpha: 'opaque', webpAlphaHint: null, ...options.file })
  const run = extra => compressImageToLimit(file, { wxApi, session, getCanvas, maxBytes: 1000000,
    onDebug: record => debug.push(record), ...extra })
  return { canvas, context, wxApi, session, file, run, gets, draws, clears, scans, exports, loads, removed,
    assignments, infoCalls, debug, outputs, get releases() { return releases } }
}

function assertFullRaster(harness) {
  for (const { args, width, height } of harness.draws) {
    assert.equal(args.length, 5)
    assert.deepEqual(args.slice(1), [0, 0, width, height])
    assert.equal(args[0].width, 6000)
    assert.ok(width <= 4096 && height <= 4096 && width * height <= 16 * 1024 * 1024)
  }
}

test('普通PNG和透明/未知WebP直接PNG；不透明WebP最高质量JPEG只导出一次', async () => {
  for (const branch of [
    { sourceInfo: { type: 'png' }, file: { mimeType: 'image/png' }, expected: 'png' },
    { file: { webpAlpha: 'present' }, expected: 'png' },
    { file: { webpAlpha: 'unknown' }, expected: 'png' },
    { file: { webpAlpha: 'unknown', webpAlphaHint: true }, expected: 'png' },
    { expected: 'jpg' }
  ]) {
    const h = createCanvasHarness(branch)
    const result = await h.run()
    assert.equal(h.exports.length, 1)
    assert.equal(h.exports[0].fileType, branch.expected)
    assert.equal(h.exports[0].quality, branch.expected === 'jpg' ? 1 : undefined)
    assert.equal(result.mimeType, branch.expected === 'jpg' ? 'image/jpeg' : 'image/png')
    assert.equal(result.fileName, `婚礼.${branch.expected}`)
    assert.equal(h.draws.length, 1)
    assertFullRaster(h)
    assert.deepEqual(h.draws[0].args.slice(1), [0, 0, 4096, 2730])
    assert.equal(h.exports[0].destWidth, 4096)
    assert.equal(h.exports[0].destHeight, 2730)
    assert.equal(h.scans.length, 0)
    assert.equal(h.releases, 1)
    assert.equal(h.canvas.width, 1)
    assert.deepEqual(h.loads, ['wxfile://source'])
    h.session.dispose()
  }
})

test('像素读取能力缺失报告unavailable，不假定图像透明', async () => {
  const { inspectCanvasAlpha } = require('../pages/works/utils/work-image-compress')
  assert.equal(await inspectCanvasAlpha({ width: 1, height: 1, getContext() { return {} } }, { assertActive() {} }), 'unavailable')
})

test('VP8L提示false完整扫描四种状态并复用首轮栅格', async () => {
  for (const [alpha, expectedStatus, fileType] of [
    ['opaque', 'opaque', 'jpg'], ['transparent', 'transparent', 'png'],
    ['unavailable', 'unavailable', 'png'], ['throw', 'read_failed', 'png'], ['invalid', 'read_failed', 'png']
  ]) {
    const h = createCanvasHarness({ alpha, file: { webpAlpha: 'unknown', webpAlphaHint: false } })
    await h.run()
    assert.equal(h.exports[0].fileType, fileType)
    assert.equal(h.debug[0].alphaScanStatus, expectedStatus)
    assert.equal(h.debug[0].encodingBranch, fileType === 'jpg' ? 'jpeg' : 'png')
    assert.equal(h.debug[0].width, 4096)
    assert.equal(h.debug[0].height, 2730)
    assert.equal(h.debug[0].actualType, fileType === 'jpg' ? 'jpeg' : 'png')
    assert.equal(h.draws.length, 1)
    assert.equal(h.clears.length, 1)
    assert.equal(h.gets.length, 1)
    assert.equal(h.assignments.length, 4)
    assertFullRaster(h)
    if (alpha === 'opaque' || alpha === 'transparent') {
      assert.equal(h.scans.reduce((sum, strip) => sum + strip.width * strip.height, 0), 4096 * 2730)
      assert.ok(h.scans.every(strip => strip.height <= 64))
      assert.equal(h.scans.at(-1).y + h.scans.at(-1).height, 2730)
    }
    h.session.dispose()
  }
})

test('PNG超限缩放后过小会有限二分放大，候选按实际面积选择且整图重绘', async () => {
  const h = createCanvasHarness({ sourceInfo: { type: 'png' }, encode(args, index) {
    if (index === 1) return { size: 2000000 }
    if (index === 2) return { size: 700000 }
    if (index === 3) return { size: 1100000 }
    return { size: 970000 }
  } })
  const result = await h.run()
  assert.equal(h.exports.length, 4)
  assert.ok(h.exports[1].width < h.exports[0].width)
  assert.ok(h.exports[2].width > h.exports[1].width)
  assert.ok(h.exports[3].width > h.exports[1].width && h.exports[3].width < h.exports[2].width)
  assert.equal(result.tempFilePath, 'wxfile://canvas-4')
  assert.deepEqual(h.removed, ['wxfile://canvas-1', 'wxfile://canvas-3', 'wxfile://canvas-2'])
  assert.equal(h.gets.length, 4)
  assert.ok(h.gets.every(call => call.ownerToken === h.gets[0].ownerToken))
  assert.equal(h.releases, 1)
  assertFullRaster(h)
  h.session.dispose()
})

test('Canvas JPEG同尺寸精调不取节点不重绘，缩放时只新增一次完整绘制', async () => {
  const h = createCanvasHarness({ encode(args) {
    return { size: args.width === 4096 ? 1600000 : (args.quality === 1 ? 990000 : 900000) }
  } })
  await h.run()
  assert.deepEqual(h.exports.slice(0, 3).map(args => args.quality), [1, 0.85, 0.85])
  assert.equal(h.gets.length, 2)
  assert.equal(h.draws.length, 2)
  assert.equal(h.clears.length, 2)
  assert.equal(h.loads.length, 1)
  assertFullRaster(h)
  h.session.dispose()
})

test('PNG尺寸与次数下限、无重复尺寸，失败释放画布', async () => {
  const h = createCanvasHarness({ sourceInfo: { type: 'png' }, encode() { return { size: 100000000 } } })
  await assert.rejects(h.run(), /图片压缩后仍超过 10MB/)
  assert.ok(h.exports.length <= 8)
  assert.ok(h.exports.every(args => Math.max(args.width, args.height) >= 1024))
  assert.equal(new Set(h.exports.map(args => `${args.width}:${args.height}`)).size, h.exports.length)
  assert.equal(h.releases, 1)
  h.session.dispose()
})

test('拒绝动态或未确认静态WebP，Canvas缺失/加载/导出失败映射独立文案', async () => {
  for (const file of [{ isAnimation: true }, { staticImageVerified: false }]) {
    const h = createCanvasHarness({ file })
    await assert.rejects(h.run(), /当前图片格式暂不支持压缩/)
    assert.equal(h.gets.length, 0)
    h.session.dispose()
  }
  for (const options of [{ loadFail: true }, { getFail: new Error('旧裁剪文案') },
    { encode() { return { fail: new Error('export failed') } } }]) {
    const h = createCanvasHarness(options)
    await assert.rejects(h.run(), /图片处理失败，请选择较小文件后重试/)
    assert.equal(h.releases, options.getFail ? 0 : 1)
    h.session.dispose()
  }
  const h = createCanvasHarness()
  delete h.wxApi.canvasToTempFilePath
  await assert.rejects(h.run(), /图片处理失败，请选择较小文件后重试/)
  assert.equal(h.gets.length, 0)
  h.session.dispose()
})

test('Canvas成品格式错误和无效候选会立即清理，不能保留错误格式', async () => {
  for (const sourceType of ['png', 'webp']) {
    const h = createCanvasHarness({ sourceInfo: { type: sourceType }, encode() { return { size: 990000, type: 'gif' } } })
    await assert.rejects(h.run(), /无法读取压缩后的图片信息/)
    assert.deepEqual(h.removed, ['wxfile://canvas-1'])
    assert.equal(h.releases, 1)
    h.session.dispose()
  }
})

test('扫描取消与预算耗尽不降级PNG且零导出', async () => {
  const h = createCanvasHarness({ file: { webpAlpha: 'unknown', webpAlphaHint: false }, onScan({ session }) { session.cancel() } })
  await assert.rejects(h.run(), { code: 'MEDIA_COMPRESSION_CANCELLED' })
  assert.equal(h.exports.length, 0)
  assert.equal(h.releases, 1)
})

for (const sourceType of ['png', 'webp']) {
  for (const hasBest of [false, true]) {
    test(`${sourceType} ${hasBest ? '精调有best' : '首轮无best'}超时后不再调用，真实终态才释放`, async t => {
      t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 0 })
      let callbacks
      const h = createCanvasHarness({ sourceInfo: { type: sourceType }, encode(args, index) {
        if (hasBest && index === 1) return { size: 2000000 }
        if (hasBest && index === 2) return { size: 800000 }
        return { pending: true, capture(args) { callbacks = args } }
      } })
      const pending = h.run()
      const observed = hasBest ? pending : assert.rejects(pending, { code: 'MEDIA_COMPRESSION_TIMEOUT', message: '图片压缩超时，请重试' })
      await new Promise(resolve => setImmediate(resolve))
      const counts = [h.exports.length, h.gets.length, h.draws.length, h.infoCalls.length]
      t.mock.timers.tick(30000)
      const result = await observed
      if (hasBest) assert.equal(result.tempFilePath, 'wxfile://canvas-2')
      assert.equal(h.releases, 0)
      t.mock.timers.tick(30000)
      assert.equal(h.releases, 0)
      assert.deepEqual([h.exports.length, h.gets.length, h.draws.length, h.infoCalls.length], counts)
      callbacks.success({ tempFilePath: 'wxfile://late-canvas' })
      callbacks.fail(new Error('duplicate callback'))
      assert.equal(h.releases, 1)
      assert.ok(h.removed.includes('wxfile://late-canvas'))
      assert.ok(!h.removed.includes('wxfile://source'))
      if (hasBest) assert.ok(!h.removed.includes(result.tempFilePath))
      h.session.dispose()
    })
  }
}

test('Canvas取消期间继续保留像素，迟到导出终态幂等释放', async () => {
  let callbacks
  const h = createCanvasHarness({ encode() { return { pending: true, capture(args) { callbacks = args } } } })
  const pending = h.run()
  const rejected = assert.rejects(pending, { code: 'MEDIA_COMPRESSION_CANCELLED' })
  await new Promise(resolve => setImmediate(resolve))
  h.session.cancel()
  await rejected
  assert.equal(h.releases, 0)
  assert.equal(h.canvas.width, 4096)
  callbacks.success({ tempFilePath: 'wxfile://cancelled-output' })
  callbacks.success({ tempFilePath: 'wxfile://cancelled-output' })
  assert.equal(h.releases, 1)
  assert.deepEqual(h.removed, ['wxfile://cancelled-output'])
})

test('Canvas初始尺寸限制最长边和面积，原图较小时不放大', () => {
  const { initialCanvasSize } = require('../pages/works/utils/work-image-compress')
  for (const [width, height] of [[9000, 9000], [8000, 12000], [400, 300]]) {
    const size = initialCanvasSize(width, height)
    assert.ok(size.width <= width && size.height <= height)
    assert.ok(Math.max(size.width, size.height) <= 4096)
    assert.ok(size.width * size.height <= 16 * 1024 * 1024)
  }
  assert.deepEqual(initialCanvasSize(400, 300), { width: 400, height: 300 })
})

test('VP8L扫描剩余预算耗尽或原取消错误不变成读取失败', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 0 })
  const h = createCanvasHarness({ file: { webpAlpha: 'unknown', webpAlphaHint: false },
    onScan() { t.mock.timers.tick(120000) } })
  await assert.rejects(h.run(), { code: 'MEDIA_COMPRESSION_TIMEOUT', message: '图片压缩超时，请重试' })
  assert.equal(h.exports.length, 0)
  assert.equal(h.releases, 1)
  h.session.dispose()
  const { inspectCanvasAlpha } = require('../pages/works/utils/work-image-compress')
  for (const code of ['MEDIA_COMPRESSION_CANCELLED', 'MEDIA_COMPRESSION_TIMEOUT']) {
    const error = Object.assign(new Error('原错误'), { code })
    await assert.rejects(inspectCanvasAlpha({ width: 1, height: 1, getContext() {
      return { getImageData() { throw error } }
    } }, { assertActive() {} }), actual => actual === error)
  }
})

test('图像加载超时映射图片超时，无原生导出时立即释放', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 0 })
  const h = createCanvasHarness({ loadPending: true })
  const pending = assert.rejects(h.run(), { code: 'MEDIA_COMPRESSION_TIMEOUT', message: '图片压缩超时，请重试' })
  await new Promise(resolve => setImmediate(resolve))
  t.mock.timers.tick(10000)
  await pending
  assert.equal(h.releases, 1)
  assert.equal(h.exports.length, 0)
  h.session.dispose()
})

test('VP8L扫描后的JPEG质量精调只绘制一次，新图片另建owner并从原图加载', async () => {
  const h = createCanvasHarness({ file: { webpAlpha: 'unknown', webpAlphaHint: false }, encode(args) {
    return { size: args.quality === 1 ? 1200000 : 990000 }
  } })
  await h.run()
  assert.ok(h.exports.length > 2)
  assert.equal(h.draws.length, 1)
  assert.equal(h.gets.length, 1)
  assert.equal(h.assignments.length, 4)
  const previousToken = h.gets[0].ownerToken
  await h.run()
  assert.equal(h.draws.length, 2)
  assert.equal(h.gets.length, 2)
  assert.equal(h.loads.length, 2)
  assert.notEqual(h.gets[1].ownerToken, previousToken)
  assert.equal(h.releases, 2)
  h.session.dispose()
})

test('PNG精调出错清理本轮无效产物并返回best；次数耗尽选实际最大面积', async () => {
  for (const invalid of [{ size: 0 }, { size: 950000, width: -1 }, { fail: new Error('refinement failed') }]) {
    const h = createCanvasHarness({ sourceInfo: { type: 'png' }, encode(args, index) {
      return index === 1 ? { size: 2000000 } : index === 2 ? { size: 800000 } : invalid
    } })
    const result = await h.run()
    assert.equal(result.tempFilePath, 'wxfile://canvas-2')
    assert.ok(!h.removed.includes(result.tempFilePath))
    if (!invalid.fail) assert.ok(h.removed.includes('wxfile://canvas-3'))
    h.session.dispose()
  }
  const h = createCanvasHarness({ sourceInfo: { type: 'png' }, encode(args, index) {
    return index === 1 ? { size: 2000000 } : { size: 800000, width: index === 2 ? 4000 : 2500 }
  } })
  const result = await h.run()
  assert.equal(h.exports.length, 8)
  assert.equal(result.tempFilePath, 'wxfile://canvas-2')
  h.session.dispose()
})

test('Canvas节点忙保留原错误，编码锁等待取消时释放尚未导出的节点', async () => {
  const error = Object.assign(new Error('画布处理尚未结束，请返回后重新进入'), { code: 'MEDIA_COMPRESSION_CANVAS_BUSY' })
  const h = createCanvasHarness({ getFail: error })
  await assert.rejects(h.run(), actual => actual === error)
  assert.equal(h.releases, 0)
  h.session.dispose()

  const busy = createCanvasHarness()
  const owner = createCompressionSession({ wxApi: busy.wxApi })
  let nativeCallback
  const occupied = owner.call(args => { nativeCallback = args }, {}, {
    encoding: true, timeoutMs: 30000, nominalTimeoutMs: 30000
  })
  const pending = busy.run()
  const cancelled = assert.rejects(pending, { code: 'MEDIA_COMPRESSION_CANCELLED' })
  await new Promise(setImmediate)
  assert.equal(busy.exports.length, 0)
  assert.equal(busy.releases, 0)
  busy.session.cancel()
  await cancelled
  assert.equal(busy.releases, 1)
  nativeCallback.success({})
  await occupied
  owner.dispose()
})

test('本地诊断只含枚举尺寸耗时，默认不开启且回调故障不影响成品', async () => {
  const h = createCanvasHarness()
  await h.run()
  assert.deepEqual(Object.keys(h.debug[0]).sort(), [
    'event', 'alphaScanStatus', 'encodingBranch', 'width', 'height', 'alphaScanElapsedMs', 'actualType'
  ].sort())
  assert.equal(h.debug[0].alphaScanStatus, 'not_required')
  assert.equal(h.debug[0].alphaScanElapsedMs, 0)
  await h.run({ onDebug: undefined })
  assert.equal(h.debug.length, 1)
  await h.run({ onDebug() { throw new Error('debug failed') } })
  h.session.dispose()
})

test('极窄竖PNG缩放按长边推进，不因宽度取整不变突破4096上限', async () => {
  const h = createCanvasHarness({ sourceInfo: { type: 'png', width: 1, height: 9000 }, encode(args, index) {
    return { size: index === 1 ? 2000000 : 970000 }
  } })
  const result = await h.run()
  assert.equal(h.exports.length, 2)
  assert.ok(h.exports[1].height < h.exports[0].height)
  assert.ok(h.exports.every(args => args.height <= 4096 && args.width === 1))
  assert.ok(result.height >= 1024)
  h.session.dispose()
})
