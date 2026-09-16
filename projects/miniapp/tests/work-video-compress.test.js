const assert = require('node:assert/strict')
const test = require('node:test')

const { createCompressionSession } = require('../pages/works/utils/work-compression-runtime')
const { compressVideoToLimit } = require('../pages/works/utils/work-video-compress')

const LIMIT = 100 * 1024 * 1024
const SOURCE_PATH = 'wxfile://source'
const SOURCE_INFO = {
  width: 1920,
  height: 1080,
  duration: 60,
  fps: 30,
  bitrate: 18000,
  type: 'mov',
  size: 1
}

function createVideoHarness(options = {}) {
  const calls = []
  const infoCalls = []
  const removed = []
  const outputSizes = options.outputSizes || [Math.floor(LIMIT * 0.98)]
  const outputInfos = options.outputInfos || []
  const outputPaths = options.outputPaths || outputSizes.map((_, index) => `wxfile://output-${index + 1}`)
  const failures = options.failures || []
  const sourceInfo = { ...SOURCE_INFO, ...(options.sourceInfo || {}) }
  const sizeByPath = new Map([[SOURCE_PATH, options.sourceSize ?? LIMIT + 1]])
  outputPaths.forEach((path, index) => sizeByPath.set(path, outputSizes[index]))
  const wxApi = {
    getVideoInfo(args) {
      infoCalls.push(args.src)
      const outputIndex = outputPaths.indexOf(args.src)
      if (outputIndex < 0) {
        args.success({ ...sourceInfo })
        return
      }
      const info = outputInfos[outputIndex] || {
        ...sourceInfo,
        type: 'mp4',
        bitrate: calls[outputIndex] && calls[outputIndex].bitrate
      }
      args.success({ ...info })
    },
    compressVideo(args) {
      const index = calls.length
      calls.push(args)
      if (failures[index]) {
        args.fail(failures[index])
        return
      }
      args.success({ tempFilePath: outputPaths[index], size: 1 })
    },
    getFileSystemManager() {
      return {
        statSync(path) {
          if (!sizeByPath.has(path)) {
            throw new Error(`missing stat fixture: ${path}`)
          }
          return { size: sizeByPath.get(path) }
        },
        unlinkSync(path) {
          removed.push(path)
        }
      }
    }
  }
  const session = createCompressionSession({ wxApi, protectedPaths: [SOURCE_PATH] })
  return { wxApi, session, calls, infoCalls, removed, sourceInfo, outputPaths }
}

function videoFile(patch = {}) {
  return {
    mediaType: 'VIDEO',
    tempFilePath: SOURCE_PATH,
    fileName: '演出.mov',
    title: '演出',
    size: LIMIT + 1,
    sha256: 'old',
    ...patch
  }
}

async function runCompression(harness, file = videoFile(), options = {}) {
  try {
    return await compressVideoToLimit(file, {
      wxApi: harness.wxApi,
      session: harness.session,
      maxBytes: LIMIT,
      maxDurationMs: 600000,
      ...options
    })
  } finally {
    harness.session.dispose()
  }
}

test('视频以stat字节验收，并用成品更新票据信息', async () => {
  const limit = 100 * 1024 * 1024
  const calls = []
  const wxApi = {
    getVideoInfo(args) {
      args.success({
        width: 1920,
        height: 1080,
        duration: 60,
        fps: 30,
        bitrate: 18000,
        type: args.src === 'wxfile://source' ? 'mov' : 'mp4',
        size: 1
      })
    },
    compressVideo(args) {
      calls.push(args)
      args.success({ tempFilePath: 'wxfile://compressed', size: 1 })
    },
    getFileSystemManager() {
      return {
        statSync(path) {
          return { size: path === 'wxfile://source' ? limit + 1 : Math.floor(limit * 0.98) }
        },
        unlinkSync() {}
      }
    }
  }
  const session = createCompressionSession({ wxApi, protectedPaths: ['wxfile://source'] })
  const file = {
    mediaType: 'VIDEO',
    tempFilePath: 'wxfile://source',
    fileName: '演出.mov',
    title: '演出',
    size: limit + 1,
    sha256: 'old'
  }
  const result = await compressVideoToLimit(file, {
    wxApi,
    session,
    maxBytes: limit,
    maxDurationMs: 600000
  })
  assert.equal(calls.length, 1)
  assert.equal(calls[0].src, file.tempFilePath)
  assert.equal(Object.hasOwn(calls[0], 'quality'), false)
  assert.ok(calls[0].bitrate > 1000)
  const targetBytes = Math.floor(0.98 * limit)
  const firstKbps = Math.floor((targetBytes - Math.ceil(0.01 * targetBytes)
    - Math.ceil(192 * 1000 * 60 / 8)) * 8 / 60 / 1000)
  const r = Math.min(1, Math.sqrt(firstKbps * 1000 / (1920 * 1080 * 30 * 0.045)))
  assert.equal(calls[0].bitrate, firstKbps)
  assert.ok(Number.isFinite(calls[0].resolution)
    && calls[0].resolution > 0
    && calls[0].resolution <= 1)
  assert.equal(calls[0].resolution, r)
  assert.equal(calls[0].fps, 30)
  assert.equal(result.size, Math.floor(limit * 0.98))
  assert.equal(result.fileName, '演出.mp4')
  assert.equal(result.durationMs, 60000)
  assert.equal(result.sha256, '')
  assert.equal(result.compressed, true)
  assert.equal(result.originalSize, limit + 1)
  session.dispose()
})

test('非视频直接保留，不读取文件或视频信息', async () => {
  const harness = createVideoHarness()
  const file = videoFile({ mediaType: 'IMAGE' })
  const result = await runCompression(harness, file)
  assert.equal(result, file)
  assert.deepEqual(harness.calls, [])
  assert.deepEqual(harness.infoCalls, [])
})

test('视频大小边界以stat为准，只有L+1进入编码', async (t) => {
  for (const [label, sourceSize, expectedCalls] of [
    ['L-1', LIMIT - 1, 0],
    ['L', LIMIT, 0],
    ['L+1', LIMIT + 1, 1]
  ]) {
    await t.test(label, async () => {
      const harness = createVideoHarness({ sourceSize })
      const result = await runCompression(harness, videoFile({ size: 1 }))
      assert.equal(harness.calls.length, expectedCalls)
      assert.equal(result.size, expectedCalls ? Math.floor(LIMIT * 0.98) : sourceSize)
    })
  }
})

test('未压缩视频仅在stat大小变化时清空主SHA并保留封面指纹', async (t) => {
  for (const [label, pickerSize, expectedSha256] of [
    ['大小未变', LIMIT - 1, 'main-sha'],
    ['大小变化', 1, '']
  ]) {
    await t.test(label, async () => {
      const harness = createVideoHarness({ sourceSize: LIMIT - 1 })
      delete harness.wxApi.getVideoInfo
      const file = videoFile({
        size: pickerSize,
        sha256: 'main-sha',
        width: 1920,
        height: 1080,
        durationMs: 60000,
        fps: 24,
        mimeType: 'video/quicktime',
        aspectRatio: '16:9',
        coverSha256: 'default-cover-sha',
        customCoverPath: 'wxfile://custom-cover',
        customCoverSha256: 'custom-cover-sha'
      })
      const result = await runCompression(harness, file)
      assert.equal(harness.calls.length, 0)
      assert.deepEqual(harness.infoCalls, [])
      assert.equal(result.sha256, expectedSha256)
      assert.equal(result.width, file.width)
      assert.equal(result.height, file.height)
      assert.equal(result.durationMs, file.durationMs)
      assert.equal(result.fps, file.fps)
      assert.equal(result.mimeType, file.mimeType)
      assert.equal(result.aspectRatio, file.aspectRatio)
      assert.equal(result.coverSha256, 'default-cover-sha')
      assert.equal(result.customCoverSha256, 'custom-cover-sha')
    })
  }
})

test('视频时长边界允许600000ms并拒绝任何真实超时值', async (t) => {
  for (const [duration, shouldPass] of [[600, true], [600.0001, false], [600.001, false]]) {
    await t.test(String(duration), async () => {
      if (shouldPass) {
        const targetBytes = Math.floor(0.98 * LIMIT)
        const firstKbps = Math.floor((targetBytes - Math.ceil(0.01 * targetBytes)
          - Math.ceil(192 * 1000 * duration / 8)) * 8 / duration / 1000)
        const r = Math.sqrt(firstKbps * 1000 / (1920 * 1080 * 30 * 0.045))
        const harness = createVideoHarness({
          sourceInfo: { duration },
          outputInfos: [{
            width: Math.floor(1920 * r),
            height: Math.floor(1080 * r),
            duration,
            fps: 30,
            type: 'mp4'
          }]
        })
        await runCompression(harness)
        assert.equal(harness.calls.length, 1)
      } else {
        const harness = createVideoHarness({ sourceInfo: { duration } })
        await assert.rejects(runCompression(harness), { message: '视频作品不能超过 10 分钟' })
        assert.equal(harness.calls.length, 0)
      }
    })
  }
})

test('源fps无效时显式请求30且不伪造已知源fps', async (t) => {
  for (const fps of [undefined, 0, -1, NaN, Infinity]) {
    await t.test(String(fps), async () => {
      const harness = createVideoHarness({ sourceInfo: { fps } })
      await runCompression(harness)
      assert.equal(harness.calls[0].fps, 30)
      assert.equal(Object.hasOwn(harness.calls[0], 'sourceFpsKnown'), false)
    })
  }
})

test('源时长或宽高无效时在编码前失败', async (t) => {
  for (const sourceInfo of [
    { duration: undefined },
    { duration: 0 },
    { width: undefined },
    { width: 0 },
    { height: Infinity }
  ]) {
    await t.test(JSON.stringify(sourceInfo), async () => {
      const harness = createVideoHarness({ sourceInfo })
      await assert.rejects(runCompression(harness), {
        message: '无法读取视频信息，请重新选择'
      })
      assert.equal(harness.calls.length, 0)
    })
  }
})

test('源视频信息缺失时使用同一选择文件的完整宽高和时长', async (t) => {
  for (const sourceInfo of [
    { width: undefined, height: undefined, duration: undefined },
    { width: 0, height: 0, duration: 0 },
    { width: 1080, height: 0, duration: 60 },
    { width: NaN, height: Infinity, duration: NaN }
  ]) {
    await t.test(JSON.stringify(sourceInfo), async () => {
      const harness = createVideoHarness({
        sourceInfo,
        outputInfos: [{ width: 1920, height: 1080, duration: 60, fps: 30, type: 'mp4' }]
      })
      const result = await runCompression(harness, videoFile({ width: 1920, height: 1080, durationMs: 60000 }))
      assert.equal(result.compressed, true)
      assert.equal(result.durationMs, 60000)
      assert.equal(result.width, 1920)
      assert.equal(result.height, 1080)
      assert.equal(harness.calls.length, 1)
    })
  }
})

test('有效原生源视频信息优先于选择器的旧信息', async () => {
  const harness = createVideoHarness()
  const result = await runCompression(harness, videoFile({ width: 100, height: 100, durationMs: 601000 }))
  assert.equal(result.durationMs, 60000)
  assert.equal(result.width, 1920)
  assert.equal(result.height, 1080)
})

test('原生读取失败、超时和取消不能被选择器信息掩盖', async (t) => {
  for (const code of ['NATIVE_FAILED', 'MEDIA_COMPRESSION_TIMEOUT', 'MEDIA_COMPRESSION_CANCELLED']) {
    await t.test(code, async () => {
      const harness = createVideoHarness()
      harness.wxApi.getVideoInfo = args => args.fail({ code, errMsg: 'getVideoInfo:fail' })
      await assert.rejects(runCompression(harness, videoFile({ width: 1920, height: 1080, durationMs: 60000 })), { code })
      assert.equal(harness.calls.length, 0)
    })
  }
})

test('压缩成品缺少时长不能使用原片或选择器时长通过验收', async () => {
  const harness = createVideoHarness({
    outputInfos: [{ width: 1920, height: 1080, duration: 0, fps: 30, type: 'mp4' }]
  })
  await assert.rejects(runCompression(harness, videoFile({ width: 1920, height: 1080, durationMs: 60000 })), {
    message: '无法读取压缩后的视频信息，请重新选择'
  })
  assert.ok(harness.removed.includes(harness.outputPaths[0]))
})

test('4K短片预算充足时保留原分辨率比例', async () => {
  const harness = createVideoHarness({
    sourceInfo: { width: 3840, height: 2160, duration: 5, bitrate: 120000 },
    outputInfos: [{ width: 3840, height: 2160, duration: 5, fps: 30, type: 'mp4' }]
  })
  await runCompression(harness)
  assert.equal(harness.calls[0].resolution, 1)
})

test('4K十分钟视频按预算比例请求分辨率', async () => {
  const duration = 600
  const targetBytes = Math.floor(0.98 * LIMIT)
  const firstKbps = Math.floor((targetBytes - Math.ceil(0.01 * targetBytes)
    - Math.ceil(192 * 1000 * duration / 8)) * 8 / duration / 1000)
  const r = Math.min(1, Math.sqrt(firstKbps * 1000 / (3840 * 2160 * 30 * 0.045)))
  const width = Math.floor(3840 * r)
  const height = Math.floor(2160 * r)
  const harness = createVideoHarness({
    sourceInfo: { width: 3840, height: 2160, duration, bitrate: 120000 },
    outputInfos: [{ width, height, duration, fps: 30, type: 'mp4' }]
  })
  await runCompression(harness)
  assert.equal(harness.calls[0].resolution, r)
  assert.ok(harness.calls[0].resolution > 0 && harness.calls[0].resolution < 1)
})

test('多轮搜索固定原片、fps和resolution且只改变码率', async () => {
  const harness = createVideoHarness({
    outputSizes: [LIMIT + 1024, Math.floor(LIMIT * 0.98)],
    outputInfos: [
      { width: 1920, height: 1080, duration: 60, fps: 30, type: 'mp4' },
      { width: 1920, height: 1080, duration: 60, fps: 30, type: 'mp4' }
    ]
  })
  await runCompression(harness)
  assert.equal(harness.calls.length, 2)
  assert.deepEqual(harness.calls.map(call => call.src), [SOURCE_PATH, SOURCE_PATH])
  assert.deepEqual(new Set(harness.calls.map(call => call.fps)), new Set([30]))
  assert.deepEqual(new Set(harness.calls.map(call => call.resolution)).size, 1)
  assert.notEqual(harness.calls[0].bitrate, harness.calls[1].bitrate)
  assert.ok(harness.calls[0].bitrate > 2800)
  assert.ok(harness.calls.every(call => !Object.hasOwn(call, 'quality')))
})

test('首轮超限仍读取成品元数据', async () => {
  const harness = createVideoHarness({
    outputSizes: [LIMIT + 1024, Math.floor(LIMIT * 0.98)]
  })
  await runCompression(harness)
  assert.deepEqual(harness.infoCalls.slice(0, 3), [SOURCE_PATH, harness.outputPaths[0], harness.outputPaths[1]])
})

test('高帧率导致短边不足时降为30fps后重算resolution', async () => {
  const harness = createVideoHarness({
    sourceInfo: { width: 3840, height: 1600, duration: 600, fps: 60, bitrate: 120000 },
    outputInfos: [{ width: 1438, height: 599, duration: 600, fps: 30, type: 'mp4' }]
  })
  await runCompression(harness)
  assert.equal(harness.calls[0].fps, 30)
  assert.ok(harness.calls[0].resolution > 0.3 && harness.calls[0].resolution < 1)
})

test('偶数对齐后的实测尺寸会抬高下一轮质量码率下限', async () => {
  const actualMin = Math.ceil(1248 * 704 * 30 * 0.045 / 1000)
  const harness = createVideoHarness({
    sourceInfo: { width: 3840, height: 2160, duration: 600, bitrate: 120000 },
    outputSizes: [60 * 1024 * 1024, Math.floor(LIMIT * 0.98)],
    outputInfos: [
      { width: 1248, height: 704, duration: 600, fps: 30, type: 'mp4' },
      { width: 1248, height: 704, duration: 600, fps: 30, type: 'mp4' }
    ]
  })
  await runCompression(harness)
  assert.equal(harness.calls.length, 2)
  assert.ok(harness.calls[0].bitrate < actualMin)
  assert.equal(harness.calls[1].bitrate, actualMin)
})

test('原生忽略resolution时不保留低于实测Kmin的成品', async () => {
  const actualMin = Math.ceil(3840 * 2160 * 30 * 0.045 / 1000)
  const harness = createVideoHarness({
    sourceInfo: { width: 3840, height: 2160, duration: 600, bitrate: 120000 },
    outputSizes: [60 * 1024 * 1024, Math.floor(LIMIT * 0.98)],
    outputInfos: [
      { width: 3840, height: 2160, duration: 600, fps: 30, type: 'mp4' },
      { width: 3840, height: 2160, duration: 600, fps: 30, type: 'mp4' }
    ]
  })
  const result = await runCompression(harness)
  assert.equal(harness.calls[1].bitrate, actualMin)
  assert.equal(result.tempFilePath, harness.outputPaths[1])
  assert.ok(harness.removed.includes(harness.outputPaths[0]))
})

test('后续成品尺寸变化时再次校准实测Kmin', async () => {
  const firstActualMin = Math.ceil(1248 * 704 * 30 * 0.045 / 1000)
  const secondActualMin = Math.ceil(1264 * 704 * 30 * 0.045 / 1000)
  const harness = createVideoHarness({
    sourceInfo: { width: 3840, height: 2160, duration: 600, bitrate: 120000 },
    outputSizes: [60 * 1024 * 1024, 61 * 1024 * 1024, Math.floor(LIMIT * 0.98)],
    outputInfos: [
      { width: 1248, height: 704, duration: 600, fps: 30, type: 'mp4' },
      { width: 1264, height: 704, duration: 600, fps: 30, type: 'mp4' },
      { width: 1264, height: 704, duration: 600, fps: 30, type: 'mp4' }
    ]
  })
  await runCompression(harness)
  assert.deepEqual(harness.calls.map(call => call.bitrate), [harness.calls[0].bitrate, firstActualMin, secondActualMin])
})

test('成品格式映射来自getVideoInfo而不是路径后缀', async (t) => {
  for (const [type, mimeType, extension] of [
    ['mp4', 'video/mp4', 'mp4'],
    ['mov', 'video/quicktime', 'mov'],
    ['m4v', 'video/x-m4v', 'm4v'],
    ['video/mp4', 'video/mp4', 'mp4'],
    ['video/quicktime', 'video/quicktime', 'mov'],
    ['video/x-m4v', 'video/x-m4v', 'm4v']
  ]) {
    await t.test(type, async () => {
      const harness = createVideoHarness({
        outputPaths: ['wxfile://misleading.bin'],
        outputInfos: [{ width: 1920, height: 1080, duration: 60, fps: 30, type }]
      })
      const result = await runCompression(harness)
      assert.equal(result.mimeType, mimeType)
      assert.equal(result.type, mimeType)
      assert.equal(result.fileName, `演出.${extension}`)
    })
  }
})

test('DJI样本的开发者工具元数据形态可以完成压缩结果验收', async () => {
  // 源信息复现安装版开发者工具对样本的解析结果，原生编码结果仍由替身提供。
  const sourceInfo = {
    width: 3840,
    height: 2160,
    duration: 27,
    fps: 29,
    bitrate: 82989,
    type: 'video/mp4',
    orientation: 'up',
    size: 283263,
    errMsg: 'getVideoInfo:ok'
  }
  const originalSize = 290061234
  const outputSize = Math.floor(LIMIT * 0.98)
  const harness = createVideoHarness({
    sourceSize: originalSize,
    sourceInfo,
    outputSizes: [outputSize],
    outputInfos: [{ ...sourceInfo, bitrate: 29951, size: 100352 }]
  })
  const result = await runCompression(harness, videoFile({
    fileName: 'DJI_20260517171115_0202_D.MP4',
    size: originalSize
  }))
  assert.equal(harness.calls.length, 1)
  assert.equal(harness.calls[0].bitrate, 29951)
  assert.equal(harness.calls[0].fps, 29)
  assert.equal(harness.calls[0].resolution, 1)
  assert.equal(result.tempFilePath, harness.outputPaths[0])
  assert.equal(result.size, outputSize)
  assert.equal(result.originalSize, originalSize)
  assert.equal(result.fileName, 'DJI_20260517171115_0202_D.mp4')
  assert.equal(result.mimeType, 'video/mp4')
  assert.equal(result.type, 'video/mp4')
  assert.equal(result.durationMs, 27000)
  assert.equal(result.compressed, true)
})

test('成品元数据和几何质量边界分别保留正确错误语义', async (t) => {
  const cases = [
    ['空文件', { outputSizes: [0] }, undefined],
    ['异常宽度', { outputInfos: [{ width: 0, height: 1080, duration: 60, fps: 30, type: 'mp4' }] }, undefined],
    ['未知格式', { outputInfos: [{ width: 1920, height: 1080, duration: 60, fps: 30, type: 'avi' }] }, undefined],
    ['未知MIME格式', { outputInfos: [{ width: 1920, height: 1080, duration: 60, fps: 30, type: 'video/x-msvideo' }] }, undefined],
    ['短边低于下限', { outputInfos: [{ width: 800, height: 479, duration: 60, fps: 30, type: 'mp4' }] }, 'MEDIA_COMPRESSION_VIDEO_QUALITY_LIMIT'],
    ['像素面积变大', { outputInfos: [{ width: 2560, height: 1440, duration: 60, fps: 30, type: 'mp4' }] }, 'MEDIA_COMPRESSION_VIDEO_QUALITY_LIMIT']
  ]
  for (const [label, options, code] of cases) {
    await t.test(label, async () => {
      const harness = createVideoHarness(options)
      const predicate = code
        ? error => error.code === code
        : error => error.message === '无法读取压缩后的视频信息，请重新选择'
      await assert.rejects(runCompression(harness), predicate)
    })
  }
})

test('成品时长容差包含100ms边界并拒绝越界值', async (t) => {
  for (const [duration, shouldPass] of [[60.1, true], [60.1001, false]]) {
    await t.test(String(duration), async () => {
      const harness = createVideoHarness({
        outputInfos: [{ width: 1920, height: 1080, duration, fps: 30, type: 'mp4' }]
      })
      if (shouldPass) {
        await runCompression(harness)
      } else {
        await assert.rejects(runCompression(harness), {
          message: '无法读取压缩后的视频信息，请重新选择'
        })
      }
    })
  }
})

test('成品时长容差比较源片和成品的原始毫秒值', async () => {
  const harness = createVideoHarness({
    sourceInfo: { duration: 60.0006 },
    outputInfos: [{
      width: 1920,
      height: 1080,
      duration: 60.1009,
      fps: 30,
      type: 'mp4'
    }]
  })
  await assert.rejects(runCompression(harness), {
    message: '无法读取压缩后的视频信息，请重新选择'
  })
})

test('第一次过小会提高码率，第二次接近上限即接受', async () => {
  const harness = createVideoHarness({
    outputSizes: [60 * 1024 * 1024, Math.floor(LIMIT * 0.97)]
  })
  const result = await runCompression(harness)
  assert.equal(harness.calls.length, 2)
  assert.ok(harness.calls[1].bitrate > harness.calls[0].bitrate)
  assert.equal(result.tempFilePath, harness.outputPaths[1])
})

test('质量合格但三轮都超限时保留原大小错误', async () => {
  const harness = createVideoHarness({
    outputSizes: [LIMIT + 3000, LIMIT + 2000, LIMIT + 1000]
  })
  await assert.rejects(runCompression(harness), {
    message: '视频压缩后仍超过 100MB，请选择较小文件'
  })
  assert.equal(harness.calls.length, 3)
})

test('已有候选后原生失败会返回候选', async () => {
  const nativeError = Object.assign(new Error('编码器故障'), { code: 'NATIVE_FAILED' })
  const harness = createVideoHarness({
    outputSizes: [60 * 1024 * 1024, 1],
    failures: [null, nativeError]
  })
  const result = await runCompression(harness)
  assert.equal(result.tempFilePath, harness.outputPaths[0])
  assert.equal(harness.calls.length, 2)
})

test('已有候选后原生超时会返回候选且不再启动编码', async () => {
  const timeout = Object.assign(new Error('视频压缩超时，请重试'), {
    code: 'MEDIA_COMPRESSION_TIMEOUT'
  })
  const harness = createVideoHarness({
    outputSizes: [60 * 1024 * 1024, 1],
    failures: [null, timeout]
  })
  const result = await runCompression(harness)
  assert.equal(result.tempFilePath, harness.outputPaths[0])
  assert.equal(harness.calls.length, 2)
})

test('已有候选后总预算耗尽会返回最佳候选', async () => {
  const originalNow = Date.now
  let now = 1000
  Date.now = () => now
  const harness = createVideoHarness({
    sourceInfo: { bitrate: undefined },
    outputSizes: [60 * 1024 * 1024, 80 * 1024 * 1024, 1]
  })
  const originalGetVideoInfo = harness.wxApi.getVideoInfo
  harness.wxApi.getVideoInfo = args => {
    originalGetVideoInfo(args)
    if (args.src === harness.outputPaths[1]) {
      now += 20 * 60 * 1000 + 1
    }
  }
  try {
    const result = await runCompression(harness)
    assert.equal(result.tempFilePath, harness.outputPaths[1])
    assert.equal(harness.calls.length, 2)
  } finally {
    Date.now = originalNow
  }
})

test('已有候选后下一轮开始前取消仍优先抛出取消错误', async () => {
  const harness = createVideoHarness({
    sourceInfo: { bitrate: undefined },
    outputSizes: [60 * 1024 * 1024, 80 * 1024 * 1024, 1]
  })
  const originalGetVideoInfo = harness.wxApi.getVideoInfo
  harness.wxApi.getVideoInfo = args => {
    originalGetVideoInfo(args)
    if (args.src === harness.outputPaths[1]) {
      harness.session.cancel()
    }
  }
  await assert.rejects(runCompression(harness), {
    code: 'MEDIA_COMPRESSION_CANCELLED'
  })
  assert.equal(harness.calls.length, 2)
})

test('已有候选后取消仍抛出取消错误', async () => {
  const cancelled = Object.assign(new Error('已取消处理'), {
    code: 'MEDIA_COMPRESSION_CANCELLED'
  })
  const harness = createVideoHarness({
    outputSizes: [60 * 1024 * 1024, 1],
    failures: [null, cancelled]
  })
  await assert.rejects(runCompression(harness), {
    code: 'MEDIA_COMPRESSION_CANCELLED'
  })
})

test('无候选时保留原生失败和超时错误且不自动重试', async (t) => {
  for (const [error, expectedMessage] of [
    [Object.assign(new Error('原生失败'), { code: 'NATIVE_FAILED' }), '原生失败'],
    [Object.assign(new Error('媒体处理超时'), { code: 'MEDIA_COMPRESSION_TIMEOUT' }), '视频压缩超时，请重试']
  ]) {
    await t.test(error.code, async () => {
      const harness = createVideoHarness({ failures: [error] })
      await assert.rejects(runCompression(harness), {
        code: error.code,
        message: expectedMessage
      })
      assert.equal(harness.calls.length, 1)
    })
  }
})

test('最高可信原码率已到达时直接使用更小的合格成品', async () => {
  const harness = createVideoHarness({
    sourceInfo: { bitrate: 3000 },
    outputSizes: [60 * 1024 * 1024]
  })
  const result = await runCompression(harness)
  assert.equal(harness.calls.length, 1)
  assert.equal(harness.calls[0].bitrate, 3000)
  assert.equal(result.tempFilePath, harness.outputPaths[0])
})

test('提高码率后同尺寸成品未变大时停止继续填充', async () => {
  const harness = createVideoHarness({
    outputSizes: [60 * 1024 * 1024, 59 * 1024 * 1024]
  })
  const result = await runCompression(harness)
  assert.equal(harness.calls.length, 2)
  assert.equal(result.tempFilePath, harness.outputPaths[1])
})

test('候选以像素面积和请求码率排序，不以文件大小排序', async () => {
  const harness = createVideoHarness({
    outputSizes: [70 * 1024 * 1024, 60 * 1024 * 1024],
    outputInfos: [
      { width: 1280, height: 720, duration: 60, fps: 30, type: 'mp4' },
      { width: 1920, height: 1080, duration: 60, fps: 30, type: 'mp4' }
    ]
  })
  const result = await runCompression(harness)
  assert.equal(result.tempFilePath, harness.outputPaths[1])
})

test('已有候选后遇到质量下限失败会返回候选', async () => {
  const harness = createVideoHarness({
    outputSizes: [60 * 1024 * 1024, 61 * 1024 * 1024],
    outputInfos: [
      { width: 1920, height: 1080, duration: 60, fps: 30, type: 'mp4' },
      { width: 800, height: 479, duration: 60, fps: 30, type: 'mp4' }
    ]
  })
  const result = await runCompression(harness)
  assert.equal(result.tempFilePath, harness.outputPaths[0])
})

test('小于上限但实测Kmin与可信原码率冲突时使用独立质量错误', async () => {
  const harness = createVideoHarness({
    sourceInfo: { width: 3840, height: 2160, duration: 600, bitrate: 2000 },
    outputSizes: [60 * 1024 * 1024],
    outputInfos: [
      { width: 3840, height: 2160, duration: 600, fps: 30, type: 'mp4' }
    ]
  })
  await assert.rejects(runCompression(harness), error => {
    assert.equal(error.code, 'MEDIA_COMPRESSION_VIDEO_QUALITY_LIMIT')
    assert.equal(error.message, '视频压缩无法兼顾清晰度和大小，请选择较短或较小的文件')
    assert.doesNotMatch(error.message, /仍超过 100MB/)
    return true
  })
})

test('onCompress只在首次编码前触发一次', async () => {
  let count = 0
  const harness = createVideoHarness({
    outputSizes: [LIMIT + 1024, Math.floor(LIMIT * 0.98)]
  })
  await runCompression(harness, videoFile(), { onCompress() { count += 1 } })
  assert.equal(count, 1)
})

test('成品保留有效自定义封面指纹并清空主文件指纹', async () => {
  const harness = createVideoHarness()
  const result = await runCompression(harness, videoFile({
    customCoverPath: 'wxfile://custom-cover',
    customCoverSha256: 'cover-sha',
    coverSha256: 'default-cover-sha'
  }))
  assert.equal(result.customCoverPath, 'wxfile://custom-cover')
  assert.equal(result.customCoverSha256, 'cover-sha')
  assert.equal(result.sha256, '')
})

test('缺少compressVideo能力时在启动编码前给出升级提示', async () => {
  const harness = createVideoHarness()
  delete harness.wxApi.compressVideo
  await assert.rejects(runCompression(harness), {
    message: '当前微信版本不支持视频压缩，请升级微信'
  })
  assert.equal(harness.calls.length, 0)
})

test('读取阶段耗尽单视频总预算后不启动编码', async () => {
  const originalNow = Date.now
  let now = 1000
  Date.now = () => now
  const harness = createVideoHarness()
  const originalGetVideoInfo = harness.wxApi.getVideoInfo
  harness.wxApi.getVideoInfo = args => {
    originalGetVideoInfo(args)
    now += 20 * 60 * 1000 + 1
  }
  let compressNotifications = 0
  try {
    await assert.rejects(runCompression(harness, videoFile(), {
      onCompress() { compressNotifications += 1 }
    }), {
      code: 'MEDIA_COMPRESSION_TIMEOUT'
    })
    assert.equal(harness.calls.length, 0)
    assert.equal(compressNotifications, 0)
  } finally {
    Date.now = originalNow
  }
})
