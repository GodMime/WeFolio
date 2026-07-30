const {
  THUMB_MAX_BYTES,
  buildAspectRatio,
  normalizeDimension
} = require('./media')
const { calculateFileSha256 } = require('./sha256')

const MAX_BATCH_COUNT = 9
const IMAGE_MAX_BYTES = 10 * 1024 * 1024
const ANIMATION_MAX_BYTES = 10 * 1024 * 1024
const VIDEO_MAX_BYTES = 100 * 1024 * 1024
const VIDEO_MAX_DURATION_SECONDS = 10 * 60
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
const STATIC_IMAGE_MAIN_COMPRESS_ATTEMPTS = [
  { quality: 90, compressedSize: 2048 },
  { quality: 82, compressedSize: 1600 },
  { quality: 74, compressedSize: 1280 },
  { quality: 66, compressedSize: 960 }
]
const IMAGE_TOO_LARGE_MESSAGE = '图片作品不能超过 10MB'
const ANIMATION_TOO_LARGE_MESSAGE = '动图作品不能超过 10MB'

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
  const mediaType = raw.fileType === 'video' ? 'VIDEO' : 'IMAGE'
  const durationMs = raw.duration ? Math.round(Number(raw.duration) * 1000) : normalizeSize(raw.durationMs)
  const width = normalizeDimension(raw.width)
  const height = normalizeDimension(raw.height)
  return {
    id: raw.id || `local-${Date.now()}-${index}`,
    clientId: raw.clientId || `client-${Date.now()}-${index}`,
    tempFilePath: filePath,
    coverPath: trimText(raw.thumbTempFilePath),
    fileName,
    title: titleFromFileName(fileName),
    description: '',
    tags: [],
    isVideo: mediaType === 'VIDEO',
    isAnimation: mediaType === 'ANIMATION',
    metaText: buildMediaMetaText(mediaType, durationMs),
    mediaType,
    fileType: mediaType === 'VIDEO' ? 'video' : 'image',
    mimeType: mimeTypeFromFile(raw),
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

function findRiffChunk(bytes, expectedType) {
  let offset = 12
  while (offset + 8 <= bytes.length) {
    const chunkType = ascii(bytes, offset, 4)
    const chunkSize = readUint32LittleEndian(bytes, offset + 4)
    if (chunkType === expectedType) {
      return true
    }
    if (chunkSize < 0) {
      return false
    }
    offset += 8 + chunkSize + (chunkSize % 2)
  }
  return false
}

function isWebp(bytes) {
  return ascii(bytes, 0, 4) === 'RIFF' && ascii(bytes, 8, 4) === 'WEBP'
}

function isAnimatedWebp(bytes) {
  if (!isWebp(bytes)) {
    return false
  }
  if (ascii(bytes, 12, 4) === 'VP8X' && bytes.length > 20 && (bytes[20] & 0x02) !== 0) {
    return true
  }
  return findRiffChunk(bytes, 'ANIM') || findRiffChunk(bytes, 'ANMF')
}

function readLocalMediaBytes(filePath, wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  return new Promise((resolve, reject) => {
    runtimeWx.getFileSystemManager().readFile({
      filePath,
      success(response) {
        resolve(new Uint8Array(response.data))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : '读取作品文件失败'))
      }
    })
  })
}

function isGifOrWebpCandidate(file = {}) {
  const fileName = trimText(file.fileName).toLowerCase()
  const mimeType = trimText(file.mimeType).toLowerCase()
  return fileName.endsWith('.gif')
    || fileName.endsWith('.webp')
    || mimeType === 'image/gif'
    || mimeType === 'image/webp'
}

async function classifyChosenMediaFiles(files = [], options = {}) {
  const classified = []
  for (const file of Array.isArray(files) ? files : []) {
    if (!file || file.mediaType !== 'IMAGE') {
      classified.push(file)
      continue
    }
    if (normalizeSize(file.size) > ANIMATION_MAX_BYTES && isGifOrWebpCandidate(file)) {
      throw new Error(ANIMATION_TOO_LARGE_MESSAGE)
    }
    if (normalizeSize(file.size) > ANIMATION_MAX_BYTES) {
      classified.push(file)
      continue
    }
    let bytes
    try {
      bytes = await readLocalMediaBytes(file.tempFilePath, options.wxApi)
    } catch (error) {
      classified.push(file)
      continue
    }
    if (isGif(bytes)) {
      classified.push(Object.assign({}, file, {
        mediaType: 'ANIMATION',
        mimeType: 'image/gif',
        fileType: 'image',
        isVideo: false,
        isAnimation: true,
        metaText: buildMediaMetaText('ANIMATION')
      }))
      continue
    }
    if (isWebp(bytes)) {
      const animated = isAnimatedWebp(bytes)
      classified.push(Object.assign({}, file, {
        mediaType: animated ? 'ANIMATION' : 'IMAGE',
        mimeType: 'image/webp',
        fileType: 'image',
        isVideo: false,
        isAnimation: animated,
        metaText: buildMediaMetaText(animated ? 'ANIMATION' : 'IMAGE')
      }))
      continue
    }
    classified.push(file)
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

async function prepareStaticImageMainFile(file, options = {}) {
  if (!file || file.mediaType !== 'IMAGE' || normalizeSize(file.size) <= IMAGE_MAX_BYTES) {
    return file
  }
  for (const attempt of STATIC_IMAGE_MAIN_COMPRESS_ATTEMPTS) {
    const tempFilePath = await compressImageFile(file.tempFilePath, attempt, options)
    const fileSize = getLocalFileSize(tempFilePath, options.wxApi)
    if (fileSize <= 0 || fileSize > IMAGE_MAX_BYTES) {
      continue
    }
    const imageInfo = await getImageInfo(tempFilePath, options.wxApi)
    const calculateSha256 = options.calculateSha256
      || ((path) => calculateFileSha256(path, { wxApi: options.wxApi }))
    const nextFile = Object.assign({}, file, {
      tempFilePath,
      size: fileSize,
      sha256: await calculateSha256(tempFilePath)
    })
    if (imageInfo) {
      nextFile.width = normalizeDimension(imageInfo.width)
      nextFile.height = normalizeDimension(imageInfo.height)
      nextFile.aspectRatio = buildAspectRatio(nextFile.width, nextFile.height)
    }
    return nextFile
  }
  throw new Error(IMAGE_TOO_LARGE_MESSAGE)
}

async function prepareStaticImageMainFiles(files = [], options = {}) {
  const prepared = []
  for (const file of Array.isArray(files) ? files : []) {
    prepared.push(await prepareStaticImageMainFile(file, options))
  }
  return prepared
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
    if (file.mediaType !== 'IMAGE' && file.mediaType !== 'VIDEO' && file.mediaType !== 'ANIMATION') {
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
          durationMs: file.mediaType === 'VIDEO' ? file.durationMs : null,
          width: file.width,
          height: file.height,
          idempotencyKey: `ticket-${file.clientId}`
        }
        const sha256 = trimText(file.sha256)
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
        if (file.customCoverTaskId && file.customCoverStatus === 'UPLOADED') {
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
  const videos = pendingItems.filter((item) => item.mediaType === 'VIDEO')
  const [imageResults, animationResults, videoResults] = await Promise.all([
    runPool(images, imageConcurrency, uploadFn),
    runPool(animations, animationConcurrency, uploadFn),
    runPool(videos, videoConcurrency, uploadFn)
  ])
  return imageResults.concat(animationResults, videoResults)
}

module.exports = {
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
  classifyChosenMediaFiles,
  enrichVideoFileMetadata,
  normalizeChosenMediaFiles,
  prepareLocalCoverUploadFile,
  prepareCoverUploadFiles,
  prepareStaticImageMainFiles,
  readLocalMediaBytes,
  uploadToCos,
  runWorkUploadQueue,
  validateChosenMediaFiles
}
