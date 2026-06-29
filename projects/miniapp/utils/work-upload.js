const MAX_BATCH_COUNT = 9
const IMAGE_MAX_BYTES = 10 * 1024 * 1024
const VIDEO_MAX_BYTES = 100 * 1024 * 1024
const VIDEO_MAX_DURATION_SECONDS = 10 * 60
const IMAGE_UPLOAD_CONCURRENCY = 2
const VIDEO_UPLOAD_CONCURRENCY = 1
const COS_UPLOAD_TIMEOUT = 10 * 60 * 1000
const UPLOAD_COMPLETE_FAILURE_FALLBACK = '部分作品确认失败'

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

function createChooseMediaOptions(remainingCount = MAX_BATCH_COUNT) {
  const count = Math.max(0, Math.min(MAX_BATCH_COUNT, Number(remainingCount) || MAX_BATCH_COUNT))
  return {
    count,
    mediaType: ['image', 'video'],
    sourceType: ['album']
  }
}

function fileNameFromPath(filePath) {
  const value = trimText(filePath)
  const segments = value.split('/')
  return segments[segments.length - 1] || '未命名作品'
}

function titleFromFileName(fileName) {
  const value = trimText(fileName)
  const index = value.lastIndexOf('.')
  return index > 0 ? value.slice(0, index) : value
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
    metaText: buildMediaMetaText(mediaType, durationMs),
    mediaType,
    fileType: mediaType === 'VIDEO' ? 'video' : 'image',
    mimeType: mimeTypeFromFile(raw),
    size: normalizeSize(raw.size),
    durationMs,
    width: normalizeSize(raw.width),
    height: normalizeSize(raw.height),
    status: 'READY',
    progress: 0,
    taskId: null,
    uploadTicket: null,
    confirmIdempotencyKey: raw.confirmIdempotencyKey || `confirm-${Date.now()}-${index}`
  }
}

function normalizeChosenMediaFiles(files = []) {
  return Array.isArray(files) ? files.map(normalizeChosenMediaFile) : []
}

function validateChosenMediaFiles(files = []) {
  if (!Array.isArray(files) || files.length === 0) {
    return { valid: false, message: '请选择图片或视频' }
  }
  if (files.length > MAX_BATCH_COUNT) {
    return { valid: false, message: '一次最多上传 9 个作品' }
  }
  for (const file of files) {
    if (file.mediaType === 'IMAGE' && normalizeSize(file.size) > IMAGE_MAX_BYTES) {
      return { valid: false, message: '图片作品不能超过 10MB' }
    }
    if (file.mediaType === 'VIDEO' && normalizeSize(file.size) > VIDEO_MAX_BYTES) {
      return { valid: false, message: '视频作品不能超过 100MB' }
    }
    if (file.mediaType === 'VIDEO' && normalizeSize(file.durationMs) > VIDEO_MAX_DURATION_SECONDS * 1000) {
      return { valid: false, message: '视频作品不能超过 10 分钟' }
    }
    if (file.mediaType !== 'IMAGE' && file.mediaType !== 'VIDEO') {
      return { valid: false, message: '作品文件格式不支持' }
    }
  }
  return { valid: true, message: '' }
}

function buildUploadTicketPayload(files = [], batchId = '') {
  return {
    batchId,
    files: files.map((file) => ({
      clientId: file.clientId,
      mediaType: file.mediaType,
      fileName: file.fileName,
      mimeType: file.mimeType,
      fileSize: file.size,
      durationMs: file.mediaType === 'VIDEO' ? file.durationMs : null,
      width: file.width,
      height: file.height,
      idempotencyKey: `ticket-${file.clientId}`
    }))
  }
}

function buildUploadCompletePayload(files = []) {
  return {
    items: files
      .filter((file) => file.taskId)
      .map((file) => ({
        taskId: file.taskId,
        title: trimText(file.title),
        description: trimText(file.description),
        tagNames: Array.isArray(file.tags) ? file.tags.map(trimText).filter(Boolean) : [],
        idempotencyKey: file.confirmIdempotencyKey
      }))
  }
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
  const videoConcurrency = options.videoConcurrency || VIDEO_UPLOAD_CONCURRENCY
  const images = items.filter((item) => item.mediaType === 'IMAGE')
  const videos = items.filter((item) => item.mediaType === 'VIDEO')
  const [imageResults, videoResults] = await Promise.all([
    runPool(images, imageConcurrency, uploadFn),
    runPool(videos, videoConcurrency, uploadFn)
  ])
  return imageResults.concat(videoResults)
}

module.exports = {
  COS_UPLOAD_TIMEOUT,
  IMAGE_MAX_BYTES,
  IMAGE_UPLOAD_CONCURRENCY,
  MAX_BATCH_COUNT,
  VIDEO_MAX_BYTES,
  VIDEO_MAX_DURATION_SECONDS,
  VIDEO_UPLOAD_CONCURRENCY,
  buildUploadCompletePayload,
  buildUploadCompleteFailureMessage,
  buildUploadTicketPayload,
  createChooseMediaOptions,
  normalizeChosenMediaFiles,
  uploadToCos,
  runWorkUploadQueue,
  validateChosenMediaFiles
}
