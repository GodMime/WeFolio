const { buildAspectRatio, normalizeDimension } = require('./media')
const { loadCanvasImage } = require('./work-thumbnail-crop')
const {
  COMPRESSION_BUSY,
  COMPRESSION_CANCELLED,
  COMPRESSION_TIMEOUT,
  IMAGE_ATTEMPT_TIMEOUT_MS,
  IMAGE_TOTAL_TIMEOUT_MS,
  METADATA_TIMEOUT_MS
} = require('./work-compression-runtime')

const JPEG_MAX_QUALITY = 100
const JPEG_MIN_QUALITY = 85
const JPEG_QUALITY_SEARCH_STEPS = 5
const JPEG_RESIZE_STEPS = 4
const JPEG_MAX_ATTEMPTS = 14
const IMAGE_TARGET_RATIO = 0.98
const IMAGE_MIN_LONG_EDGE = 1024
const CANVAS_MAX_SIDE = 4096
const CANVAS_MAX_PIXELS = 16 * 1024 * 1024
const PNG_MAX_ATTEMPTS = 8
const PNG_REFINE_THRESHOLD = 0.95
const ALPHA_SCAN_STRIP_HEIGHT = 64
const OPAQUE_ALPHA = 255
const CANVAS_CONTEXT_TYPE = '2d'
const IMAGE_TYPE = Object.freeze({ JPEG: 'jpeg', PNG: 'png', WEBP: 'webp' })
const CANVAS_FILE_TYPE = Object.freeze({ JPEG: 'jpg', PNG: 'png' })
const WEBP_ALPHA_OPAQUE = 'opaque'
const ALPHA_SCAN_STATUS = Object.freeze({
  OPAQUE: 'opaque', TRANSPARENT: 'transparent', UNAVAILABLE: 'unavailable',
  READ_FAILED: 'read_failed', NOT_REQUIRED: 'not_required'
})
const CANVAS_BUSY = 'MEDIA_COMPRESSION_CANVAS_BUSY'
const CANVAS_FAILED = 'MEDIA_COMPRESSION_CANVAS_FAILED'
const CANVAS_DEBUG_EVENT = 'image_canvas_result'
const CANVAS_ERROR_MESSAGE = '图片处理失败，请选择较小文件后重试'

const FILE_SIZE_ERROR_MESSAGE = '无法读取作品文件大小，请重新选择'
const IMAGE_INFO_ERROR_MESSAGE = '无法读取图片信息，请重新选择'
const COMPRESSED_IMAGE_INFO_ERROR_MESSAGE = '无法读取压缩后的图片信息，请重新选择'
const IMAGE_TOO_LARGE_MESSAGE = '图片压缩后仍超过 10MB，请选择较小文件'
const IMAGE_TIMEOUT_MESSAGE = '图片压缩超时，请重试'
const IMAGE_UNSUPPORTED_MESSAGE = '当前图片格式暂不支持压缩'
const NATIVE_IMAGE_UNSUPPORTED_MESSAGE = '当前微信版本不支持图片压缩，请升级微信'

function createImageError(code, message, cause) {
  const error = Object.assign(new Error(message), { code })
  if (cause) {
    error.cause = cause
  }
  return error
}

function normalizeImageType(value) {
  const type = String(value || '').trim().toLowerCase()
  return type === 'jpg' ? 'jpeg' : type
}

function readRemainingTimeout(startedAt, totalTimeoutMs, stageTimeoutMs) {
  const remaining = totalTimeoutMs - (Date.now() - startedAt)
  if (!(remaining > 0)) {
    throw createImageError(COMPRESSION_TIMEOUT, IMAGE_TIMEOUT_MESSAGE)
  }
  return Math.min(stageTimeoutMs, remaining)
}

function readFileSize(path, options) {
  const { session, wxApi, startedAt, totalTimeoutMs = IMAGE_TOTAL_TIMEOUT_MS } = options
  session.assertActive()
  readRemainingTimeout(startedAt, totalTimeoutMs, METADATA_TIMEOUT_MS)
  try {
    const stat = wxApi.getFileSystemManager().statSync(path)
    const size = Number(stat && stat.size)
    if (!Number.isFinite(size) || size <= 0) {
      throw new Error('invalid file size')
    }
    return size
  } catch (error) {
    if (error && error.code === COMPRESSION_CANCELLED) {
      throw error
    }
    throw createImageError('MEDIA_COMPRESSION_FILE_SIZE_INVALID', FILE_SIZE_ERROR_MESSAGE, error)
  }
}

async function readImageInfo(src, options) {
  const {
    session,
    startedAt,
    totalTimeoutMs = IMAGE_TOTAL_TIMEOUT_MS,
    invalidMessage = IMAGE_INFO_ERROR_MESSAGE
  } = options
  const timeoutMs = readRemainingTimeout(startedAt, totalTimeoutMs, METADATA_TIMEOUT_MS)
  let response
  try {
    response = await session.call('getImageInfo', { src }, { timeoutMs })
  } catch (error) {
    if (error && (error.code === COMPRESSION_CANCELLED || error.code === COMPRESSION_TIMEOUT)) {
      throw error
    }
    throw createImageError('MEDIA_COMPRESSION_IMAGE_INFO_INVALID', invalidMessage, error)
  }
  const width = normalizeDimension(response && response.width)
  const height = normalizeDimension(response && response.height)
  const type = normalizeImageType(response && response.type)
  if (width <= 0 || height <= 0 || !type) {
    throw createImageError('MEDIA_COMPRESSION_IMAGE_INFO_INVALID', invalidMessage)
  }
  return {
    width,
    height,
    type,
    orientation: response.orientation || ''
  }
}

async function encodeNativeJpeg({ quality, width, height }, options) {
  const {
    file,
    session,
    wxApi,
    startedAt,
    totalTimeoutMs = IMAGE_TOTAL_TIMEOUT_MS
  } = options
  if (!wxApi || typeof wxApi.compressImage !== 'function') {
    throw createImageError('MEDIA_COMPRESSION_IMAGE_API_UNAVAILABLE', NATIVE_IMAGE_UNSUPPORTED_MESSAGE)
  }
  const timeoutMs = readRemainingTimeout(startedAt, totalTimeoutMs, IMAGE_ATTEMPT_TIMEOUT_MS)
  const args = {
    src: file.tempFilePath,
    quality
  }
  if (width && height && (width !== options.sourceWidth || height !== options.sourceHeight)) {
    args.compressedWidth = width
  }
  return session.call('compressImage', args, {
    timeoutMs,
    waitForEncoding: true,
    encodingWaitTimeoutMs: readRemainingTimeout(startedAt, totalTimeoutMs, totalTimeoutMs),
    onEncodingWait: options.onEncodingWait,
    nominalTimeoutMs: IMAGE_ATTEMPT_TIMEOUT_MS,
    encoding: true,
    createsFile: true
  })
}

function nextImageWidth(width, size, maxBytes, minimumWidth) {
  const ratio = Math.min(0.95, Math.sqrt(IMAGE_TARGET_RATIO * maxBytes / size))
  return Math.max(minimumWidth, Math.floor(width * ratio))
}

function isBetterCandidate(candidate, current) {
  if (!current) {
    return true
  }
  const candidateArea = candidate.width * candidate.height
  const currentArea = current.width * current.height
  return candidateArea > currentArea
    || (candidateArea === currentArea && candidate.quality > current.quality)
}

function discardCandidate(candidate, session) {
  if (candidate && candidate.tempFilePath) {
    session.discard(candidate.tempFilePath)
  }
}

async function searchJpeg(file, info, options) {
  const {
    encodeAttempt,
    maxBytes,
    session,
    wxApi,
    startedAt,
    totalTimeoutMs = IMAGE_TOTAL_TIMEOUT_MS
  } = options
  const initialSize = options.initialSize || { width: info.width, height: info.height }
  const initialWidth = normalizeDimension(initialSize.width)
  const initialHeight = normalizeDimension(initialSize.height)
  const sourceLongEdge = Math.max(info.width, info.height)
  const minimumLongEdge = Math.min(sourceLongEdge, IMAGE_MIN_LONG_EDGE)
  const minimumWidth = Math.max(1, Math.ceil(info.width * minimumLongEdge / sourceLongEdge))
  const attempted = new Set()
  let attempts = 0
  let resizeCount = 0
  let width = initialWidth
  let height = initialHeight
  let best = null

  const attempt = async quality => {
    const key = `${width}:${quality}`
    if (attempts >= JPEG_MAX_ATTEMPTS || attempted.has(key)) {
      return null
    }
    attempted.add(key)
    attempts++
    let encoded
    try {
      encoded = await encodeAttempt({ quality, width, height })
      session.assertActive()
      const tempFilePath = encoded && encoded.tempFilePath
      if (!tempFilePath) {
        throw createImageError('MEDIA_COMPRESSION_IMAGE_ENCODE_FAILED', COMPRESSED_IMAGE_INFO_ERROR_MESSAGE)
      }
      const size = readFileSize(tempFilePath, {
        session,
        wxApi,
        startedAt,
        totalTimeoutMs
      })
      const outputInfo = await readImageInfo(tempFilePath, {
        session,
        startedAt,
        totalTimeoutMs,
        invalidMessage: COMPRESSED_IMAGE_INFO_ERROR_MESSAGE
      })
      if (outputInfo.type !== 'jpeg') {
        discardCandidate({ tempFilePath }, session)
        throw createImageError('MEDIA_COMPRESSION_IMAGE_FORMAT_INVALID', COMPRESSED_IMAGE_INFO_ERROR_MESSAGE)
      }
      const candidate = {
        tempFilePath,
        size,
        width: outputInfo.width,
        height: outputInfo.height,
        type: outputInfo.type,
        orientation: outputInfo.orientation,
        quality
      }
      if (size <= maxBytes) {
        if (isBetterCandidate(candidate, best)) {
          discardCandidate(best, session)
          best = candidate
        } else {
          discardCandidate(candidate, session)
        }
      } else {
        discardCandidate(candidate, session)
      }
      return candidate
    } catch (error) {
      // 无效候选不能滞留到整批结束；已采用的best由调用方继续持有。
      if (encoded && (!best || encoded.tempFilePath !== best.tempFilePath)) {
        discardCandidate(encoded, session)
      }
      if (error && error.code === COMPRESSION_CANCELLED) {
        throw error
      }
      if (error && error.code === COMPRESSION_TIMEOUT) {
        if (best) {
          return { stop: true, candidate: best }
        }
        throw createImageError(COMPRESSION_TIMEOUT, IMAGE_TIMEOUT_MESSAGE, error)
      }
      if (best) {
        return { stop: true, candidate: best }
      }
      throw error
    }
  }

  const searchQuality = async (low, high) => {
    let steps = 0
    while (low <= high && steps < JPEG_QUALITY_SEARCH_STEPS && attempts < JPEG_MAX_ATTEMPTS) {
      const quality = Math.floor((low + high) / 2)
      const result = await attempt(quality)
      if (result && result.stop) {
        return result
      }
      if (result && result.size <= maxBytes) {
        low = quality + 1
      } else {
        high = quality - 1
      }
      steps++
    }
    return null
  }

  if (width <= 0 || height <= 0) {
    throw createImageError('MEDIA_COMPRESSION_IMAGE_INFO_INVALID', IMAGE_INFO_ERROR_MESSAGE)
  }

  const maximum = await attempt(JPEG_MAX_QUALITY)
  if (maximum && maximum.stop) {
    return maximum.candidate
  }
  if (maximum && maximum.size <= maxBytes) {
    return best
  }

  let minimum = await attempt(JPEG_MIN_QUALITY)
  if (minimum && minimum.stop) {
    return minimum.candidate
  }
  if (minimum && minimum.size <= maxBytes) {
    const stopped = await searchQuality(JPEG_MIN_QUALITY + 1, JPEG_MAX_QUALITY - 1)
    return stopped && stopped.stop ? stopped.candidate : best
  }

  while (resizeCount < JPEG_RESIZE_STEPS && attempts < JPEG_MAX_ATTEMPTS) {
    const resizedWidth = nextImageWidth(width, minimum.size, maxBytes, minimumWidth)
    if (resizedWidth >= width) {
      break
    }
    width = resizedWidth
    height = Math.max(1, Math.round(info.height * width / info.width))
    resizeCount++
    minimum = await attempt(JPEG_MIN_QUALITY)
    if (minimum && minimum.stop) {
      return minimum.candidate
    }
    if (minimum && minimum.size <= maxBytes) {
      const stopped = await searchQuality(JPEG_MIN_QUALITY + 1, JPEG_MAX_QUALITY)
      return stopped && stopped.stop ? stopped.candidate : best
    }
  }

  if (best) {
    return best
  }
  throw createImageError('MEDIA_COMPRESSION_IMAGE_TOO_LARGE', IMAGE_TOO_LARGE_MESSAGE)
}

// Canvas上限仅约束栅格，不对原生JPEG强制缩小。
function initialCanvasSize(width, height) {
  const scale = Math.min(1, CANVAS_MAX_SIDE / Math.max(width, height),
    Math.sqrt(CANVAS_MAX_PIXELS / (width * height)))
  return { width: Math.max(1, Math.floor(width * scale)), height: Math.max(1, Math.floor(height * scale)) }
}

function assertImageActive(session, options) {
  session.assertActive()
  if (options.startedAt !== undefined) {
    readRemainingTimeout(options.startedAt, options.totalTimeoutMs || IMAGE_TOTAL_TIMEOUT_MS, METADATA_TIMEOUT_MS)
  }
}

// VP8L提示不构成不透明证明，逐条带检查完整目标栅格。
async function inspectCanvasAlpha(canvas, session, options = {}) {
  assertImageActive(session, options)
  let context
  try {
    context = canvas.getContext(CANVAS_CONTEXT_TYPE)
  } catch (error) {
    assertImageActive(session, options)
    if (error && (error.code === COMPRESSION_CANCELLED || error.code === COMPRESSION_TIMEOUT)) throw error
    return ALPHA_SCAN_STATUS.READ_FAILED
  }
  if (!context || typeof context.getImageData !== 'function') return ALPHA_SCAN_STATUS.UNAVAILABLE
  for (let y = 0; y < canvas.height; y += ALPHA_SCAN_STRIP_HEIGHT) {
    assertImageActive(session, options)
    const height = Math.min(ALPHA_SCAN_STRIP_HEIGHT, canvas.height - y)
    let data
    try {
      data = context.getImageData(0, y, canvas.width, height).data
    } catch (error) {
      assertImageActive(session, options)
      if (error && (error.code === COMPRESSION_CANCELLED || error.code === COMPRESSION_TIMEOUT)) throw error
      return ALPHA_SCAN_STATUS.READ_FAILED
    }
    assertImageActive(session, options)
    if (!data || data.length !== canvas.width * height * 4) return ALPHA_SCAN_STATUS.READ_FAILED
    for (let offset = 3; offset < data.length; offset += 4) {
      if (data[offset] !== OPAQUE_ALPHA) return ALPHA_SCAN_STATUS.TRANSPARENT
    }
    // 让按钮取消有机会执行；取消/超时不得变成PNG降级。
    await new Promise(resolve => setTimeout(resolve, 0))
    assertImageActive(session, options)
  }
  return ALPHA_SCAN_STATUS.OPAQUE
}

function mapCanvasError(error) {
  if (error && (error.code === COMPRESSION_CANCELLED || error.code === COMPRESSION_TIMEOUT
      || error.code === COMPRESSION_BUSY || error.code === CANVAS_BUSY)) return error
  return createImageError(CANVAS_FAILED, CANVAS_ERROR_MESSAGE, error)
}

function drawFullCanvasRaster(canvas, image, width, height) {
  const context = canvas.getContext(CANVAS_CONTEXT_TYPE)
  context.clearRect(0, 0, width, height)
  context.drawImage(image, 0, 0, width, height)
}

// 导出只使用当前完整栅格；质量精调不会清空或重新采样画布。
async function exportCanvasImage({ canvas, image, width, height, fileType, quality }, options) {
  const { session, wxApi, startedAt, totalTimeoutMs = IMAGE_TOTAL_TIMEOUT_MS } = options
  try {
    session.assertActive()
    const timeoutMs = readRemainingTimeout(startedAt, totalTimeoutMs, IMAGE_ATTEMPT_TIMEOUT_MS)
    if (!wxApi || typeof wxApi.canvasToTempFilePath !== 'function') throw new Error(CANVAS_ERROR_MESSAGE)
    if (!options.reuseRaster) drawFullCanvasRaster(canvas, image, width, height)
    const exportArgs = { canvas, x: 0, y: 0, width, height, destWidth: width, destHeight: height, fileType }
    if (fileType === CANVAS_FILE_TYPE.JPEG) exportArgs.quality = quality / JPEG_MAX_QUALITY
    return await session.call(args => {
      // 只有实际获取编码锁并启动导出才占用原生资源。
      options.onNativeStart()
      wxApi.canvasToTempFilePath(args)
    }, exportArgs, {
      timeoutMs, nominalTimeoutMs: IMAGE_ATTEMPT_TIMEOUT_MS,
      waitForEncoding: true,
      encodingWaitTimeoutMs: readRemainingTimeout(startedAt, totalTimeoutMs, totalTimeoutMs),
      onEncodingWait: options.onEncodingWait,
      encoding: true, createsFile: true, onNativeSettled: options.onNativeSettled
    })
  } catch (error) {
    throw mapCanvasError(error)
  }
}

// PNG只搜索尺寸，保留已验收的最大实际像素面积，最多八次。
async function searchPng(file, info, options) {
  const { session, maxBytes, encodeAttempt } = options
  const initialSize = options.initialSize || initialCanvasSize(info.width, info.height)
  const sourceLongEdge = Math.max(info.width, info.height)
  const minimumLongEdge = Math.min(sourceLongEdge, IMAGE_MIN_LONG_EDGE)
  const attempted = new Set()
  let width = initialSize.width
  let height = initialSize.height
  let longEdge = Math.max(width, height)
  let tooLargeLongEdge = null
  let qualifiedLongEdge = null
  let best = null
  for (let attempt = 0; attempt < PNG_MAX_ATTEMPTS; attempt++) {
    const key = `${width}:${height}`
    if (attempted.has(key)) break
    attempted.add(key)
    let encoded
    try {
      encoded = await encodeAttempt({ width, height })
      session.assertActive()
      const tempFilePath = encoded && encoded.tempFilePath
      if (!tempFilePath) throw createImageError('MEDIA_COMPRESSION_IMAGE_ENCODE_FAILED', COMPRESSED_IMAGE_INFO_ERROR_MESSAGE)
      const size = readFileSize(tempFilePath, options)
      const outputInfo = await readImageInfo(tempFilePath, { ...options, invalidMessage: COMPRESSED_IMAGE_INFO_ERROR_MESSAGE })
      if (outputInfo.type !== IMAGE_TYPE.PNG) {
        throw createImageError('MEDIA_COMPRESSION_IMAGE_FORMAT_INVALID', COMPRESSED_IMAGE_INFO_ERROR_MESSAGE)
      }
      const candidate = { tempFilePath, size, ...outputInfo }
      if (size <= maxBytes) {
        if (isBetterCandidate(candidate, best)) {
          discardCandidate(best, session)
          best = candidate
        } else {
          discardCandidate(candidate, session)
        }
        qualifiedLongEdge = Math.max(qualifiedLongEdge || 0, longEdge)
        if (size >= PNG_REFINE_THRESHOLD * maxBytes || tooLargeLongEdge === null) break
        longEdge = Math.floor((qualifiedLongEdge + tooLargeLongEdge) / 2)
      } else {
        discardCandidate(candidate, session)
        tooLargeLongEdge = Math.min(tooLargeLongEdge || Infinity, longEdge)
        longEdge = qualifiedLongEdge === null
          ? nextImageWidth(longEdge, size, maxBytes, minimumLongEdge)
          : Math.floor((qualifiedLongEdge + tooLargeLongEdge) / 2)
      }
      // 按长边比例缩放，避免极窄竖图宽度取整不变后反而扩大高度。
      width = Math.max(1, Math.floor(info.width * longEdge / sourceLongEdge))
      height = Math.max(1, Math.floor(info.height * longEdge / sourceLongEdge))
    } catch (error) {
      if (encoded && (!best || encoded.tempFilePath !== best.tempFilePath)) discardCandidate(encoded, session)
      if (error && error.code === COMPRESSION_CANCELLED) throw error
      if (best) return best
      if (error && error.code === COMPRESSION_TIMEOUT) throw createImageError(COMPRESSION_TIMEOUT, IMAGE_TIMEOUT_MESSAGE, error)
      throw error
    }
  }
  if (best) return best
  throw createImageError('MEDIA_COMPRESSION_IMAGE_TOO_LARGE', IMAGE_TOO_LARGE_MESSAGE)
}

// lease覆盖整张图的生命周期，业务结束与原生导出结束分开收尾。
async function compressCanvasImage(file, info, options) {
  const { session, getCanvas, startedAt, totalTimeoutMs } = options
  const ownerToken = {}
  const initialSize = initialCanvasSize(info.width, info.height)
  let lease = null
  let image = null
  let drawnRaster = null
  let closing = false
  let nativePending = false
  let released = false
  const releaseWhenIdle = () => {
    if (!closing || nativePending || released) return
    released = true
    drawnRaster = null
    image = null
    if (lease) lease.release()
  }
  const ensureRaster = async (width, height) => {
    try {
      assertImageActive(session, options)
      const sameRaster = drawnRaster && drawnRaster.canvas === lease.canvas
        && drawnRaster.ownerToken === ownerToken && drawnRaster.sourcePath === file.tempFilePath
        && drawnRaster.width === width && drawnRaster.height === height
      if (sameRaster) return
      const timeoutMs = readRemainingTimeout(startedAt, totalTimeoutMs, METADATA_TIMEOUT_MS)
      // 页面自身包装等待；先保存lease再检查取消，避免丢失迟到租用对象。
      lease = await getCanvas({ width, height, ownerToken, timeoutMs,
        encodingWaitTimeoutMs: readRemainingTimeout(startedAt, totalTimeoutMs, totalTimeoutMs),
        onEncodingWait: options.onEncodingWait })
      session.assertActive()
      drawnRaster = null
      if (!image) {
        image = await session.call(({ success, fail }) => {
          loadCanvasImage(lease.canvas, file.tempFilePath).then(success, fail)
        }, {}, { timeoutMs: readRemainingTimeout(startedAt, totalTimeoutMs, METADATA_TIMEOUT_MS) })
        session.assertActive()
      }
      assertImageActive(session, options)
      drawFullCanvasRaster(lease.canvas, image, width, height)
      drawnRaster = { canvas: lease.canvas, ownerToken, sourcePath: file.tempFilePath, width, height }
    } catch (error) {
      throw mapCanvasError(error)
    }
  }
  try {
    let alphaScanStatus = ALPHA_SCAN_STATUS.NOT_REQUIRED
    let alphaScanElapsedMs = 0
    let jpeg = info.type === IMAGE_TYPE.WEBP && file.webpAlpha === WEBP_ALPHA_OPAQUE
    if (info.type === IMAGE_TYPE.WEBP && file.webpAlphaHint === false) {
      await ensureRaster(initialSize.width, initialSize.height)
      const scanStartedAt = Date.now()
      alphaScanStatus = await inspectCanvasAlpha(lease.canvas, session, options)
      alphaScanElapsedMs = Date.now() - scanStartedAt
      jpeg = alphaScanStatus === ALPHA_SCAN_STATUS.OPAQUE
    }
    const fileType = jpeg ? CANVAS_FILE_TYPE.JPEG : CANVAS_FILE_TYPE.PNG
    const encodeAttempt = async ({ width, height, quality }) => {
      await ensureRaster(width, height)
      return exportCanvasImage({ canvas: lease.canvas, image, width, height, fileType, quality }, {
        ...options, reuseRaster: true,
        onNativeStart() { nativePending = true; lease.nativePending = true },
        onNativeSettled() { nativePending = false; lease.nativePending = false; releaseWhenIdle() }
      })
    }
    const search = jpeg ? searchJpeg : searchPng
    const candidate = await search(file, info, { ...options, initialSize, encodeAttempt })
    session.assertActive()
    // 仅本地显式传入回调时输出枚举/尺寸/耗时，不带路径、像素或凭证。
    if (typeof options.onDebug === 'function') {
      try {
        options.onDebug({ event: CANVAS_DEBUG_EVENT, alphaScanStatus,
          encodingBranch: jpeg ? IMAGE_TYPE.JPEG : IMAGE_TYPE.PNG,
          width: initialSize.width, height: initialSize.height,
          alphaScanElapsedMs, actualType: candidate.type })
      } catch (error) { /* 调试回调不改变业务结果。 */ }
    }
    return candidate
  } catch (error) {
    if (error && error.code === COMPRESSION_TIMEOUT) throw createImageError(COMPRESSION_TIMEOUT, IMAGE_TIMEOUT_MESSAGE, error)
    throw error
  } finally {
    closing = true
    releaseWhenIdle()
  }
}

function replaceImageExtension(fileName, extension) {
  const name = String(fileName || '').trim() || `image.${extension}`
  return /\.[^./\\]+$/.test(name)
    ? name.replace(/\.[^./\\]+$/, `.${extension}`)
    : `${name}.${extension}`
}

function finalizeImage(file, candidate, options) {
  return {
    ...file,
    tempFilePath: candidate.tempFilePath,
    size: candidate.size,
    mimeType: candidate.type === IMAGE_TYPE.PNG ? 'image/png' : 'image/jpeg',
    fileName: replaceImageExtension(file.fileName, candidate.type === IMAGE_TYPE.PNG ? CANVAS_FILE_TYPE.PNG : CANVAS_FILE_TYPE.JPEG),
    width: candidate.width,
    height: candidate.height,
    aspectRatio: buildAspectRatio(candidate.width, candidate.height),
    sha256: '',
    coverSha256: '',
    customCoverSha256: file.customCoverPath ? (file.customCoverSha256 || '') : '',
    compressed: true,
    originalSize: options.originalSize
  }
}

async function compressImageToLimit(file, options = {}) {
  if (!file || file.mediaType !== 'IMAGE') {
    return file
  }
  const { session, wxApi, maxBytes, onCompress } = options
  const startedAt = Date.now()
  const totalTimeoutMs = options.totalTimeoutMs || IMAGE_TOTAL_TIMEOUT_MS
  const originalSize = readFileSize(file.tempFilePath, {
    session,
    wxApi,
    startedAt,
    totalTimeoutMs
  })
  const measuredFile = originalSize === file.size
    ? file
    : { ...file, size: originalSize, sha256: '' }
  if (originalSize <= maxBytes) {
    return measuredFile
  }

  const info = await readImageInfo(file.tempFilePath, {
    session,
    startedAt,
    totalTimeoutMs
  })
  const canvasImage = info.type === IMAGE_TYPE.PNG || (info.type === IMAGE_TYPE.WEBP
    && file.staticImageVerified === true && file.isAnimation !== true)
  if (info.type !== IMAGE_TYPE.JPEG && !canvasImage) {
    throw createImageError('MEDIA_COMPRESSION_IMAGE_UNSUPPORTED', IMAGE_UNSUPPORTED_MESSAGE)
  }
  if (canvasImage && (!wxApi || typeof wxApi.canvasToTempFilePath !== 'function' || typeof options.getCanvas !== 'function')) {
    throw createImageError(CANVAS_FAILED, CANVAS_ERROR_MESSAGE)
  }
  if (!canvasImage && (!wxApi || typeof wxApi.compressImage !== 'function')) {
    throw createImageError('MEDIA_COMPRESSION_IMAGE_API_UNAVAILABLE', NATIVE_IMAGE_UNSUPPORTED_MESSAGE)
  }
  if (typeof onCompress === 'function') {
    onCompress()
  }
  if (canvasImage) {
    const candidate = await compressCanvasImage(file, info, { ...options, startedAt, totalTimeoutMs })
    session.assertActive()
    return finalizeImage(measuredFile, candidate, { originalSize })
  }
  const encodeAttempt = params => encodeNativeJpeg(params, {
    file,
    session,
    wxApi,
    startedAt,
    totalTimeoutMs,
    onEncodingWait: options.onEncodingWait,
    sourceWidth: info.width,
    sourceHeight: info.height
  })
  const candidate = await searchJpeg(file, info, {
    encodeAttempt,
    initialSize: { width: info.width, height: info.height },
    maxBytes,
    session,
    wxApi,
    startedAt,
    totalTimeoutMs
  })
  session.assertActive()
  return finalizeImage(measuredFile, candidate, { originalSize })
}

module.exports = {
  initialCanvasSize,
  inspectCanvasAlpha,
  exportCanvasImage,
  searchPng,
  compressImageToLimit,
  encodeNativeJpeg,
  finalizeImage,
  nextImageWidth,
  readImageInfo,
  searchJpeg
}
