const { buildAspectRatio } = require('./media')
const {
  COMPRESSION_CANCELLED,
  COMPRESSION_TIMEOUT,
  METADATA_TIMEOUT_MS,
  VIDEO_ATTEMPT_TIMEOUT_MS,
  VIDEO_TOTAL_TIMEOUT_MS
} = require('./work-compression-runtime')
const VIDEO_TARGET_RATIO = 0.98
const VIDEO_ACCEPT_RATIO = 0.95
const AUDIO_BUDGET_KBPS = 192
const CONTAINER_BUDGET_RATIO = 0.01
const VIDEO_BITS_PER_PIXEL_FRAME = 0.045
const VIDEO_MIN_SHORT_SIDE = 480
const VIDEO_FALLBACK_FPS = 30
const VIDEO_MAX_ATTEMPTS = 3
const VIDEO_QUALITY_LIMIT_ERROR_CODE = 'MEDIA_COMPRESSION_VIDEO_QUALITY_LIMIT'
const VIDEO_QUALITY_LIMIT_MESSAGE = '视频压缩无法兼顾清晰度和大小，请选择较短或较小的文件'
const VIDEO_TOO_LARGE_MESSAGE = '视频压缩后仍超过 100MB，请选择较小文件'
const VIDEO_DURATION_LIMIT_MESSAGE = '视频作品不能超过 10 分钟'
const VIDEO_INFO_FAILED_MESSAGE = '无法读取视频信息，请重新选择'
const VIDEO_OUTPUT_INFO_FAILED_MESSAGE = '无法读取压缩后的视频信息，请重新选择'
const FILE_SIZE_FAILED_MESSAGE = '无法读取作品文件大小，请重新选择'
const VIDEO_UNSUPPORTED_MESSAGE = '当前微信版本不支持视频压缩，请升级微信'
const VIDEO_TIMEOUT_MESSAGE = '视频压缩超时，请重试'
const VIDEO_TYPE_MAP = {
  mp4: { extension: 'mp4', mimeType: 'video/mp4' },
  mov: { extension: 'mov', mimeType: 'video/quicktime' },
  m4v: { extension: 'm4v', mimeType: 'video/x-m4v' }
}

function createCodedError(code, message) {
  return Object.assign(new Error(message), { code })
}

function createQualityError() {
  return createCodedError(VIDEO_QUALITY_LIMIT_ERROR_CODE, VIDEO_QUALITY_LIMIT_MESSAGE)
}

function createMetadataError(message = VIDEO_OUTPUT_INFO_FAILED_MESSAGE) {
  const error = new Error(message)
  error.videoMetadataInvalid = true
  return error
}

function isPositiveFinite(value) {
  return Number.isFinite(Number(value)) && Number(value) > 0
}

function normalizeType(value) {
  return String(value || '').trim().toLowerCase()
}

function getRuntimeWx(wxApi) {
  if (wxApi) {
    return wxApi
  }
  if (typeof wx !== 'undefined') {
    return wx
  }
  throw new Error('wx 运行环境不可用')
}

function remainingTimeout(startedAt, phaseLimitMs) {
  const remaining = VIDEO_TOTAL_TIMEOUT_MS - (Date.now() - startedAt)
  if (remaining <= 0) {
    throw createCodedError(COMPRESSION_TIMEOUT, VIDEO_TIMEOUT_MESSAGE)
  }
  return Math.min(phaseLimitMs, remaining)
}

function readStatSize(path, wxApi) {
  try {
    const stat = wxApi.getFileSystemManager().statSync(path)
    const size = Number(stat && stat.size)
    if (!Number.isFinite(size) || size <= 0) {
      throw new Error(FILE_SIZE_FAILED_MESSAGE)
    }
    return size
  } catch (error) {
    if (error && error.message === FILE_SIZE_FAILED_MESSAGE) {
      throw error
    }
    throw new Error(FILE_SIZE_FAILED_MESSAGE)
  }
}

function readOutputSize(path, wxApi) {
  let stat
  try {
    stat = wxApi.getFileSystemManager().statSync(path)
  } catch (error) {
    throw new Error(FILE_SIZE_FAILED_MESSAGE)
  }
  const size = Number(stat && stat.size)
  if (!Number.isFinite(size) || size <= 0) {
    throw createMetadataError()
  }
  return size
}

async function readVideoInfo(src, options = {}) {
  const { session } = options
  if (!session || typeof session.call !== 'function') {
    throw new Error('视频压缩会话不可用')
  }
  return session.call('getVideoInfo', { src }, {
    timeoutMs: options.timeoutMs || METADATA_TIMEOUT_MS
  })
}

async function readSourceVideoInfo(file, options = {}) {
  const info = await readVideoInfo(file.tempFilePath, options) || {}
  options.session.assertActive()
  // 原生读取成功也可能缺字段；只允许用同一原片的选择器信息补齐。
  // 宽高成对取值，避免不同接口的旋转方向不同而拼出错误比例。
  const dimensions = isPositiveFinite(info.width) && isPositiveFinite(info.height) ? info : file
  const pickerDuration = isPositiveFinite(file.durationSeconds)
    ? Number(file.durationSeconds) : Number(file.durationMs) / 1000
  const sourceInfo = {
    ...info,
    width: Number(dimensions.width),
    height: Number(dimensions.height),
    duration: isPositiveFinite(info.duration) ? Number(info.duration) : pickerDuration
  }
  if (!isPositiveFinite(sourceInfo.width) || !isPositiveFinite(sourceInfo.height)
      || !isPositiveFinite(sourceInfo.duration)) {
    throw createMetadataError(VIDEO_INFO_FAILED_MESSAGE)
  }
  return sourceInfo
}

function buildVideoBudget(info, maxBytes) {
  const targetBytes = Math.floor(maxBytes * VIDEO_TARGET_RATIO)
  const containerBytes = Math.ceil(targetBytes * CONTAINER_BUDGET_RATIO)
  const audioBytes = Math.ceil(AUDIO_BUDGET_KBPS * 1000 * info.duration / 8)
  const bitrate = Math.floor((targetBytes - containerBytes - audioBytes) * 8
    / info.duration / 1000)
  return { targetBytes, containerBytes, audioBytes, bitrate }
}

function chooseVideoGeometry(info, bitrate) {
  if (!isPositiveFinite(info.width) || !isPositiveFinite(info.height)
      || !isPositiveFinite(bitrate)) {
    throw createQualityError()
  }
  const sourceFpsKnown = Number.isFinite(Number(info.fps)) && Number(info.fps) > 0
  let targetFps = sourceFpsKnown ? Number(info.fps) : VIDEO_FALLBACK_FPS
  const scaleForFps = fps => Math.min(1, Math.sqrt(Number(bitrate) * 1000
    / (Number(info.width) * Number(info.height) * fps * VIDEO_BITS_PER_PIXEL_FRAME)))
  let r = scaleForFps(targetFps)
  const minimumShortSide = Math.min(Number(info.width), Number(info.height), VIDEO_MIN_SHORT_SIDE)
  if (Math.min(Number(info.width), Number(info.height)) * r < minimumShortSide
      && sourceFpsKnown && Number(info.fps) > VIDEO_FALLBACK_FPS) {
    targetFps = VIDEO_FALLBACK_FPS
    r = scaleForFps(targetFps)
  }
  if (!Number.isFinite(r) || r <= 0 || r > 1
      || Math.min(Number(info.width), Number(info.height)) * r < minimumShortSide) {
    throw createQualityError()
  }
  const predictedWidth = Math.max(1, Math.floor(Number(info.width) * r))
  const predictedHeight = Math.max(1, Math.floor(Number(info.height) * r))
  const minBitrate = Math.ceil(predictedWidth * predictedHeight * targetFps
    * VIDEO_BITS_PER_PIXEL_FRAME / 1000)
  return {
    r,
    targetFps,
    sourceFpsKnown,
    predictedWidth,
    predictedHeight,
    minBitrate
  }
}

function nextVideoBitrate(current, size, budget) {
  const overhead = Math.min(budget.audioBytes + budget.containerBytes, Math.floor(size * 0.25))
  const payloadSize = size - overhead
  if (payloadSize <= 0) {
    return Math.floor(current * 0.5)
  }
  const estimate = Math.floor(current * (budget.targetBytes - overhead) / payloadSize)
  return Math.max(Math.floor(current * 0.5), Math.min(Math.floor(current * 1.5), estimate))
}

function validateVideoCandidate(file, candidate, sourceInfo, options = {}) {
  const info = candidate.info || {}
  const width = Number(info.width)
  const height = Number(info.height)
  const duration = Number(info.duration)
  const normalizedType = normalizeType(info.type)
  // 微信端可能返回格式简称，开发者工具会返回完整 MIME 类型。
  const type = Object.values(VIDEO_TYPE_MAP).find(({ extension, mimeType }) =>
    normalizedType === extension || normalizedType === mimeType)
  if (!isPositiveFinite(candidate.size) || !isPositiveFinite(width)
      || !isPositiveFinite(height) || !isPositiveFinite(duration) || !type) {
    throw createMetadataError()
  }
  const exactDurationMs = duration * 1000
  const durationMs = Math.round(exactDurationMs)
  const exactSourceDurationMs = Number(sourceInfo.duration) * 1000
  const targetFps = Number(options.targetFps)
  const durationToleranceMs = Math.max(100, 2000 / targetFps)
  if (exactDurationMs > Number(options.maxDurationMs)
      || Math.abs(exactDurationMs - exactSourceDurationMs) > durationToleranceMs) {
    throw createMetadataError()
  }
  const minimumShortSide = Math.min(
    Number(sourceInfo.width),
    Number(sourceInfo.height),
    VIDEO_MIN_SHORT_SIDE
  )
  if (Math.min(width, height) < minimumShortSide
      || width * height > Number(sourceInfo.width) * Number(sourceInfo.height)) {
    throw createQualityError()
  }
  const outputFps = Number.isFinite(Number(info.fps)) && Number(info.fps) > 0
    ? Number(info.fps)
    : targetFps
  const minBitrate = Math.ceil(width * height * outputFps
    * VIDEO_BITS_PER_PIXEL_FRAME / 1000)
  const extensionBase = String(file.fileName || '').replace(/\.[^./]+$/, '')
    || String(file.title || '').trim()
    || 'video'
  const customCoverPath = String(file.customCoverPath || '').trim()
  return {
    minBitrate,
    pixelArea: width * height,
    width,
    height,
    durationMs,
    fps: outputFps,
    result: {
      ...file,
      tempFilePath: candidate.path,
      fileName: `${extensionBase}.${type.extension}`,
      fileType: 'video',
      mediaType: 'VIDEO',
      mimeType: type.mimeType,
      type: type.mimeType,
      size: Number(candidate.size),
      width,
      height,
      durationMs,
      durationSeconds: duration,
      fps: outputFps,
      bitrate: Number(candidate.requestedBitrate),
      aspectRatio: buildAspectRatio(width, height),
      sha256: '',
      compressed: true,
      originalSize: Number(options.originalSize),
      coverSha256: '',
      customCoverPath,
      customCoverSha256: customCoverPath ? String(file.customCoverSha256 || '').trim() : ''
    }
  }
}

function isBetterCandidate(candidate, best) {
  return !best
    || candidate.pixelArea > best.pixelArea
    || (candidate.pixelArea === best.pixelArea
      && candidate.requestedBitrate > best.requestedBitrate)
}

function discardCandidate(session, candidate) {
  if (candidate && candidate.path && typeof session.discard === 'function') {
    session.discard(candidate.path)
  }
}

function retainCandidate(session, candidate, best) {
  if (!isBetterCandidate(candidate, best)) {
    discardCandidate(session, candidate)
    return best
  }
  discardCandidate(session, best)
  return candidate
}

async function compressVideoToLimit(file, options = {}) {
  if (!file || file.mediaType !== 'VIDEO') {
    return file
  }
  const wxApi = getRuntimeWx(options.wxApi)
  const session = options.session
  const maxBytes = Number(options.maxBytes)
  const maxDurationMs = Number(options.maxDurationMs)
  const startedAt = Date.now()
  session.assertActive()
  const sourceSize = readStatSize(file.tempFilePath, wxApi)
  if (sourceSize <= maxBytes) {
    return {
      ...file,
      size: sourceSize,
      sha256: sourceSize === Number(file.size) ? file.sha256 : ''
    }
  }
  const sourceInfo = options.sourceInfo || await readSourceVideoInfo(file, {
    session,
    timeoutMs: remainingTimeout(startedAt, METADATA_TIMEOUT_MS)
  })
  if (!isPositiveFinite(sourceInfo.width) || !isPositiveFinite(sourceInfo.height)
      || !isPositiveFinite(sourceInfo.duration)) {
    throw createMetadataError(VIDEO_INFO_FAILED_MESSAGE)
  }
  const exactSourceDurationMs = Number(sourceInfo.duration) * 1000
  if (exactSourceDurationMs > maxDurationMs) {
    throw new Error(VIDEO_DURATION_LIMIT_MESSAGE)
  }
  if (typeof wxApi.compressVideo !== 'function') {
    throw new Error(VIDEO_UNSUPPORTED_MESSAGE)
  }
  const budget = buildVideoBudget(sourceInfo, maxBytes)
  if (!isPositiveFinite(budget.bitrate)) {
    throw createQualityError()
  }
  const trustedSourceBitrate = isPositiveFinite(sourceInfo.bitrate)
    ? Math.floor(Number(sourceInfo.bitrate))
    : Infinity
  let currentKbps = Math.min(budget.bitrate, trustedSourceBitrate)
  const geometry = chooseVideoGeometry(sourceInfo, currentKbps)
  let calibratedMinBitrate = geometry.minBitrate
  let best = null
  let previousAttempt = null
  let compressNotified = false

  for (let attempt = 0; attempt < VIDEO_MAX_ATTEMPTS; attempt += 1) {
    session.assertActive()
    if (calibratedMinBitrate > trustedSourceBitrate || currentKbps < calibratedMinBitrate) {
      if (best) {
        return best.result
      }
      throw createQualityError()
    }
    let attemptTimeoutMs
    try {
      attemptTimeoutMs = remainingTimeout(startedAt, VIDEO_ATTEMPT_TIMEOUT_MS)
    } catch (error) {
      if (best && error && error.code === COMPRESSION_TIMEOUT) {
        return best.result
      }
      throw error
    }
    if (!compressNotified && typeof options.onCompress === 'function') {
      options.onCompress()
      compressNotified = true
    }
    let output
    try {
      output = await session.call('compressVideo', {
        src: file.tempFilePath,
        bitrate: currentKbps,
        fps: geometry.targetFps,
        resolution: geometry.r
      }, {
        timeoutMs: attemptTimeoutMs,
        nominalTimeoutMs: VIDEO_ATTEMPT_TIMEOUT_MS,
        encoding: true,
        createsFile: true
      })
    } catch (error) {
      if (error && error.code === COMPRESSION_CANCELLED) {
        throw error
      }
      if (best) {
        return best.result
      }
      if (error && error.code === COMPRESSION_TIMEOUT) {
        throw createCodedError(COMPRESSION_TIMEOUT, VIDEO_TIMEOUT_MESSAGE)
      }
      throw error
    }
    const path = String(output && (output.tempFilePath || output.filePath) || '').trim()
    if (!path) {
      const error = createMetadataError()
      if (best) {
        return best.result
      }
      throw error
    }
    const outputCandidate = { path, requestedBitrate: currentKbps }
    let candidate
    try {
      outputCandidate.size = readOutputSize(path, wxApi)
      outputCandidate.info = await readVideoInfo(path, {
        session,
        timeoutMs: remainingTimeout(startedAt, METADATA_TIMEOUT_MS)
      })
      candidate = validateVideoCandidate(file, outputCandidate, sourceInfo, {
        targetFps: geometry.targetFps,
        maxDurationMs,
        originalSize: sourceSize
      })
    } catch (error) {
      discardCandidate(session, outputCandidate)
      if (error && error.code === COMPRESSION_CANCELLED) {
        throw error
      }
      if (best) {
        return best.result
      }
      throw error
    }
    calibratedMinBitrate = candidate.minBitrate
    candidate.path = path
    candidate.size = outputCandidate.size
    candidate.requestedBitrate = currentKbps

    if (currentKbps < calibratedMinBitrate) {
      discardCandidate(session, candidate)
      if (best) {
        return best.result
      }
      if (candidate.size <= maxBytes && attempt + 1 < VIDEO_MAX_ATTEMPTS
          && calibratedMinBitrate <= trustedSourceBitrate) {
        previousAttempt = candidate
        currentKbps = calibratedMinBitrate
        continue
      }
      throw createQualityError()
    }

    if (candidate.size <= maxBytes) {
      best = retainCandidate(session, candidate, best)
      if (candidate.size >= Math.floor(maxBytes * VIDEO_ACCEPT_RATIO)) {
        return best.result
      }
      if (previousAttempt && currentKbps > previousAttempt.requestedBitrate
          && candidate.width === previousAttempt.width
          && candidate.height === previousAttempt.height
          && candidate.size <= previousAttempt.size) {
        return best.result
      }
    } else {
      discardCandidate(session, candidate)
    }

    if (attempt + 1 >= VIDEO_MAX_ATTEMPTS) {
      if (best) {
        return best.result
      }
      throw new Error(VIDEO_TOO_LARGE_MESSAGE)
    }

    let nextKbps = nextVideoBitrate(currentKbps, candidate.size, budget)
    nextKbps = Math.max(calibratedMinBitrate, Math.min(trustedSourceBitrate, nextKbps))
    if (!Number.isFinite(nextKbps) || nextKbps <= 0 || nextKbps === currentKbps) {
      if (best) {
        return best.result
      }
      throw candidate.size > maxBytes ? createQualityError() : new Error(VIDEO_TOO_LARGE_MESSAGE)
    }
    if (candidate.size > maxBytes && nextKbps >= currentKbps) {
      if (best) {
        return best.result
      }
      throw createQualityError()
    }
    if (candidate.size < maxBytes && nextKbps < currentKbps) {
      return best.result
    }
    previousAttempt = candidate
    currentKbps = nextKbps
  }

  if (best) {
    return best.result
  }
  throw new Error(VIDEO_TOO_LARGE_MESSAGE)
}

module.exports = {
  VIDEO_QUALITY_LIMIT_ERROR_CODE,
  VIDEO_QUALITY_LIMIT_MESSAGE,
  buildVideoBudget,
  chooseVideoGeometry,
  compressVideoToLimit,
  nextVideoBitrate,
  readSourceVideoInfo,
  readVideoInfo,
  validateVideoCandidate
}
