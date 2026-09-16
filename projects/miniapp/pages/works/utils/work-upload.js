const {
  THUMB_MAX_BYTES,
  buildAspectRatio,
  normalizeDimension
} = require('./media')
const { calculateFileSha256 } = require('./sha256')
const { DEFAULT_AUDIO_COVER_URL, formatFileSize } = require('./works')
const { COMPRESSION_CANCELLED, COMPRESSION_TIMEOUT, METADATA_TIMEOUT_MS, releasePreparedWorkFiles } = require('./work-compression-runtime')
const { compressImageToLimit } = require('./work-image-compress')
const { compressVideoToLimit } = require('./work-video-compress')

const MAX_BATCH_COUNT = 9
const IMAGE_MAX_BYTES = 10 * 1024 * 1024
// 最大允许字节数采用包含边界的表达，确保单个动图严格小于 32MB。
const ANIMATION_MAX_BYTES = 32 * 1024 * 1024 - 1
const VIDEO_MAX_BYTES = 100 * 1024 * 1024
const VIDEO_MAX_DURATION_SECONDS = 10 * 60
const AUDIO_MAX_BYTES = 50 * 1024 * 1024
const AUDIO_MAX_DURATION_MS = 10 * 60 * 1000
const AUDIO_READ_TIMEOUT_MS = 5000
const AUDIO_READ_FAILED_MESSAGE = '无法读取音频信息，请转换为 MP3 后重试'
const AUDIO_MIME_TYPES = { mp3: 'audio/mpeg', m4a: 'audio/mp4', aac: 'audio/aac', wav: 'audio/wav' }
const AUDIO_PICKER_FILE_TYPE = 'file'
const AUDIO_PICKER_UNSUPPORTED_MESSAGE = '当前微信版本不支持选择文件，请升级微信'
const AUDIO_PICKER_FAILED_MESSAGE = '选择音频失败，请重试'
const AUDIO_PICKER_UNAVAILABLE_MESSAGE = '暂时无法选择音频，请联系管理员'
const AUDIO_PICKER_PRIVACY_MESSAGE = '请同意隐私保护指引后选择音频'
const PRIVACY_SCOPE_UNDECLARED_ERRNO = 112
const PRIVACY_AUTH_DENIED_ERRNOS = [103, 104]
const IMAGE_UPLOAD_CONCURRENCY = 2
const ANIMATION_UPLOAD_CONCURRENCY = 2
const VIDEO_UPLOAD_CONCURRENCY = 1
const COS_UPLOAD_TIMEOUT = 10 * 60 * 1000
const TITLE_MAX_LENGTH = 30
const UPLOAD_COMPLETE_FAILURE_FALLBACK = '部分作品确认失败'
const CHOOSE_MEDIA_TYPE_MIX = 'mix'
const CHOOSE_MEDIA_TYPE_IMAGE = 'image'
const CHOOSE_SOURCE_TYPE_ALBUM = 'album'
const COVER_CLIENT_ID_SUFFIX = '-cover'
const DEFAULT_COVER_MIME_TYPE = 'image/jpeg'
const THUMB_FILE_SUFFIX = '-thumb'
const THUMB_FILE_EXTENSION = 'jpg'
const THUMB_COMPRESS_ATTEMPTS = [
  { quality: 85, compressedSize: 960 },
  { quality: 75, compressedSize: 720 },
  { quality: 65, compressedSize: 540 },
  { quality: 55, compressedSize: 360 },
  { quality: 45, compressedSize: 240 }
]
const THUMB_TOO_LARGE_MESSAGE = '缩略图或封面图不能超过 100KB'
const IMAGE_TOO_LARGE_MESSAGE = '图片作品不能超过 10MB'
const ANIMATION_TOO_LARGE_MESSAGE = '动图作品必须小于 32MB'
// 开发者工具整文件读取超过 10MB 时可能解码失败，识别类型只读取必要头部。
const MEDIA_SIGNATURE_READ_BYTES = 21
const WEBP_RIFF_HEADER_BYTES = 12
const WEBP_CHUNK_HEADER_BYTES = 8
const WEBP_ANIMATION_CHUNK_TYPES = ['ANIM', 'ANMF']
const WEBP_MAX_SCAN_CHUNKS = 1024
const WEBP_MAX_SCAN_HEADER_BYTES = 64 * 1024
const WEBP_ANIMATION_FLAG = 0x02
const WEBP_ALPHA_FLAG = 0x10
const WEBP_VP8L_SIGNATURE = 0x2f
const WEBP_EXTENDED_HEADER_SIZE = 10
const WEBP_LOSSLESS_HEADER_SIZE = 5
const COMPRESSION_SUMMARY_PREFIX = '已压缩：'
const IMAGE_COMPRESSION_UNSUPPORTED_MESSAGE = '当前微信版本不支持图片压缩，请升级微信'
const VIDEO_COMPRESSION_UNSUPPORTED_MESSAGE = '当前微信版本不支持视频压缩，请升级微信'
const IMAGE_PROCESSING_FAILED_MESSAGE = '图片处理失败，请选择较小文件后重试'
const MEDIA_EMPTY_MESSAGE = '作品文件不能为空，请重新选择'
const MEDIA_TYPE_UNSUPPORTED_MESSAGE = '作品文件格式不支持'
const VIDEO_INFO_FAILED_MESSAGE = '无法读取视频信息，请重新选择'
const VIDEO_DURATION_LIMIT_MESSAGE = '视频作品不能超过 10 分钟'
const MEDIA_STAT_FAILED_MESSAGE = '无法读取作品文件大小，请重新选择'
const WEBP_UNKNOWN = Object.freeze({ staticImageVerified: false, animated: false, webpAlpha: 'unknown', webpAlphaHint: null })

function getRuntimeWx(wxApi) {
  if (wxApi) {
    return wxApi
  }
  if (typeof wx !== 'undefined') {
    return wx
  }
  throw new Error('wx 运行环境不可用')
}

function trimText(value) {
  return String(value || '').trim()
}

function normalizeSize(value) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) && numberValue > 0 ? numberValue : 0
}

function resolveAspectRatio(file = {}) {
  return trimText(file.aspectRatio) || buildAspectRatio(file.width, file.height)
}

function createChooseMediaOptions(remainingCount = MAX_BATCH_COUNT) {
  const count = Math.max(0, Math.min(MAX_BATCH_COUNT, Number(remainingCount) || MAX_BATCH_COUNT))
  return {
    count,
    mediaType: [CHOOSE_MEDIA_TYPE_MIX],
    sourceType: [CHOOSE_SOURCE_TYPE_ALBUM],
    sizeType: ['original']
  }
}

function createChooseAudioError(rawError = {}) {
  const errMsg = trimText(rawError && (rawError.errMsg || rawError.message))
  const errno = rawError && rawError.errno
  let message = AUDIO_PICKER_FAILED_MESSAGE
  if (/cancel/i.test(errMsg)) {
    message = errMsg
  } else if (Number(errno) === PRIVACY_SCOPE_UNDECLARED_ERRNO
      || /api scope is not declared in the privacy agreement|appid privacy api banned/i.test(errMsg)) {
    // 聊天文件的隐私声明独立于照片和视频，须在微信管理后台补充，不能由客户端绕过。
    message = AUDIO_PICKER_UNAVAILABLE_MESSAGE
  } else if (PRIVACY_AUTH_DENIED_ERRNOS.includes(Number(errno))) {
    message = AUDIO_PICKER_PRIVACY_MESSAGE
  }
  const error = new Error(message)
  error.errMsg = errMsg
  error.errno = errno
  return error
}

// 保留微信 fail 对象中的诊断字段，避免页面只读取 Error.message 而丢失原始原因。
function chooseAudioFiles(remainingCount = MAX_BATCH_COUNT, options = {}) {
  return new Promise((resolve, reject) => {
    const runtimeWx = getRuntimeWx(options.wxApi)
    if (typeof runtimeWx.chooseMessageFile !== 'function') {
      reject(new Error(AUDIO_PICKER_UNSUPPORTED_MESSAGE))
      return
    }
    try {
      runtimeWx.chooseMessageFile({
        count: createChooseMediaOptions(remainingCount).count,
        type: AUDIO_PICKER_FILE_TYPE,
        extension: Object.keys(AUDIO_MIME_TYPES),
        success: resolve,
        fail(error) { reject(createChooseAudioError(error)) }
      })
    } catch (error) {
      reject(createChooseAudioError(error))
    }
  })
}

function createChooseCoverImageOptions() {
  return {
    count: 1,
    mediaType: [CHOOSE_MEDIA_TYPE_IMAGE],
    sourceType: [CHOOSE_SOURCE_TYPE_ALBUM]
  }
}

function fileNameFromPath(filePath) {
  const value = trimText(filePath)
  const segments = value.split('/')
  return segments[segments.length - 1] || '未命名作品'
}

function fileStemFromFileName(fileName) {
  const value = trimText(fileName)
  const index = value.lastIndexOf('.')
  return index > 0 ? value.slice(0, index) : value
}

function titleFromFileName(fileName) {
  const stem = fileStemFromFileName(fileName)
  return stem.length > TITLE_MAX_LENGTH ? stem.slice(0, TITLE_MAX_LENGTH) : stem
}

function buildThumbFileName(fileName) {
  const stem = fileStemFromFileName(fileName) || 'work'
  return `${stem}${THUMB_FILE_SUFFIX}.${THUMB_FILE_EXTENSION}`
}

function getLocalFileSize(filePath, wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  const fileSystemManager = runtimeWx.getFileSystemManager()
  const stats = fileSystemManager.statSync(filePath)
  return stats && typeof stats.size === 'number' ? stats.size : 0
}

function compressImageFile(filePath, attempt, options = {}) {
  const runtimeWx = getRuntimeWx(options.wxApi)
  if (!runtimeWx.compressImage) {
    return Promise.reject(new Error('当前微信版本不支持图片压缩'))
  }
  return new Promise((resolve, reject) => {
    runtimeWx.compressImage({
      src: filePath,
      quality: attempt.quality,
      compressedWidth: attempt.compressedSize,
      success(response) {
        if (response && response.tempFilePath) {
          resolve(response.tempFilePath)
          return
        }
        reject(new Error('图片压缩失败'))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : '图片压缩失败'))
      }
    })
  })
}

async function prepareThumbFile(filePath, options = {}) {
  const originalSize = getLocalFileSize(filePath, options.wxApi)
  if (originalSize > 0 && originalSize <= THUMB_MAX_BYTES) {
    return {
      filePath,
      fileSize: originalSize
    }
  }
  for (const attempt of THUMB_COMPRESS_ATTEMPTS) {
    const tempFilePath = await compressImageFile(filePath, attempt, options)
    const fileSize = getLocalFileSize(tempFilePath, options.wxApi)
    if (fileSize > 0 && fileSize <= THUMB_MAX_BYTES) {
      return {
        filePath: tempFilePath,
        fileSize
      }
    }
  }
  throw new Error(THUMB_TOO_LARGE_MESSAGE)
}

async function prepareLocalCoverUploadFile(filePath, options = {}) {
  return prepareThumbFile(filePath, options)
}

function formatDurationText(durationMs) {
  const totalSeconds = Math.round(normalizeSize(durationMs) / 1000)
  if (totalSeconds <= 0) {
    return ''
  }
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

function buildMediaMetaText(mediaType, durationMs) {
  if (mediaType === 'AUDIO') {
    return `默认标题 · 音频 ${formatDurationText(durationMs)}`
  }
  if (mediaType === 'VIDEO') {
    const durationText = formatDurationText(durationMs)
    return durationText ? `默认标题 · 视频 ${durationText}` : '默认标题 · 视频'
  }
  if (mediaType === 'ANIMATION') {
    return '默认标题 · 动图'
  }
  return '默认标题 · 图片'
}

function mimeTypeFromFile(file) {
  const value = trimText(file.mimeType || file.type)
  if (value) {
    return value
  }
  return file.fileType === 'video' ? 'video/mp4' : 'image/jpeg'
}

function normalizeChosenMediaFile(raw = {}, index = 0) {
  const filePath = trimText(raw.tempFilePath || raw.path)
  const fileName = trimText(raw.name) || fileNameFromPath(filePath)
  const mediaType = raw.fileType === 'audio' ? 'AUDIO' : raw.fileType === 'video' ? 'VIDEO' : 'IMAGE'
  const durationMs = raw.duration ? Math.round(Number(raw.duration) * 1000) : normalizeSize(raw.durationMs)
  const width = normalizeDimension(raw.width)
  const height = normalizeDimension(raw.height)
  return {
    id: raw.id || `local-${Date.now()}-${index}`,
    clientId: raw.clientId || `client-${Date.now()}-${index}`,
    tempFilePath: filePath,
    coverPath: trimText(raw.thumbTempFilePath),
    audioCoverUrl: mediaType === 'AUDIO' ? DEFAULT_AUDIO_COVER_URL : '',
    fileName,
    title: titleFromFileName(fileName),
    description: '',
    tags: [],
    isVideo: mediaType === 'VIDEO',
    isAnimation: mediaType === 'ANIMATION',
    metaText: buildMediaMetaText(mediaType, durationMs),
    mediaType,
    fileType: mediaType.toLowerCase(),
    mimeType: mediaType === 'AUDIO' ? (AUDIO_MIME_TYPES[fileName.split('.').pop().toLowerCase()] || '') : mimeTypeFromFile(raw),
    size: normalizeSize(raw.size),
    sha256: trimText(raw.sha256),
    durationMs,
    width,
    height,
    aspectRatio: buildAspectRatio(width, height),
    status: 'READY',
    progress: 0,
    taskId: null,
    uploadTicket: null,
    coverSha256: trimText(raw.coverSha256),
    customCoverSha256: trimText(raw.customCoverSha256),
    animationFallbackAttempted: Boolean(raw.animationFallbackAttempted),
    confirmIdempotencyKey: raw.confirmIdempotencyKey || `confirm-${Date.now()}-${index}`
  }
}

function normalizeChosenMediaFiles(files = []) {
  return Array.isArray(files) ? files.map(normalizeChosenMediaFile) : []
}

function ascii(bytes, offset, length) {
  if (!bytes || offset < 0 || length < 0 || offset + length > bytes.length) {
    return ''
  }
  let value = ''
  for (let index = offset; index < offset + length; index++) {
    value += String.fromCharCode(bytes[index])
  }
  return value
}

function isGif(bytes) {
  const header = ascii(bytes, 0, 6)
  return header === 'GIF87a' || header === 'GIF89a'
}

function readUint32LittleEndian(bytes, offset) {
  if (offset + 4 > bytes.length) {
    return -1
  }
  return (
    bytes[offset]
    | (bytes[offset + 1] << 8)
    | (bytes[offset + 2] << 16)
    | (bytes[offset + 3] << 24)
  ) >>> 0
}

function isWebp(bytes) {
  return ascii(bytes, 0, 4) === 'RIFF' && ascii(bytes, 8, 4) === 'WEBP'
}

function readLocalMediaBytes(filePath, wxApi, position, length, session, timeoutMs = METADATA_TIMEOUT_MS) {
  const fileSystem = getRuntimeWx(wxApi).getFileSystemManager()
  const args = { filePath, position, length }
  const pending = session
    ? session.call(callbacks => fileSystem.readFile(callbacks), args, { timeoutMs })
    : new Promise((resolve, reject) => fileSystem.readFile(Object.assign({}, args, {
      success: resolve,
      fail(error) { reject(new Error(error && error.errMsg ? error.errMsg : '读取作品文件失败')) }
    })))
  return pending.then(response => new Uint8Array(response.data))
}

function measureChosenMediaFiles(files = [], options = {}) {
  return files.map(file => {
    if (!file || file.mediaType === 'AUDIO') return file
    let size
    try {
      size = getRuntimeWx(options.wxApi).getFileSystemManager().statSync(file.tempFilePath).size
      if (!Number.isSafeInteger(size) || size < 0) throw new Error(MEDIA_STAT_FAILED_MESSAGE)
    } catch (error) {
      throw new Error(MEDIA_STAT_FAILED_MESSAGE)
    }
    return Object.assign({}, file, { size, sha256: size === file.size ? file.sha256 : '' })
  })
}

function isNamedFormat(file, extension) {
  return trimText(file.fileName).toLowerCase().endsWith(`.${extension}`)
    || trimText(file.mimeType).toLowerCase() === `image/${extension}`
}

function isCompressionInterrupted(error) {
  return error && (error.code === COMPRESSION_CANCELLED || error.code === COMPRESSION_TIMEOUT)
}

// 只扫描段结构；大图像载荷按长度跳过，静态身份和透明信息分别返回。
async function inspectWebp(file, bytes, read) {
  const fileSize = file.size
  if (!isWebp(bytes)) return WEBP_UNKNOWN
  // 动画提示即按动图保护，损坏头部不能借此进入静态压缩。
  if (ascii(bytes, 12, 4) === 'VP8X' && bytes.length > 20 && (bytes[20] & WEBP_ANIMATION_FLAG)) {
    return Object.assign({}, WEBP_UNKNOWN, { animated: true })
  }
  if (readUint32LittleEndian(bytes, 4) + 8 !== fileSize) return WEBP_UNKNOWN
  let offset = WEBP_RIFF_HEADER_BYTES
  let chunkCount = 0
  let imageType = ''
  let extendedFlags = null
  let hasAlpha = false
  let alphaHint = null
  let conflicting = false
  while (offset < fileSize) {
    if (chunkCount >= WEBP_MAX_SCAN_CHUNKS || offset + WEBP_CHUNK_HEADER_BYTES > fileSize) return WEBP_UNKNOWN
    const header = offset + WEBP_CHUNK_HEADER_BYTES <= bytes.length
      ? bytes.subarray(offset, offset + WEBP_CHUNK_HEADER_BYTES)
      : await read(offset, WEBP_CHUNK_HEADER_BYTES)
    if (header.length !== WEBP_CHUNK_HEADER_BYTES) return WEBP_UNKNOWN
    chunkCount += 1
    const type = ascii(header, 0, 4)
    const size = readUint32LittleEndian(header, 4)
    const payloadStart = offset + WEBP_CHUNK_HEADER_BYTES
    const next = payloadStart + size + size % 2
    if (size < 0 || next > fileSize) return WEBP_UNKNOWN
    if (WEBP_ANIMATION_CHUNK_TYPES.includes(type)) return Object.assign({}, WEBP_UNKNOWN, { animated: true })
    if (type === 'VP8X') {
      if (size !== WEBP_EXTENDED_HEADER_SIZE || extendedFlags !== null || offset !== WEBP_RIFF_HEADER_BYTES) return WEBP_UNKNOWN
      const flag = payloadStart < bytes.length ? bytes.subarray(payloadStart, payloadStart + 1) : await read(payloadStart, 1)
      if (flag.length !== 1) return WEBP_UNKNOWN
      extendedFlags = flag[0]
      if (extendedFlags & WEBP_ANIMATION_FLAG) return Object.assign({}, WEBP_UNKNOWN, { animated: true })
    } else if (type === 'VP8 ' || type === 'VP8L') {
      if (imageType || size === 0) return WEBP_UNKNOWN
      imageType = type
      if (type === 'VP8L') {
        if (size < WEBP_LOSSLESS_HEADER_SIZE) return WEBP_UNKNOWN
        const header = payloadStart + WEBP_LOSSLESS_HEADER_SIZE <= bytes.length
          ? bytes.subarray(payloadStart, payloadStart + WEBP_LOSSLESS_HEADER_SIZE)
          : await read(payloadStart, WEBP_LOSSLESS_HEADER_SIZE)
        if (header.length !== WEBP_LOSSLESS_HEADER_SIZE || header[0] !== WEBP_VP8L_SIGNATURE || (header[4] & 0xe0)) return WEBP_UNKNOWN
        alphaHint = Boolean(header[4] & WEBP_ALPHA_FLAG)
      }
    } else if (type === 'ALPH') {
      if (!size || hasAlpha || imageType) conflicting = true
      hasAlpha = true
    }
    offset = next
  }
  if (!imageType || offset !== fileSize) return WEBP_UNKNOWN
  const extendedAlpha = extendedFlags !== null && Boolean(extendedFlags & WEBP_ALPHA_FLAG)
  if ((hasAlpha && (extendedFlags === null || imageType !== 'VP8 '))
      || (extendedFlags !== null && imageType === 'VP8 ' && extendedAlpha !== hasAlpha)
      || (extendedFlags !== null && imageType === 'VP8L' && extendedAlpha !== alphaHint)) conflicting = true
  let webpAlpha = 'unknown'
  if (!conflicting) {
    webpAlpha = imageType === 'VP8L' ? (alphaHint ? 'present' : 'unknown')
      : (hasAlpha || extendedAlpha ? 'present' : 'opaque')
  }
  return { staticImageVerified: true, animated: false, webpAlpha, webpAlphaHint: alphaHint }
}

async function classifyChosenMediaFiles(files = [], options = {}) {
  const classified = []
  const { session } = options
  for (const file of Array.isArray(files) ? files : []) {
    if (session) session.assertActive()
    if (!file || file.mediaType !== 'IMAGE') { classified.push(file); continue }
    const large = normalizeSize(file.size) > ANIMATION_MAX_BYTES
    if (large && isNamedFormat(file, 'gif')) throw new Error(ANIMATION_TOO_LARGE_MESSAGE)
    if (large && !isNamedFormat(file, 'webp')) { classified.push(file); continue }
    const deadline = Date.now() + METADATA_TIMEOUT_MS
    let readBytes = 0
    const read = async (position, length) => {
      if (session) session.assertActive()
      const remaining = deadline - Date.now()
      if (remaining <= 0) throw Object.assign(new Error('读取作品信息超时，请重试'), { code: COMPRESSION_TIMEOUT })
      if (length <= 0 || length > MEDIA_SIGNATURE_READ_BYTES || readBytes + length > WEBP_MAX_SCAN_HEADER_BYTES) {
        throw new Error('WebP头部读取超出限制')
      }
      readBytes += length
      const result = await readLocalMediaBytes(file.tempFilePath, options.wxApi, position, length, session, remaining)
      if (session) session.assertActive()
      return result
    }
    let bytes
    let webp = WEBP_UNKNOWN
    try {
      bytes = await read(0, Math.min(normalizeSize(file.size) || MEDIA_SIGNATURE_READ_BYTES, MEDIA_SIGNATURE_READ_BYTES))
      if (isWebp(bytes)) webp = await inspectWebp(file, bytes, read)
    } catch (error) {
      if (isCompressionInterrupted(error)) throw error
      if (large) throw new Error(ANIMATION_TOO_LARGE_MESSAGE)
      classified.push(file)
      continue
    }
    if (large && !webp.staticImageVerified) throw new Error(ANIMATION_TOO_LARGE_MESSAGE)
    const gif = isGif(bytes)
    if (gif || isWebp(bytes)) {
      const animated = gif || webp.animated
      classified.push(Object.assign({}, file, {
        mediaType: animated ? 'ANIMATION' : 'IMAGE',
        mimeType: gif ? 'image/gif' : 'image/webp', fileType: 'image',
        isVideo: false, isAnimation: animated,
        staticImageVerified: !gif && webp.staticImageVerified,
        webpAlpha: webp.webpAlpha, webpAlphaHint: webp.webpAlphaHint,
        metaText: buildMediaMetaText(animated ? 'ANIMATION' : 'IMAGE')
      }))
    } else classified.push(file)
  }
  return classified
}

function getVideoInfo(filePath, wxApi) {
  let runtimeWx
  try {
    runtimeWx = getRuntimeWx(wxApi)
  } catch (error) {
    return Promise.resolve(null)
  }
  if (!filePath || !runtimeWx.getVideoInfo) {
    return Promise.resolve(null)
  }
  return new Promise((resolve) => {
    runtimeWx.getVideoInfo({
      src: filePath,
      success: resolve,
      fail() {
        resolve(null)
      }
    })
  })
}

// 只让微信读取本地时长，不播放、不解析文件内容；退出页面可取消。
function readAudioDuration(filePath, options = {}) {
  return new Promise((resolve, reject) => {
    let context
    let timer
    let poll
    let settled = false
    const finish = (error, durationMs) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      clearInterval(poll)
      if (context) context.destroy()
      if (options.onCancelReady) options.onCancelReady(null)
      if (error) reject(error)
      else resolve(durationMs)
    }
    const read = () => {
      if (settled) return
      const seconds = Number(context.duration)
      if (Number.isFinite(seconds) && seconds > 0) finish(null, Math.max(1, Math.round(seconds * 1000)))
    }
    try {
      context = getRuntimeWx(options.wxApi).createInnerAudioContext()
      context.autoplay = false
      context.onCanplay(read)
      context.onError(() => finish(new Error(AUDIO_READ_FAILED_MESSAGE)))
      timer = setTimeout(() => finish(new Error(AUDIO_READ_FAILED_MESSAGE)), options.timeoutMs || AUDIO_READ_TIMEOUT_MS)
      // canplay 时 duration 可能尚未就绪，只在此次读取的五秒窗口内等待。
      poll = setInterval(read, 100)
      if (options.onCancelReady) options.onCancelReady(() => finish(new Error('cancel')))
      context.src = filePath
    } catch (error) {
      finish(new Error(AUDIO_READ_FAILED_MESSAGE))
    }
  })
}

function getImageInfo(filePath, wxApi) {
  let runtimeWx
  try {
    runtimeWx = getRuntimeWx(wxApi)
  } catch (error) {
    return Promise.resolve(null)
  }
  if (!filePath || !runtimeWx.getImageInfo) {
    return Promise.resolve(null)
  }
  return new Promise((resolve) => {
    runtimeWx.getImageInfo({
      src: filePath,
      success: resolve,
      fail() {
        resolve(null)
      }
    })
  })
}

async function enrichVideoFileMetadata(files = [], options = {}) {
  if (!Array.isArray(files)) {
    return []
  }
  return Promise.all(files.map(async (file) => {
    if (!file) {
      return file
    }
    const currentWidth = normalizeDimension(file.width)
    const currentHeight = normalizeDimension(file.height)
    if (file.mediaType === 'IMAGE' || file.mediaType === 'ANIMATION') {
      if (currentWidth > 0 && currentHeight > 0) {
        return Object.assign({}, file, {
          aspectRatio: buildAspectRatio(currentWidth, currentHeight)
        })
      }
      const imageInfo = await getImageInfo(file.tempFilePath, options.wxApi)
      if (!imageInfo) {
        return file
      }
      const nextFile = Object.assign({}, file, {
        width: currentWidth || normalizeDimension(imageInfo.width),
        height: currentHeight || normalizeDimension(imageInfo.height)
      })
      nextFile.aspectRatio = buildAspectRatio(nextFile.width, nextFile.height)
      return nextFile
    }
    if (file.mediaType !== 'VIDEO') {
      return file
    }
    const currentDurationMs = normalizeSize(file.durationMs)
    if (currentWidth > 0 && currentHeight > 0 && currentDurationMs > 0) {
      return Object.assign({}, file, {
        aspectRatio: buildAspectRatio(currentWidth, currentHeight)
      })
    }
    const videoInfo = await getVideoInfo(file.tempFilePath, options.wxApi)
    if (!videoInfo) {
      return file
    }
    const durationSeconds = normalizeSize(videoInfo.duration)
    const durationMs = currentDurationMs || (durationSeconds > 0
      ? Math.round(durationSeconds * 1000)
      : normalizeSize(videoInfo.durationMs))
    const nextFile = Object.assign({}, file, {
      width: currentWidth || normalizeDimension(videoInfo.width),
      height: currentHeight || normalizeDimension(videoInfo.height),
      durationMs
    })
    nextFile.aspectRatio = buildAspectRatio(nextFile.width, nextFile.height)
    nextFile.metaText = buildMediaMetaText(nextFile.mediaType, nextFile.durationMs)
    return nextFile
  }))
}

function validMediaDimension(value) {
  return Number.isFinite(value) && value > 0
}

// 全批轻量校验先行，避免后面的已知错误浪费前面文件的编码时间。
async function preflightWorkMainFiles(files, { wxApi, session, getCanvas }) {
  const runtimeWx = getRuntimeWx(wxApi)
  if (!files.length || files.length > MAX_BATCH_COUNT) {
    throw new Error(validateChosenMediaFiles(files).message)
  }
  for (const file of files) {
    session.assertActive()
    if (!file || !['IMAGE', 'VIDEO', 'ANIMATION', 'AUDIO'].includes(file.mediaType)) throw new Error(MEDIA_TYPE_UNSUPPORTED_MESSAGE)
    if (file.mediaType === 'AUDIO' || file.mediaType === 'ANIMATION') {
      const validation = validateChosenMediaFiles([file])
      if (!validation.valid) throw new Error(validation.message)
    }
    if (!Number.isSafeInteger(file.size) || file.size <= 0) throw new Error(MEDIA_EMPTY_MESSAGE)
    if (file.mediaType === 'IMAGE' && file.size > IMAGE_MAX_BYTES) {
      if (typeof runtimeWx.getImageInfo !== 'function') throw new Error(IMAGE_PROCESSING_FAILED_MESSAGE)
      const info = await session.call('getImageInfo', { src: file.tempFilePath }, { timeoutMs: METADATA_TIMEOUT_MS })
      session.assertActive()
      const type = trimText(info && info.type).toLowerCase()
      if (!info || !validMediaDimension(info.width) || !validMediaDimension(info.height)
          || !['jpg', 'jpeg', 'png', 'webp'].includes(type)
          || (type === 'webp' && (!file.staticImageVerified || file.isAnimation))) {
        throw new Error(IMAGE_PROCESSING_FAILED_MESSAGE)
      }
      if (type === 'jpg' || type === 'jpeg') {
        if (typeof runtimeWx.compressImage !== 'function') throw new Error(IMAGE_COMPRESSION_UNSUPPORTED_MESSAGE)
      } else if (typeof getCanvas !== 'function' || typeof runtimeWx.canvasToTempFilePath !== 'function') {
        throw new Error(IMAGE_PROCESSING_FAILED_MESSAGE)
      }
    } else if (file.mediaType === 'VIDEO' && file.size > VIDEO_MAX_BYTES) {
      if (typeof runtimeWx.getVideoInfo !== 'function' || typeof runtimeWx.compressVideo !== 'function') {
        throw new Error(VIDEO_COMPRESSION_UNSUPPORTED_MESSAGE)
      }
      const info = await session.call('getVideoInfo', { src: file.tempFilePath }, { timeoutMs: METADATA_TIMEOUT_MS })
      session.assertActive()
      if (!info || !validMediaDimension(info.width) || !validMediaDimension(info.height) || !validMediaDimension(info.duration)) {
        throw new Error(VIDEO_INFO_FAILED_MESSAGE)
      }
      if (info.duration * 1000 > VIDEO_MAX_DURATION_SECONDS * 1000) throw new Error(VIDEO_DURATION_LIMIT_MESSAGE)
    } else if (file.mediaType === 'VIDEO' && file.durationMs > VIDEO_MAX_DURATION_SECONDS * 1000) {
      throw new Error(VIDEO_DURATION_LIMIT_MESSAGE)
    }
  }
}

async function prepareWorkMainFiles(files = [], options = {}) {
  const { session, wxApi, getCanvas, onProgress } = options
  const prepared = []
  const ownedPathsByClientId = {}
  try {
    session.assertActive()
    await preflightWorkMainFiles(files, { wxApi, session, getCanvas })
    session.assertActive()
    for (let index = 0; index < files.length; index += 1) {
      session.assertActive()
      const file = files[index]
      const progress = stage => {
        if (onProgress) onProgress({ mediaType: file.mediaType, index: index + 1, total: files.length, stage })
      }
      progress('reading')
      const onCompress = () => progress('compressing')
      const next = file.mediaType === 'IMAGE'
        ? await compressImageToLimit(file, { session, wxApi, getCanvas, onCompress, maxBytes: IMAGE_MAX_BYTES })
        : file.mediaType === 'VIDEO'
          ? await compressVideoToLimit(file, { session, wxApi, onCompress, maxBytes: VIDEO_MAX_BYTES,
            maxDurationMs: VIDEO_MAX_DURATION_SECONDS * 1000 })
          : file
      session.assertActive()
      prepared.push(Object.assign({}, next, {
        compressionText: next.compressed
          ? `${COMPRESSION_SUMMARY_PREFIX}${formatFileSize(next.originalSize)} → ${formatFileSize(next.size)}` : ''
      }))
    }
    for (const file of prepared) ownedPathsByClientId[file.clientId] = session.transfer([file.tempFilePath])
    return { files: prepared, ownedPathsByClientId }
  } catch (error) {
    releasePreparedWorkFiles({ wxApi, ownedPathsByClientId })
    session.dispose()
    throw error
  }
}

function isConfirmedFile(file = {}) {
  return file.status === 'CONFIRMED' || Boolean(file.confirmedWorkId)
}

function shouldCreateUploadTicket(file = {}) {
  return !isConfirmedFile(file) && !file.taskId
}

function shouldUploadMainFile(file = {}) {
  return !isConfirmedFile(file) && file.status !== 'UPLOADED'
}

function validateChosenMediaFiles(files = []) {
  if (!Array.isArray(files) || files.length === 0) {
    return { valid: false, message: '请选择图片、视频或动图' }
  }
  if (files.length > MAX_BATCH_COUNT) {
    return { valid: false, message: '一次最多上传 9 个作品' }
  }
  for (const file of files) {
    if (file.mediaType === 'AUDIO') {
      if (!AUDIO_MIME_TYPES[trimText(file.fileName).split('.').pop().toLowerCase()]) {
        return { valid: false, message: '音频仅支持 MP3、M4A、AAC、WAV' }
      }
      if (normalizeSize(file.size) <= 0 || file.size > AUDIO_MAX_BYTES) {
        return { valid: false, message: '音频文件不能为空且不能超过 50MB' }
      }
      if (normalizeSize(file.durationMs) <= 0) return { valid: false, message: AUDIO_READ_FAILED_MESSAGE }
      if (file.durationMs > AUDIO_MAX_DURATION_MS) return { valid: false, message: '音频作品不能超过 10 分钟' }
    }
    if (file.mediaType === 'IMAGE' && normalizeSize(file.size) > IMAGE_MAX_BYTES) {
      return { valid: false, message: IMAGE_TOO_LARGE_MESSAGE }
    }
    if (file.mediaType === 'ANIMATION' && normalizeSize(file.size) > ANIMATION_MAX_BYTES) {
      return { valid: false, message: ANIMATION_TOO_LARGE_MESSAGE }
    }
    if (file.mediaType === 'VIDEO' && normalizeSize(file.size) > VIDEO_MAX_BYTES) {
      return { valid: false, message: '视频作品不能超过 100MB' }
    }
    if (file.mediaType === 'VIDEO' && normalizeSize(file.durationMs) > VIDEO_MAX_DURATION_SECONDS * 1000) {
      return { valid: false, message: '视频作品不能超过 10 分钟' }
    }
    if (file.mediaType !== 'IMAGE' && file.mediaType !== 'VIDEO' && file.mediaType !== 'ANIMATION' && file.mediaType !== 'AUDIO') {
      return { valid: false, message: '作品文件格式不支持' }
    }
  }
  return { valid: true, message: '' }
}

function buildUploadTicketPayload(files = [], batchId = '') {
  return {
    batchId,
    files: files
      .filter(shouldCreateUploadTicket)
      .map((file) => {
        const item = {
          clientId: file.clientId,
          mediaType: file.mediaType,
          fileName: file.fileName,
          mimeType: file.mimeType,
          fileSize: file.size,
          durationMs: file.mediaType === 'VIDEO' || file.mediaType === 'AUDIO' ? file.durationMs : null,
          width: file.width,
          height: file.height,
          idempotencyKey: `ticket-${file.clientId}`
        }
        const sha256 = trimText(file.sha256)
        if (file.mediaType === 'AUDIO') {
          delete item.width
          delete item.height
        }
        if (sha256) {
          item.sha256 = sha256
        }
        return item
      })
  }
}

function getCoverUploadPath(file = {}) {
  return trimText(file.customCoverPath) || trimText(file.coverPath)
}

function hasCustomCover(file = {}) {
  return Boolean(trimText(file.customCoverPath))
}

function getCoverFileName(file = {}) {
  return trimText(file.coverFileName)
    || trimText(file.customCoverFileName)
    || buildThumbFileName(file.fileName)
}

function getCoverFileSize(file = {}) {
  if (normalizeSize(file.coverSize)) {
    return normalizeSize(file.coverSize)
  }
  return hasCustomCover(file) ? normalizeSize(file.customCoverSize) : 0
}

function getCoverWidth(file = {}) {
  return hasCustomCover(file)
    ? normalizeSize(file.customCoverWidth)
    : normalizeSize(file.coverWidth)
}

function getCoverHeight(file = {}) {
  return hasCustomCover(file)
    ? normalizeSize(file.customCoverHeight)
    : normalizeSize(file.coverHeight)
}

function getCoverIdempotencyKey(file = {}) {
  if (hasCustomCover(file)) {
    return trimText(file.customCoverIdempotencyKey)
      || trimText(file.coverIdempotencyKey)
      || `cover-ticket-${file.clientId}`
  }
  return trimText(file.coverIdempotencyKey)
    || trimText(file.customCoverIdempotencyKey)
    || `cover-ticket-${file.clientId}`
}

function getCoverSha256(file = {}) {
  return trimText(file.customCoverSha256) || trimText(file.coverSha256)
}

function shouldUploadCoverFile(file = {}) {
  if (isConfirmedFile(file) || file.customCoverStatus === 'UPLOADED') {
    return false
  }
  if (file.mediaType === 'VIDEO') {
    return false
  }
  if (file.mediaType === 'IMAGE') {
    return normalizeSize(file.size) > THUMB_MAX_BYTES && Boolean(getCoverUploadPath(file))
  }
  return false
}

async function prepareCoverUploadFiles(files = [], options = {}) {
  const preparedFiles = []
  for (const file of files) {
    const nextFile = Object.assign({}, file)
    if (isConfirmedFile(nextFile) || nextFile.customCoverStatus === 'UPLOADED') {
      preparedFiles.push(nextFile)
      continue
    }
    if (nextFile.mediaType === 'IMAGE') {
      if (normalizeSize(nextFile.size) <= THUMB_MAX_BYTES) {
        nextFile.coverPath = ''
        nextFile.coverSize = 0
        nextFile.coverSha256 = trimText(nextFile.sha256)
        preparedFiles.push(nextFile)
        continue
      }
      const thumb = await prepareThumbFile(nextFile.tempFilePath, options)
      Object.assign(nextFile, {
        coverPath: thumb.filePath,
        coverSize: thumb.fileSize,
        coverFileName: buildThumbFileName(nextFile.fileName),
        coverSha256: '',
        coverIdempotencyKey: `cover-ticket-${nextFile.clientId}`
      })
      preparedFiles.push(nextFile)
      continue
    }
    if (nextFile.mediaType === 'VIDEO') {
      preparedFiles.push(nextFile)
      continue
    }
    preparedFiles.push(nextFile)
  }
  return preparedFiles
}

function normalizeProgress(value) {
  const numberValue = Number(value)
  if (!Number.isFinite(numberValue)) {
    return 0
  }
  return Math.max(0, Math.min(100, Math.round(numberValue)))
}

function shouldCountCoverProgress(file = {}) {
  if (file.mediaType === 'VIDEO') {
    return false
  }
  if (file.customCoverUploadTicket || file.customCoverStatus) {
    return true
  }
  return file.mediaType === 'IMAGE' && normalizeSize(file.size) > THUMB_MAX_BYTES
}

function buildUploadProgressSummary(files = [], saving = false) {
  if (!saving) {
    return {
      active: false,
      percent: 0,
      text: '保存作品'
    }
  }
  if (!Array.isArray(files) || files.length === 0) {
    return {
      active: true,
      percent: 0,
      text: '准备上传'
    }
  }
  let totalProgress = 0
  let totalUnits = 0
  files.forEach((file) => {
    if (!file) {
      return
    }
    totalUnits += 1
    if (isConfirmedFile(file)) {
      totalProgress += 100
      return
    }
    totalProgress += file.status === 'UPLOADED' ? 100 : normalizeProgress(file.progress)
    if (shouldCountCoverProgress(file)) {
      totalUnits += 1
      totalProgress += file.customCoverStatus === 'UPLOADED'
        ? 100
        : normalizeProgress(file.customCoverProgress)
    }
  })
  const percent = totalUnits > 0 ? Math.min(100, Math.round(totalProgress / totalUnits)) : 0
  return {
    active: true,
    percent,
    text: percent >= 100 ? '确认作品' : `保存中 ${percent}%`
  }
}

function buildCoverUploadTicketPayload(files = [], batchId = '') {
  const coverFiles = files.filter(shouldUploadCoverFile)
  return {
    batchId,
    files: coverFiles.map((file) => {
      const item = {
        clientId: `${file.clientId}${COVER_CLIENT_ID_SUFFIX}`,
        mediaType: 'IMAGE',
        fileName: getCoverFileName(file),
        mimeType: DEFAULT_COVER_MIME_TYPE,
        fileSize: getCoverFileSize(file),
        durationMs: null,
        width: getCoverWidth(file),
        height: getCoverHeight(file),
        sourceTaskId: file.taskId || null,
        idempotencyKey: getCoverIdempotencyKey(file)
      }
      const sha256 = getCoverSha256(file)
      if (sha256) {
        item.sha256 = sha256
      }
      return item
    })
  }
}

function buildUploadCompletePayload(files = []) {
  return {
    items: files
      .filter((file) => file.taskId && !isConfirmedFile(file))
      .map((file) => {
        const item = {
          taskId: file.taskId,
          title: trimText(file.title),
          description: trimText(file.description),
          tagNames: Array.isArray(file.tags) ? file.tags.map(trimText).filter(Boolean) : [],
          aspectRatio: resolveAspectRatio(file),
          idempotencyKey: file.confirmIdempotencyKey
        }
        if (file.mediaType === 'AUDIO') delete item.aspectRatio
        if (file.mediaType !== 'AUDIO' && file.customCoverTaskId && file.customCoverStatus === 'UPLOADED') {
          item.coverTaskId = file.customCoverTaskId
        }
        return item
      })
  }
}

function applyUploadCompleteResults(files = [], items = []) {
  const itemMap = (Array.isArray(items) ? items : []).reduce((result, item) => {
    if (item && item.taskId) {
      result[String(item.taskId)] = item
    }
    return result
  }, {})
  return (Array.isArray(files) ? files : []).map((file) => {
    if (!file || !file.taskId) {
      return file
    }
    const item = itemMap[String(file.taskId)]
    if (!item) {
      return file
    }
    if (item.success) {
      return Object.assign({}, file, {
        status: 'CONFIRMED',
        progress: 100,
        confirmedWorkId: item.workId || file.confirmedWorkId || null,
        errorMessage: '',
        uploadTicket: null,
        customCoverUploadTicket: null,
        customCoverStatus: file.customCoverTaskId ? 'CONFIRMED' : file.customCoverStatus
      })
    }
    return Object.assign({}, file, {
      errorMessage: trimText(item.message) || UPLOAD_COMPLETE_FAILURE_FALLBACK,
      errorCode: trimText(item.errorCode)
    })
  })
}

function applyAnimationSingleFrameFallbacks(files = [], timestamp = Date.now()) {
  return (Array.isArray(files) ? files : []).map((file, index) => {
    if (!file
      || file.mediaType !== 'ANIMATION'
      || file.errorCode !== 'ANIMATION_SINGLE_FRAME'
      || file.animationFallbackAttempted) {
      return file
    }
    const nextClientId = `fallback-image-${timestamp}-${index}`
    return Object.assign({}, file, {
      clientId: nextClientId,
      mediaType: 'IMAGE',
      fileType: 'image',
      isVideo: false,
      isAnimation: false,
      metaText: buildMediaMetaText('IMAGE'),
      taskId: null,
      uploadTicket: null,
      status: 'READY',
      progress: 0,
      errorMessage: '',
      errorCode: '',
      confirmedWorkId: null,
      customCoverTaskId: null,
      customCoverUploadTicket: null,
      customCoverStatus: '',
      customCoverProgress: 0,
      animationFallbackAttempted: true,
      confirmIdempotencyKey: `confirm-${nextClientId}`
    })
  })
}

function buildUploadCompleteFailureMessage(items = []) {
  const failedItems = Array.isArray(items) ? items.filter((item) => item && !item.success) : []
  const messages = failedItems.map((item) => trimText(item.message) || UPLOAD_COMPLETE_FAILURE_FALLBACK)
  return messages.join('；')
}

function uploadToCos(file, ticket, options = {}) {
  const runtimeWx = getRuntimeWx(options.wxApi)
  return new Promise((resolve, reject) => {
    const uploadTask = runtimeWx.uploadFile({
      url: ticket.uploadUrl,
      filePath: file.tempFilePath,
      name: 'file',
      formData: ticket.formData || {},
      timeout: options.timeout || COS_UPLOAD_TIMEOUT,
      success(response) {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve({
            statusCode: response.statusCode,
            objectKey: ticket.objectKey
          })
          return
        }
        reject(new Error(`COS 上传失败(${response.statusCode})`))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : 'COS 上传失败'))
      }
    })
    if (uploadTask && uploadTask.onProgressUpdate && options.onProgress) {
      uploadTask.onProgressUpdate((progress) => {
        options.onProgress(file, progress)
      })
    }
    if (options.onTask && uploadTask) {
      options.onTask(file, uploadTask)
    }
  })
}

async function runPool(items, concurrency, uploadFn) {
  const results = []
  let nextIndex = 0
  const workerCount = Math.min(concurrency, items.length)
  const workers = Array.from({ length: workerCount }, async () => {
    while (nextIndex < items.length) {
      const currentIndex = nextIndex
      nextIndex += 1
      results[currentIndex] = await uploadFn(items[currentIndex], currentIndex)
    }
  })
  await Promise.all(workers)
  return results
}

async function runWorkUploadQueue(items = [], uploadFn, options = {}) {
  const imageConcurrency = options.imageConcurrency || IMAGE_UPLOAD_CONCURRENCY
  const animationConcurrency = options.animationConcurrency || ANIMATION_UPLOAD_CONCURRENCY
  const videoConcurrency = options.videoConcurrency || VIDEO_UPLOAD_CONCURRENCY
  const pendingItems = items.filter(shouldUploadMainFile)
  const images = pendingItems.filter((item) => item.mediaType === 'IMAGE')
  const animations = pendingItems.filter((item) => item.mediaType === 'ANIMATION')
  const videos = pendingItems.filter((item) => item.mediaType === 'VIDEO' || item.mediaType === 'AUDIO')
  const [imageResults, animationResults, videoResults] = await Promise.all([
    runPool(images, imageConcurrency, uploadFn),
    runPool(animations, animationConcurrency, uploadFn),
    runPool(videos, videoConcurrency, uploadFn)
  ])
  return imageResults.concat(animationResults, videoResults)
}

module.exports = {
  AUDIO_MAX_BYTES,
  AUDIO_MIME_TYPES,
  readAudioDuration,
  ANIMATION_MAX_BYTES,
  ANIMATION_UPLOAD_CONCURRENCY,
  COS_UPLOAD_TIMEOUT,
  IMAGE_MAX_BYTES,
  IMAGE_UPLOAD_CONCURRENCY,
  MAX_BATCH_COUNT,
  THUMB_MAX_BYTES,
  VIDEO_MAX_BYTES,
  VIDEO_MAX_DURATION_SECONDS,
  VIDEO_UPLOAD_CONCURRENCY,
  applyAnimationSingleFrameFallbacks,
  applyUploadCompleteResults,
  buildAspectRatio,
  buildThumbFileName,
  buildUploadProgressSummary,
  buildUploadCompletePayload,
  buildUploadCompleteFailureMessage,
  buildCoverUploadTicketPayload,
  buildUploadTicketPayload,
  createChooseCoverImageOptions,
  createChooseMediaOptions,
  chooseAudioFiles,
  classifyChosenMediaFiles,
  measureChosenMediaFiles,
  prepareWorkMainFiles,
  releasePreparedWorkFiles,
  enrichVideoFileMetadata,
  normalizeChosenMediaFiles,
  prepareLocalCoverUploadFile,
  prepareCoverUploadFiles,
  readLocalMediaBytes,
  uploadToCos,
  runWorkUploadQueue,
  validateChosenMediaFiles
}
