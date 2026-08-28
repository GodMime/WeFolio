const { request } = require('./request')

const FEEDBACKS_ENDPOINT = '/api/mine/feedbacks'
const MAX_DESCRIPTION_LENGTH = 200
const MAX_ATTACHMENT_COUNT = 3
const IMAGE_MAX_BYTES = 10 * 1024 * 1024
const VIDEO_MAX_BYTES = 100 * 1024 * 1024
const VIDEO_MAX_DURATION_MS = 10 * 60 * 1000
const IMAGE_UPLOAD_CONCURRENCY = 2
const COS_UPLOAD_TIMEOUT_MS = 10 * 60 * 1000
const COS_FILE_ALREADY_EXISTS_STATUS = 409
const COS_FILE_ALREADY_EXISTS_PATTERN = /<Code>\s*FileAlreadyExists\s*<\/Code>/
const SHANGHAI_UTC_OFFSET_HOURS = 8
const LOCAL_DATE_TIME_PATTERN = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?$/

const FEEDBACK_STATUS_META = Object.freeze({
  PROCESSING: Object.freeze({ text: '处理中', tone: 'blue' }),
  WAITING_FOLLOW_UP: Object.freeze({ text: '待再次反馈', tone: 'amber' }),
  RESOLVED: Object.freeze({ text: '已处理', tone: 'teal' })
})

const IMAGE_MIME_BY_EXTENSION = Object.freeze({
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  webp: 'image/webp'
})

const VIDEO_MIME_BY_EXTENSION = Object.freeze({
  mp4: 'video/mp4',
  mov: 'video/quicktime'
})

let identifierSequence = 0

function text(value) {
  return String(value == null ? '' : value).trim()
}

function unicodeLength(value) {
  return Array.from(value).length
}

function positiveNumber(value) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) && numberValue > 0 ? numberValue : 0
}

function nonNegativeNumber(value) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) && numberValue >= 0 ? numberValue : 0
}

function positiveId(value) {
  const numberValue = Number(value)
  return Number.isInteger(numberValue) && numberValue > 0 ? numberValue : null
}

function normalizeMimeType(value) {
  return text(value).split(';')[0].trim().toLowerCase()
}

function fileNameFromPath(filePath) {
  const segments = text(filePath).split('/')
  return segments[segments.length - 1] || ''
}

function fileExtension(fileName) {
  const normalized = text(fileName)
  const separatorIndex = normalized.lastIndexOf('.')
  if (separatorIndex <= 0 || separatorIndex === normalized.length - 1) {
    return ''
  }
  return normalized.slice(separatorIndex + 1).toLowerCase()
}

function inferredMimeType(mediaType, extension) {
  return mediaType === 'VIDEO'
    ? (VIDEO_MIME_BY_EXTENSION[extension] || '')
    : (IMAGE_MIME_BY_EXTENSION[extension] || '')
}

function isPrintableAsciiIdentifier(value) {
  return /^[\x21-\x7e]{1,64}$/.test(String(value || ''))
}

// 提交键与附件键需要跨失败重提保持稳定，避免服务端把同一次操作写成多条记录。
function generateFeedbackIdentifier(prefix = 'feedback', options = {}) {
  if (typeof options.idFactory === 'function') {
    const customValue = String(options.idFactory(prefix) || '')
    if (!isPrintableAsciiIdentifier(customValue)) {
      throw new Error('反馈标识必须为 1 至 64 个 ASCII 字符')
    }
    return customValue
  }
  identifierSequence = (identifierSequence + 1) % 1679616
  const safePrefix = String(prefix || 'feedback').replace(/[^A-Za-z0-9_-]/g, '-').slice(0, 24) || 'feedback'
  const nowValue = options.nowMs === undefined ? Date.now() : Number(options.nowMs)
  const randomFn = typeof options.randomFn === 'function' ? options.randomFn : Math.random
  const randomValue = Math.floor(Math.max(0, Math.min(0.999999999, Number(randomFn()) || 0)) * 2176782336)
  return `${safePrefix}-${Math.max(0, nowValue).toString(36)}-${randomValue.toString(36)}-${identifierSequence.toString(36)}`.slice(0, 64)
}

function validateFeedbackDescription(value) {
  const description = text(value)
  const length = unicodeLength(description)
  if (length === 0) {
    return { valid: false, message: '请输入问题描述', description, length }
  }
  if (length > MAX_DESCRIPTION_LENGTH) {
    return { valid: false, message: '问题描述不能超过 200 个字符', description, length }
  }
  return { valid: true, message: '', description, length }
}

function normalizeMediaType(file = {}) {
  const declared = text(file.mediaType).toUpperCase()
  if (declared === 'IMAGE' || declared === 'VIDEO') {
    return declared
  }
  if (declared) {
    return declared
  }
  return text(file.fileType).toLowerCase() === 'video' ? 'VIDEO' : 'IMAGE'
}

function validateFeedbackAttachment(file = {}) {
  const mediaType = normalizeMediaType(file)
  if (mediaType !== 'IMAGE' && mediaType !== 'VIDEO') {
    return { valid: false, message: '附件格式不支持' }
  }
  const fileName = text(file.fileName || file.name) || fileNameFromPath(file.tempFilePath || file.path)
  if (!fileName || fileName.includes('/') || fileName.includes('\\')) {
    return { valid: false, message: '附件文件名无效' }
  }
  const extension = fileExtension(fileName)
  const mimeType = normalizeMimeType(file.mimeType || file.type)
    || inferredMimeType(mediaType, extension)
  const expectedMimeType = inferredMimeType(mediaType, extension)
  if (!expectedMimeType || mimeType !== expectedMimeType) {
    return { valid: false, message: '附件格式不支持' }
  }
  const fileSize = positiveNumber(file.fileSize === undefined ? file.size : file.fileSize)
  if (!fileSize) {
    return { valid: false, message: '无法读取附件大小' }
  }
  if (mediaType === 'IMAGE' && fileSize > IMAGE_MAX_BYTES) {
    return { valid: false, message: '图片不能超过 10MB' }
  }
  if (mediaType === 'VIDEO' && fileSize > VIDEO_MAX_BYTES) {
    return { valid: false, message: '视频不能超过 100MB' }
  }
  const durationMs = nonNegativeNumber(file.durationMs)
  if (mediaType === 'IMAGE' && durationMs !== 0) {
    return { valid: false, message: '图片附件时长无效' }
  }
  if (mediaType === 'VIDEO' && (durationMs <= 0 || durationMs > VIDEO_MAX_DURATION_MS)) {
    return { valid: false, message: '视频不能超过 10 分钟' }
  }
  return { valid: true, message: '' }
}

function validateFeedbackDraft(description, attachments = []) {
  const descriptionValidation = validateFeedbackDescription(description)
  if (!descriptionValidation.valid) {
    return descriptionValidation
  }
  if (!Array.isArray(attachments) || attachments.length > MAX_ATTACHMENT_COUNT) {
    return { valid: false, message: '每轮最多添加 3 个附件', description: descriptionValidation.description }
  }
  const clientIds = new Set()
  for (const attachment of attachments) {
    const attachmentValidation = validateFeedbackAttachment(attachment)
    if (!attachmentValidation.valid) {
      return Object.assign({}, attachmentValidation, { description: descriptionValidation.description })
    }
    const clientId = text(attachment.clientId)
    if (!isPrintableAsciiIdentifier(clientId) || clientIds.has(clientId)) {
      return { valid: false, message: '附件标识无效', description: descriptionValidation.description }
    }
    clientIds.add(clientId)
  }
  return {
    valid: true,
    message: '',
    description: descriptionValidation.description,
    attachments
  }
}

function createFeedbackDraft(options = {}) {
  return {
    idempotencyKey: generateFeedbackIdentifier('feedback-submit', options),
    description: '',
    attachments: []
  }
}

function normalizeChosenFeedbackFile(raw = {}, options = {}) {
  const tempFilePath = text(raw.tempFilePath || raw.path)
  const fileName = text(raw.fileName || raw.name) || fileNameFromPath(tempFilePath)
  const mediaType = normalizeMediaType(raw)
  const extension = fileExtension(fileName)
  const durationMs = raw.durationMs === undefined
    ? Math.round(nonNegativeNumber(raw.duration) * 1000)
    : nonNegativeNumber(raw.durationMs)
  const existingClientId = text(raw.clientId)
  return {
    clientId: isPrintableAsciiIdentifier(existingClientId)
      ? existingClientId
      : generateFeedbackIdentifier('feedback-file', options),
    tempFilePath,
    fileName,
    mediaType,
    mimeType: normalizeMimeType(raw.mimeType || raw.type) || inferredMimeType(mediaType, extension),
    fileSize: positiveNumber(raw.fileSize === undefined ? raw.size : raw.fileSize),
    durationMs: mediaType === 'VIDEO' ? durationMs : 0,
    status: 'READY',
    taskId: null,
    expiresAt: ''
  }
}

function addChosenFeedbackMedia(draft = {}, tempFiles = [], options = {}) {
  const currentAttachments = Array.isArray(draft.attachments)
    ? draft.attachments.map((item) => Object.assign({}, item))
    : []
  const chosenFiles = Array.isArray(tempFiles) ? tempFiles : []
  if (currentAttachments.length + chosenFiles.length > MAX_ATTACHMENT_COUNT) {
    throw new Error('每轮最多添加 3 个附件')
  }
  const attachments = currentAttachments.concat(
    chosenFiles.map((item) => normalizeChosenFeedbackFile(item, options))
  )
  return {
    idempotencyKey: isPrintableAsciiIdentifier(draft.idempotencyKey)
      ? draft.idempotencyKey
      : generateFeedbackIdentifier('feedback-submit', options),
    description: draft.description || '',
    attachments
  }
}

function buildFeedbackUploadTicketPayload(attachments = []) {
  return {
    files: attachments.map((file) => ({
      clientId: text(file.clientId),
      fileName: text(file.fileName),
      mediaType: normalizeMediaType(file),
      mimeType: normalizeMimeType(file.mimeType),
      fileSize: positiveNumber(file.fileSize === undefined ? file.size : file.fileSize),
      durationMs: normalizeMediaType(file) === 'VIDEO' ? nonNegativeNumber(file.durationMs) : 0
    }))
  }
}

function buildFeedbackSubmitPayload(draft = {}) {
  const descriptionValidation = validateFeedbackDescription(draft.description)
  if (!descriptionValidation.valid) {
    throw new Error(descriptionValidation.message)
  }
  const idempotencyKey = text(draft.idempotencyKey)
  if (!isPrintableAsciiIdentifier(idempotencyKey)) {
    throw new Error('反馈幂等键必须为 1 至 64 个 ASCII 字符')
  }
  const attachments = Array.isArray(draft.attachments) ? draft.attachments : []
  const uploadTaskIds = attachments.map((item) => positiveId(item.taskId))
  if (uploadTaskIds.some((taskId) => !taskId)) {
    throw new Error('反馈附件尚未上传完成')
  }
  return {
    idempotencyKey,
    description: descriptionValidation.description,
    uploadTaskIds
  }
}

function fetchFeedbackCreationState(requestFn = request) {
  return requestFn({ url: `${FEEDBACKS_ENDPOINT}/creation-state` })
}

function createFeedbackUploadTickets(requestFn = request, payload) {
  return requestFn({
    url: `${FEEDBACKS_ENDPOINT}/upload-tickets`,
    method: 'POST',
    data: payload
  })
}

function createFeedback(requestFn = request, payload) {
  return requestFn({
    url: FEEDBACKS_ENDPOINT,
    method: 'POST',
    data: payload
  })
}

function fetchFeedbackList(requestFn = request, pageOptions = {}) {
  const requestedPageNo = Number(pageOptions.pageNo)
  const requestedPageSize = Number(pageOptions.pageSize)
  return requestFn({
    url: FEEDBACKS_ENDPOINT,
    data: {
      pageNo: Number.isInteger(requestedPageNo) && requestedPageNo > 0 ? requestedPageNo : 1,
      pageSize: Number.isInteger(requestedPageSize) && requestedPageSize > 0
        ? Math.min(requestedPageSize, 50)
        : 20
    }
  })
}

function feedbackDetailEndpoint(feedbackId, suffix = '') {
  const id = positiveId(feedbackId)
  if (!id) {
    throw new Error('反馈 ID 无效')
  }
  return `${FEEDBACKS_ENDPOINT}/${id}${suffix}`
}

function fetchFeedbackDetail(requestFn = request, feedbackId) {
  return requestFn({ url: feedbackDetailEndpoint(feedbackId) })
}

function appendFeedbackRound(requestFn = request, feedbackId, payload) {
  return requestFn({
    url: feedbackDetailEndpoint(feedbackId, '/rounds'),
    method: 'POST',
    data: payload
  })
}

function feedbackStatusMeta(status, backendText) {
  const fallback = FEEDBACK_STATUS_META[status]
  return {
    text: text(backendText) || (fallback ? fallback.text : text(status) || '未知状态'),
    tone: fallback ? fallback.tone : 'muted'
  }
}

function normalizeFeedbackAttachment(raw = {}) {
  const mediaType = normalizeMediaType(raw)
  return {
    mediaType,
    mimeType: normalizeMimeType(raw.mimeType),
    size: nonNegativeNumber(raw.size),
    durationMs: nonNegativeNumber(raw.durationMs),
    url: text(raw.url),
    isImage: mediaType === 'IMAGE',
    isVideo: mediaType === 'VIDEO'
  }
}

function normalizeFeedbackRound(raw = {}) {
  const roundNo = Math.max(1, Number(raw.roundNo) || 1)
  const attachments = Array.isArray(raw.attachments)
    ? raw.attachments.map(normalizeFeedbackAttachment)
    : []
  return {
    roundNo,
    titleText: `第 ${roundNo} 轮反馈`,
    description: text(raw.description),
    submittedAt: text(raw.submittedAt),
    submittedAtText: text(raw.submittedAtText || raw.submittedAt),
    teamResult: text(raw.teamResult),
    teamResultAt: text(raw.teamResultAt),
    teamResultAtText: text(raw.teamResultAtText || raw.teamResultAt),
    attachments
  }
}

// 团队结果归入对应轮次，当前尚未归档的结果追加在时间线末尾。
function buildFeedbackTimeline(rounds, currentResult, currentResultAt) {
  const timeline = []
  rounds.forEach((round) => {
    timeline.push({
      key: `round-${round.roundNo}-user`,
      type: 'USER',
      roundNo: round.roundNo,
      titleText: round.titleText,
      description: round.description,
      time: round.submittedAt,
      timeText: round.submittedAtText,
      attachments: round.attachments
    })
    if (round.teamResult) {
      timeline.push({
        key: `round-${round.roundNo}-team`,
        type: 'TEAM',
        roundNo: round.roundNo,
        titleText: '团队反馈',
        description: round.teamResult,
        time: round.teamResultAt,
        timeText: round.teamResultAtText,
        attachments: []
      })
    }
  })
  if (currentResult) {
    timeline.push({
      key: 'team-current',
      type: 'TEAM_CURRENT',
      roundNo: rounds.length,
      titleText: '团队反馈',
      description: currentResult,
      time: currentResultAt,
      timeText: currentResultAt,
      attachments: []
    })
  }
  return timeline
}

function normalizeFeedbackDetail(raw = {}) {
  const status = text(raw.status)
  const statusMeta = feedbackStatusMeta(status, raw.statusText)
  const rounds = (Array.isArray(raw.rounds) ? raw.rounds : [])
    .map(normalizeFeedbackRound)
    .sort((left, right) => left.roundNo - right.roundNo)
    .slice(0, 3)
  const feedbackResult = text(raw.feedbackResult)
  const feedbackResultAt = text(raw.feedbackResultAt)
  const canAppendRound = raw.canAppendRound === undefined
    ? status === 'WAITING_FOLLOW_UP' && rounds.length < 3
    : Boolean(raw.canAppendRound)
  return {
    id: positiveId(raw.id || raw.feedbackId),
    feedbackId: positiveId(raw.id || raw.feedbackId),
    feedbackNo: text(raw.feedbackNo),
    status,
    statusText: statusMeta.text,
    statusTone: statusMeta.tone,
    feedbackResult,
    feedbackResultAt,
    roundCount: nonNegativeNumber(raw.roundCount) || rounds.length,
    attachmentCount: nonNegativeNumber(raw.attachmentCount),
    createdAt: text(raw.createdAt),
    createdAtText: text(raw.createdAtText || raw.createdAt),
    updatedAt: text(raw.updatedAt),
    updatedAtText: text(raw.updatedAtText || raw.updatedAt),
    canAppendRound,
    rounds,
    timeline: buildFeedbackTimeline(rounds, feedbackResult, feedbackResultAt)
  }
}

function normalizeFeedbackListItem(raw = {}) {
  const status = text(raw.status)
  const statusMeta = feedbackStatusMeta(status, raw.statusText)
  const id = positiveId(raw.id || raw.feedbackId)
  return {
    id,
    feedbackId: id,
    feedbackNo: text(raw.feedbackNo),
    status,
    statusText: statusMeta.text,
    statusTone: statusMeta.tone,
    descriptionSummary: text(raw.descriptionSummary),
    roundCount: nonNegativeNumber(raw.roundCount),
    attachmentCount: nonNegativeNumber(raw.attachmentCount),
    createdAt: text(raw.createdAt),
    createdAtText: text(raw.createdAtText || raw.createdAt),
    updatedAt: text(raw.updatedAt),
    updatedAtText: text(raw.updatedAtText || raw.updatedAt)
  }
}

function uniqueFeedbackItems(items = []) {
  const seen = new Set()
  const normalized = []
  items.forEach((raw) => {
    const item = normalizeFeedbackListItem(raw)
    if (!item.id || seen.has(item.id)) {
      return
    }
    seen.add(item.id)
    normalized.push(item)
  })
  return normalized
}

function normalizeFeedbackPage(raw = {}) {
  const pageNo = Number(raw.pageNo)
  const pageSize = Number(raw.pageSize)
  return {
    pageNo: Number.isInteger(pageNo) && pageNo > 0 ? pageNo : 1,
    pageSize: Number.isInteger(pageSize) && pageSize > 0 ? Math.min(pageSize, 50) : 20,
    total: nonNegativeNumber(raw.total),
    hasMore: Boolean(raw.hasMore),
    items: uniqueFeedbackItems(Array.isArray(raw.items) ? raw.items : [])
  }
}

function mergeFeedbackPage(current = {}, incoming = {}) {
  const nextPage = normalizeFeedbackPage(incoming)
  if (nextPage.pageNo === 1) {
    return nextPage
  }
  const currentPage = normalizeFeedbackPage(current)
  return Object.assign({}, nextPage, {
    items: uniqueFeedbackItems(currentPage.items.concat(nextPage.items))
  })
}

// 后端 LocalDateTime 可能使用空格或 T 分隔，均按上海时区解释并回环校验非法日期。
function parseFeedbackTicketExpiryMs(value) {
  const normalized = text(value)
  if (/(?:Z|[+-]\d{2}:\d{2})$/i.test(normalized)) {
    return Date.parse(normalized)
  }
  const match = normalized.match(LOCAL_DATE_TIME_PATTERN)
  if (!match) {
    return Number.NaN
  }
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const hour = Number(match[4])
  const minute = Number(match[5])
  const second = Number(match[6])
  const millisecond = Number((match[7] || '').padEnd(3, '0').slice(0, 3))
  const expiresAtMs = Date.UTC(
    year, month - 1, day, hour - SHANGHAI_UTC_OFFSET_HOURS, minute, second, millisecond
  )
  const shanghaiLocal = new Date(expiresAtMs + SHANGHAI_UTC_OFFSET_HOURS * 60 * 60 * 1000)
  const isExact = shanghaiLocal.getUTCFullYear() === year
    && shanghaiLocal.getUTCMonth() === month - 1
    && shanghaiLocal.getUTCDate() === day
    && shanghaiLocal.getUTCHours() === hour
    && shanghaiLocal.getUTCMinutes() === minute
    && shanghaiLocal.getUTCSeconds() === second
    && shanghaiLocal.getUTCMilliseconds() === millisecond
  return isExact ? expiresAtMs : Number.NaN
}

function ticketIsValid(file, nowMs) {
  const expiresAtMs = parseFeedbackTicketExpiryMs(file.expiresAt)
  return Boolean(positiveId(file.taskId))
    && Number.isFinite(expiresAtMs)
    && expiresAtMs > nowMs
}

function resetUploadState(file, options) {
  return Object.assign({}, file, {
    clientId: generateFeedbackIdentifier('feedback-file', options),
    status: 'READY',
    taskId: null,
    expiresAt: '',
    uploadTicket: undefined,
    errorMessage: ''
  })
}

// 过期任务必须换新 clientId，否则服务端幂等映射会返回已经失效的旧票据。
function prepareAttachmentsForSubmission(attachments, nowMs, options) {
  return attachments.map((file) => {
    const copied = Object.assign({}, file, { uploadTicket: undefined })
    const hasExpiredTask = Boolean(copied.taskId) && !ticketIsValid(copied, nowMs)
    const invalidUploadedTask = copied.status === 'UPLOADED' && !ticketIsValid(copied, nowMs)
    if (hasExpiredTask || invalidUploadedTask) {
      return resetUploadState(copied, options)
    }
    return copied
  })
}

function applyUploadTickets(attachments, ticketResponse, nowMs) {
  const ticketItems = ticketResponse && Array.isArray(ticketResponse.items) ? ticketResponse.items : []
  const ticketByClientId = new Map(ticketItems.map((item) => [text(item.clientId), item]))
  return attachments.map((file) => {
    if (file.status === 'UPLOADED' && ticketIsValid(file, nowMs)) {
      return file
    }
    const ticket = ticketByClientId.get(file.clientId)
    const expiresAt = text(ticket && ticket.expiresAt)
    const expiresAtMs = parseFeedbackTicketExpiryMs(expiresAt)
    if (!ticket
      || !positiveId(ticket.taskId)
      || !text(ticket.uploadUrl)
      || !Number.isFinite(expiresAtMs)
      || expiresAtMs <= nowMs
      || !ticket.formData
      || typeof ticket.formData !== 'object'
      || Array.isArray(ticket.formData)) {
      throw new Error('反馈附件上传票据无效')
    }
    return Object.assign({}, file, {
      taskId: positiveId(ticket.taskId),
      expiresAt,
      status: 'TICKETED',
      uploadTicket: {
        uploadUrl: text(ticket.uploadUrl),
        formData: Object.assign({}, ticket.formData)
      }
    })
  })
}

function getRuntimeWx(wxApi) {
  const runtimeWx = wxApi || (typeof wx !== 'undefined' ? wx : null)
  if (!runtimeWx || typeof runtimeWx.uploadFile !== 'function') {
    throw new Error('wx 上传环境不可用')
  }
  return runtimeWx
}

// COS 禁止覆盖会让同一票据的幂等重传返回 409，仅精确的 FileAlreadyExists 可视为成功。
function uploadFeedbackFile(file, options = {}) {
  const runtimeWx = getRuntimeWx(options.wxApi)
  const ticket = file.uploadTicket || {}
  return new Promise((resolve, reject) => {
    const uploadTask = runtimeWx.uploadFile({
      url: ticket.uploadUrl,
      filePath: file.tempFilePath,
      name: 'file',
      formData: ticket.formData || {},
      timeout: options.timeout || COS_UPLOAD_TIMEOUT_MS,
      success(response) {
        const statusCode = Number(response && response.statusCode)
        const fileAlreadyExists = statusCode === COS_FILE_ALREADY_EXISTS_STATUS
          && COS_FILE_ALREADY_EXISTS_PATTERN.test(String(response && response.data || ''))
        if ((statusCode >= 200 && statusCode < 300) || fileAlreadyExists) {
          resolve()
          return
        }
        reject(new Error(`COS 上传失败(${statusCode || 0})`))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : 'COS 上传失败'))
      }
    })
    if (uploadTask && typeof uploadTask.onProgressUpdate === 'function' && typeof options.onProgress === 'function') {
      uploadTask.onProgressUpdate((progress) => options.onProgress(file.clientId, progress))
    }
  })
}

async function runUploadPool(indexes, concurrency, uploadOne) {
  let nextIndex = 0
  const workerCount = Math.min(concurrency, indexes.length)
  const workers = Array.from({ length: workerCount }, async () => {
    while (nextIndex < indexes.length) {
      const itemIndex = indexes[nextIndex]
      nextIndex += 1
      await uploadOne(itemIndex)
    }
  })
  await Promise.all(workers)
}

// 图片最多并发两个，视频串行上传，避免小程序上传通道和用户网络被大文件占满。
async function uploadFeedbackAttachments(attachments, options = {}) {
  const nextAttachments = attachments.map((file) => Object.assign({}, file))
  const imageIndexes = []
  const videoIndexes = []
  nextAttachments.forEach((file, index) => {
    if (file.status === 'UPLOADED') {
      return
    }
    if (file.mediaType === 'VIDEO') {
      videoIndexes.push(index)
    } else {
      imageIndexes.push(index)
    }
  })
  const errors = []
  const uploadOne = async (index) => {
    const file = nextAttachments[index]
    try {
      await uploadFeedbackFile(file, options)
      nextAttachments[index] = Object.assign({}, file, {
        status: 'UPLOADED',
        uploadTicket: undefined,
        errorMessage: ''
      })
    } catch (error) {
      errors.push(error)
      nextAttachments[index] = Object.assign({}, file, {
        status: 'FAILED',
        uploadTicket: undefined,
        errorMessage: error && error.message ? error.message : 'COS 上传失败'
      })
    }
  }

  await runUploadPool(imageIndexes, IMAGE_UPLOAD_CONCURRENCY, uploadOne)
  for (const index of videoIndexes) {
    await uploadOne(index)
  }
  return { attachments: nextAttachments, errors }
}

function draftError(message, draft, cause) {
  const error = new Error(message)
  error.draft = draft
  if (cause) {
    error.cause = cause
  }
  return error
}

// 附件只在提交时按“签票、直传 COS、创建或追加轮次”的顺序处理，失败时保留草稿。
async function submitFeedbackDraft(options = {}) {
  const sourceDraft = options.draft || {}
  const sourceAttachments = Array.isArray(sourceDraft.attachments) ? sourceDraft.attachments : []
  const validation = validateFeedbackDraft(sourceDraft.description, sourceAttachments)
  if (!validation.valid) {
    throw draftError(validation.message, sourceDraft)
  }
  const idempotencyKey = text(sourceDraft.idempotencyKey)
  if (!isPrintableAsciiIdentifier(idempotencyKey)) {
    throw draftError('反馈幂等键必须为 1 至 64 个 ASCII 字符', sourceDraft)
  }
  const nowMs = options.nowMs === undefined ? Date.now() : Number(options.nowMs)
  const identifierOptions = {
    idFactory: options.idFactory,
    nowMs,
    randomFn: options.randomFn
  }
  let draft = {
    idempotencyKey,
    description: validation.description,
    attachments: prepareAttachmentsForSubmission(sourceAttachments, nowMs, identifierOptions)
  }
  const attachmentsNeedingTickets = draft.attachments.filter((file) => (
    !(file.status === 'UPLOADED' && ticketIsValid(file, nowMs))
  ))

  try {
    if (attachmentsNeedingTickets.length > 0) {
      const ticketPayload = buildFeedbackUploadTicketPayload(attachmentsNeedingTickets)
      const ticketResponse = await createFeedbackUploadTickets(options.requestFn || request, ticketPayload)
      draft.attachments = applyUploadTickets(draft.attachments, ticketResponse, nowMs)
    }
  } catch (error) {
    throw draftError(error && error.message ? error.message : '反馈附件票据申请失败', draft, error)
  }

  const uploadResult = await uploadFeedbackAttachments(draft.attachments, options)
  draft.attachments = uploadResult.attachments
  if (uploadResult.errors.length > 0) {
    const firstError = uploadResult.errors[0]
    throw draftError(`附件上传失败：${firstError.message}`, draft, firstError)
  }

  const payload = buildFeedbackSubmitPayload(draft)
  try {
    const response = options.feedbackId
      ? await appendFeedbackRound(options.requestFn || request, options.feedbackId, payload)
      : await createFeedback(options.requestFn || request, payload)
    return {
      detail: normalizeFeedbackDetail(response),
      draft
    }
  } catch (error) {
    if (error && typeof error === 'object') {
      error.draft = draft
    }
    throw error
  }
}

module.exports = {
  MAX_ATTACHMENT_COUNT,
  addChosenFeedbackMedia,
  appendFeedbackRound,
  buildFeedbackSubmitPayload,
  buildFeedbackUploadTicketPayload,
  createFeedback,
  createFeedbackDraft,
  createFeedbackUploadTickets,
  feedbackStatusMeta,
  fetchFeedbackCreationState,
  fetchFeedbackDetail,
  fetchFeedbackList,
  mergeFeedbackPage,
  normalizeFeedbackDetail,
  normalizeFeedbackPage,
  submitFeedbackDraft,
  validateFeedbackDescription,
  validateFeedbackDraft
}
