/** 两分包各自持有参考附件工具；只处理维护清单，不产生作品或公开资源。 */
const REFERENCE_API = '/api/mine/portfolio-ai/reference-attachments'
const LIMITS = Object.freeze({ maxCount: 6, maxVideos: 1, imageMaxBytes: 4000000, imageMaxEdge: 4096,
  videoMaxBytes: 100000000, videoMaxDurationSeconds: 300, sourceVideoMaxBytes: 500000000 })
const NOTE_MAX = 200
const OPERATION_TIMEOUT_MS = 180000
/** 附件确认和重试沿用现有超时预算，不改变其它 API 请求超时。 */
const CONFIRM_TIMEOUT_MS = 150000
const MEDIA_MIME_TYPES = Object.freeze({ mp4: 'video/mp4', png: 'image/png', jpg: 'image/jpeg' })
const VIDEO_ENCODING_KEY = '__wefolioReferenceVideoEncoding'
/** 保留微信可能返回的编码名称与 MP4 编码标识，避免合规视频重复编码。 */
const SUPPORTED_VIDEO_CODEC = /^(h\.?264|avc1|h\.?265|hevc|hvc1|hev1)$/i
const VIDEO_FORMAT_MESSAGE = '请转换为 MP4（H.264 或 H.265/HEVC）视频后重新选择'
const CANCELLED = 'REFERENCE_CANCELLED'
const STATES = Object.freeze({ QUEUED: '等待处理', PREPARING: '正在检查', COMPRESSING: '正在压缩', UPLOADING: '正在上传', CONFIRMING: '正在确认', READY: '已上传', FAILED: '处理失败' })
const string = value => typeof value === 'string' ? value : ''
const integer = (value, fallback) => Number.isSafeInteger(value) && value > 0 ? value : fallback
const numberOf = item => Number(string(item.referenceNo).replace(/^A/, '')) || 0

function referenceLimits(capabilities) {
  const value = capabilities && capabilities.referenceAttachments || {}
  const result = { enabled: value.enabled === true }
  Object.keys(LIMITS).forEach(key => { result[key] = Math.min(LIMITS[key], Number.isSafeInteger(value[key]) && value[key] >= 0 ? value[key] : LIMITS[key]) })
  return result
}

/** 说明面板和按钮共用能力、附件状态；替换时仅把旧条目从选择额度中排除。 */
function referenceUiState(items = [], capabilities, replacingKey = '') {
  const limits = referenceLimits(capabilities)
  const attachments = normalizeReferenceItems(items)
  const existing = attachments.filter(item => item.localKey !== replacingKey)
  const pending = attachments.filter(item => item.status !== 'READY' || item.available === false)
  const error = validateReferenceInput(attachments, capabilities)
  const videoSlots = Math.min(limits.maxCount, limits.maxVideos)
  const canAdd = limits.enabled && existing.length < limits.maxCount
  const failed = pending.some(item => item.status === 'FAILED' || item.available === false)
  return { maxCount: limits.maxCount, maxVideos: limits.maxVideos, imageMaxMB: limits.imageMaxBytes / 1000000,
    videoMaxMB: limits.videoMaxBytes / 1000000, videoMaxMinutes: limits.videoMaxDurationSeconds / 60,
    sourceVideoMaxMB: limits.sourceVideoMaxBytes / 1000000,
    totalMaxMB: (videoSlots * limits.videoMaxBytes + (limits.maxCount - videoSlots) * limits.imageMaxBytes) / 1000000,
    videoCount: attachments.filter(item => item.mediaType === 'VIDEO').length,
    totalMB: Number((attachments.filter(item => ['UPLOADING', 'CONFIRMING', 'READY'].includes(item.status))
      .reduce((sum, item) => sum + item.sizeBytes, 0) / 1000000).toFixed(1)),
    pendingCount: pending.length, canAdd, canChooseVideo: canAdd && existing.filter(item => item.mediaType === 'VIDEO').length < limits.maxVideos,
    blocked: Boolean(error), warning: pending.length ? failed
      ? '有参考附件未准备好。请处理后再生成；也可移除未就绪附件，继续使用文字和已就绪附件。'
      : '参考附件正在准备中。完成后即可生成；也可移除未就绪附件继续。' : error }
}

/** 回执、预览和进度不是业务内容，不参与修订、脏标记及幂等。 */
function referenceBusiness(items = []) {
  return (Array.isArray(items) ? items : []).map(item => ({ attachmentId: string(item.attachmentId) || string(item.localKey),
    referenceNo: string(item.referenceNo), note: string(item.note) }))
}
function referenceNextNo(items = [], next = 1) {
  return Math.max(integer(next, 1), 1 + Math.max(0, ...(Array.isArray(items) ? items : []).map(numberOf)))
}
function hasReferenceState(state = {}) { return (state.aiReferenceAttachments || []).length > 0 || referenceNextNo([], state.aiReferenceNextNo) > 1 }
function referenceBasis(state = {}) {
  return [referenceBusiness(state.aiReferenceAttachments), referenceNextNo(state.aiReferenceAttachments, state.aiReferenceNextNo)]
}
function normalizeReferenceItems(items = []) {
  return (Array.isArray(items) ? items : []).map(item => ({ attachmentId: string(item.attachmentId), referenceNo: string(item.referenceNo),
    localKey: string(item.localKey) || string(item.attachmentId), note: string(item.note), attachmentToken: string(item.attachmentToken),
    referenceSaveUntil: string(item.referenceSaveUntil), expiresAt: Number(item.expiresAt) || 0, saved: item.saved === true,
    originalFileName: string(item.originalFileName), mediaType: item.mediaType === 'VIDEO' ? 'VIDEO' : 'IMAGE',
    sizeBytes: Number(item.sizeBytes) || 0, durationMs: Number(item.durationMs) || 0,
    localPath: string(item.localPath), thumbnailPath: string(item.thumbnailPath),
    available: item.available !== false, status: item.status || (item.attachmentId ? 'READY' : 'FAILED'), progress: Math.max(0, Math.min(100, Number(item.progress) || 0)),
    errorMessage: string(item.errorMessage) || (item.available === false ? string(item.unavailableReason) || '参考文件已不可用，请移除或重新选择' : '') })).map(item => ({ ...item, statusText: STATES[item.status] || STATES.FAILED,
      saveDeadlineText: referenceSaveDeadlineText(item),
      detailsText: `${(item.sizeBytes / 1000000).toFixed(1)} MB${item.mediaType === 'VIDEO' ? ` · ${Math.ceil(item.durationMs / 1000)}秒` : ''}` }))
}
/** 终态任务的保存窗口只提示尚未落入草稿的附件，已存附件由草稿继续保护。 */
function referenceSaveExpired(item) {
  // 服务端 LocalDateTime 按项目北京时间下发；带时区的时间保留其明确偏移。
  const value = string(item.referenceSaveUntil)
  const taskDeadline = value ? Date.parse(/(?:Z|[+-]\d{2}:?\d{2})$/.test(value) ? value : `${value}+08:00`) : 0
  const deadline = Math.max(Number(item.expiresAt) || 0, Number.isFinite(taskDeadline) ? taskDeadline : 0)
  return !item.saved && deadline > 0 && deadline <= Date.now()
}
function referenceSaveDeadlineText(item) {
  if (referenceSaveExpired(item)) return '附件保存期限已过，可先保存正文，管理附件后再保存附件'
  const deadline = string(item.referenceSaveUntil).match(/^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/)
  return !item.saved && deadline ? `请在 ${deadline[1]} ${deadline[2]} 前保存草稿，以保留此参考附件` : ''
}
/** 未就绪时整体省略维护清单，服务端保留原附件；禁止把部分清单当成用户删除。 */
function referenceSaveDeferred(state = {}) {
  return (state.aiReferenceAttachments || []).some(item => item.available === false || referenceSaveExpired(item)
    || item.status && item.status !== 'READY' || !/^[a-f0-9]{32}$/.test(string(item.attachmentId)))
}
function referencePayload(items = []) {
  return (Array.isArray(items) ? items : []).map(item => {
    if (item.available === false) throw new Error(item.errorMessage || '参考文件已不可用，请移除或重新选择')
    if (item.status && item.status !== 'READY' || !/^[a-f0-9]{32}$/.test(string(item.attachmentId))) throw new Error('请等待参考附件处理完成，或移除未就绪的附件')
    if (!/^A[1-9]\d*$/.test(string(item.referenceNo)) || Array.from(string(item.note)).length > NOTE_MAX) throw new Error('参考附件编号或说明无效')
    return { attachmentId: item.attachmentId, referenceNo: item.referenceNo, note: string(item.note), attachmentToken: string(item.attachmentToken) }
  })
}
function referenceSaveFields(state = {}) {
  if (!hasReferenceState(state) || referenceSaveDeferred(state)) return {}
  return { aiReferenceAttachments: referencePayload(state.aiReferenceAttachments), aiReferenceNextNo: referenceNextNo(state.aiReferenceAttachments, state.aiReferenceNextNo) }
}
function validateReferenceInput(items, caps) {
  const limits = referenceLimits(caps)
  if (!items.length) return ''
  if (!limits.enabled) return '参考附件生成暂未开放，请移除附件后继续'
  if (items.length > limits.maxCount || items.filter(item => item.mediaType === 'VIDEO').length > limits.maxVideos) return '参考附件数量超过当前上限'
  try { referencePayload(items) } catch (error) { return error.message }
  return ''
}
function cancelled() { const error = new Error('已取消附件处理'); error.code = CANCELLED; return error }
function active(operation) { if (operation.cancelled) throw cancelled() }
function stat(wxApi, path) {
  const value = wxApi.getFileSystemManager().statSync(path)
  const size = Number(value && value.size)
  if (!Number.isSafeInteger(size) || size <= 0) throw new Error('无法读取附件大小，请重新选择')
  return size
}
function removeTemporaryFile(wxApi, path, source) {
  if (!path || path === source) return
  try { wxApi.getFileSystemManager().unlinkSync(path) } catch (error) {}
}
function call(wxApi, method, options, operation, timeout = OPERATION_TIMEOUT_MS) {
  active(operation)
  return new Promise((resolve, reject) => {
    if (typeof wxApi[method] !== 'function') { reject(new Error('当前微信版本不支持此文件处理，请更新微信或更换文件')); return }
    let settled = false
    const finish = (error, value) => { if (settled) return; settled = true; clearTimeout(timer); error ? reject(error) : resolve(value) }
    const timer = setTimeout(() => finish(new Error('附件处理超时，请重试或更换文件')), timeout)
    try { wxApi[method]({ ...options, success: result => {
      if ((settled || operation.cancelled) && method === 'compressImage') removeTemporaryFile(wxApi, result.tempFilePath, options.src)
      try { active(operation); finish(null, result) } catch (error) { finish(error) }
    }, fail: () => finish(new Error('附件处理失败，请重试或更换文件')) }) }
    catch (error) { finish(error) }
  })
}

/** 原生编码没有可靠取消接口；跨两个分包共用 wx 上的占用标记，直到真实回调才释放。 */
async function encodeVideo(wxApi, options, operation) {
  active(operation)
  const pending = wxApi[VIDEO_ENCODING_KEY]
  if (pending) {
    let timer
    try { await Promise.race([pending, new Promise((resolve, reject) => { timer = setTimeout(() => reject(new Error('上一个视频仍在压缩，请稍后重试')), OPERATION_TIMEOUT_MS) })]) }
    finally { clearTimeout(timer) }
    active(operation)
  }
  if (wxApi[VIDEO_ENCODING_KEY]) return encodeVideo(wxApi, options, operation)
  if (typeof wxApi.compressVideo !== 'function') throw new Error('当前微信版本不支持视频压缩，请更换合规视频')
  let release
  const held = new Promise(resolve => { release = resolve })
  wxApi[VIDEO_ENCODING_KEY] = held
  return new Promise((resolve, reject) => {
    let finished = false
    const timer = setTimeout(() => { finished = true; reject(new Error('视频压缩超时，请稍后重试')) }, OPERATION_TIMEOUT_MS)
    const finish = (error, value) => {
      if (wxApi[VIDEO_ENCODING_KEY] === held) delete wxApi[VIDEO_ENCODING_KEY]
      release(); clearTimeout(timer)
      if (finished || operation.cancelled) removeTemporaryFile(wxApi, value && (value.tempFilePath || value.filePath), options.src)
      if (finished) return
      finished = true
      try { active(operation); error ? reject(error) : resolve(value) } catch (failure) { reject(failure) }
    }
    try { wxApi.compressVideo({ ...options, success: value => finish(null, value), fail: () => finish(new Error('视频压缩失败，请更换文件')) }) }
    catch (error) { finish(error) }
  })
}
function videoInfo(info, limits) {
  const duration = Number(info.duration)
  if (!Number.isFinite(duration) || duration <= 0 || !(Number(info.width) > 0) || !(Number(info.height) > 0)) throw new Error('无法确认视频信息，请更换文件')
  if (duration > limits.videoMaxDurationSeconds) throw new Error(`参考视频不能超过${limits.videoMaxDurationSeconds}秒，请截短后重选`)
  return duration
}
function imageType(info) { return string(info.type).toLowerCase() }
async function prepare(file, limits, wxApi, operation, update) {
  let path = file.tempFilePath
  let size = stat(wxApi, path)
  const mediaType = file.fileType === 'video' || file.mediaType === 'VIDEO' ? 'VIDEO' : 'IMAGE'
  if (mediaType === 'VIDEO') {
    if (size > limits.sourceVideoMaxBytes) throw new Error('视频源文件过大，请选择不超过500 MB的文件')
    let info = await call(wxApi, 'getVideoInfo', { src: path }, operation)
    const duration = videoInfo(info, limits)
    // 选择器缺少编码信息时不阻断上传；明确非 MP4 或不支持的编码先由微信转换。
    const needsConvert = (info.type ? !/mp4/i.test(info.type) : !/\.mp4$/i.test(path.split('?')[0]))
      || info.videoCodec && !SUPPORTED_VIDEO_CODEC.test(info.videoCodec)
    if (size > limits.videoMaxBytes || needsConvert) {
      const target = Math.floor(limits.videoMaxBytes * 0.9)
      let bitrate = Math.floor((target * 8 / duration - 128000) / 1000)
      if (bitrate < 300) throw new Error('视频在大小上限内无法保留清晰画面，请截短或更换')
      for (let attempt = 0; attempt < 2; attempt++) {
        active(operation); update({ status: 'COMPRESSING' })
        const result = await encodeVideo(wxApi, { src: file.tempFilePath, bitrate, fps: Math.min(Number(info.fps) || 24, 30), resolution: 1 }, operation)
        path = result.tempFilePath || result.filePath
        if (!path) throw new Error('视频压缩未返回文件，请重新选择')
        if (path !== file.tempFilePath) operation.temporaryFiles.add(path)
        size = stat(wxApi, path)
        info = await call(wxApi, 'getVideoInfo', { src: path }, operation)
        videoInfo(info, limits)
        if (size <= limits.videoMaxBytes) break
        bitrate = Math.floor(bitrate * target / size * 0.95)
        if (bitrate < 300) break
      }
    }
    if (size > limits.videoMaxBytes) throw new Error('压缩后视频仍超过大小上限，请更换或截短')
    if (info.type && !/mp4/i.test(info.type) || info.videoCodec && !SUPPORTED_VIDEO_CODEC.test(info.videoCodec)) throw new Error(VIDEO_FORMAT_MESSAGE)
    return { path, sizeBytes: size, mediaType, mimeType: MEDIA_MIME_TYPES.mp4, codec: string(info.videoCodec),
      width: Number(info.width), height: Number(info.height), durationMs: Math.ceil(Number(info.duration) * 1000), extension: 'mp4' }
  }
  let info = await call(wxApi, 'getImageInfo', { src: path }, operation)
  const type = imageType(info)
  if (!['jpg', 'jpeg', 'png', 'webp'].includes(type)) throw new Error('请转换为静态 JPEG 或 PNG 图片后重新选择')
  if (type === 'webp') throw new Error('请将 WebP 转换为 JPEG 或 PNG 后重新选择')
  if (!(Number(info.width) > 0) || !(Number(info.height) > 0)) throw new Error('无法读取图片尺寸，请重新选择')
  if (size > limits.imageMaxBytes || Math.max(info.width, info.height) > limits.imageMaxEdge) {
    const scale = Math.min(1, limits.imageMaxEdge / Math.max(info.width, info.height))
    for (let attempt = 0; attempt < 2; attempt++) {
      update({ status: 'COMPRESSING' })
      const result = await call(wxApi, 'compressImage', { src: file.tempFilePath, quality: attempt ? 65 : 85,
        compressedWidth: Math.max(1, Math.floor(info.width * scale)), compressedHeight: Math.max(1, Math.floor(info.height * scale)) }, operation)
      path = result.tempFilePath
      if (!path) throw new Error('图片处理未返回文件，请重新选择')
      if (path !== file.tempFilePath) operation.temporaryFiles.add(path)
      size = stat(wxApi, path)
      const output = await call(wxApi, 'getImageInfo', { src: path }, operation)
      if (!['jpg', 'jpeg', 'png'].includes(imageType(output))) throw new Error('图片处理格式不支持，请重新选择')
      if (size <= limits.imageMaxBytes && Math.max(output.width, output.height) <= limits.imageMaxEdge) { info = output; break }
      if (attempt === 1) throw new Error('处理后图片仍超限，请更换文件')
    }
  }
  const extension = imageType(info) === 'png' ? 'png' : 'jpg'
  return { path, sizeBytes: size, mediaType, mimeType: MEDIA_MIME_TYPES[extension], codec: '',
    width: Number(info.width), height: Number(info.height), durationMs: null, extension }
}
/** 首次确认与重试共用最终上传文件的媒体信息，不能退回选择器或压缩前的数据。 */
function confirmationData(operation) {
  const { mimeType, codec, sizeBytes, width, height, durationMs } = operation.prepared
  return { uploadToken: operation.ticket.uploadToken, mediaInfo: { mimeType, codec, sizeBytes, width, height, durationMs } }
}
function upload(wxApi, ticket, path, operation, update) {
  active(operation)
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { if (operation.uploadTask) operation.uploadTask.abort(); reject(new Error('附件上传超时，请重试')) }, OPERATION_TIMEOUT_MS)
    const finish = (error, value) => { clearTimeout(timer); operation.uploadTask = null; error ? reject(error) : resolve(value) }
    try {
      operation.uploadTask = wxApi.uploadFile({ url: ticket.uploadUrl || ticket.url, filePath: path, name: 'file', formData: ticket.formData, timeout: OPERATION_TIMEOUT_MS,
        success: result => {
          try { active(operation); if (![200, 201, 204].includes(Number(result.statusCode))) throw new Error('附件上传失败，请重试'); finish(null, result) }
          catch (error) { finish(error) }
        }, fail: () => finish(operation.cancelled ? cancelled() : new Error('附件上传失败，请重试')) })
      if (operation.uploadTask && operation.uploadTask.onProgressUpdate) operation.uploadTask.onProgressUpdate(event => {
        if (!operation.cancelled) update({ progress: Math.max(0, Math.min(100, Number(event.progress) || 0)) })
      })
    } catch (error) { finish(error) }
  })
}
/** 两个处理槽同时约束上传数量；取消只废弃当前项目，不影响其它附件。 */
function createReferenceUploader({ request, wxApi, owner, limits, onChange, onError }) {
  const queue = []; const operations = new Map(); let running = 0; let disposed = false
  const update = (operation, patch) => { if (!disposed && !operation.cancelled) onChange(operation.key, patch) }
  async function run(operation) {
    try {
      active(operation)
      update(operation, { status: 'PREPARING', errorMessage: '' })
      if (!operation.prepared) operation.prepared = await prepare(operation.file, limits, wxApi, operation, patch => update(operation, patch))
      active(operation)
      if (operation.uploadAttempted && !operation.uploaded) {
        try {
          const confirmed = await request({ url: `${REFERENCE_API}/complete`, method: 'POST', timeout: CONFIRM_TIMEOUT_MS, data: confirmationData(operation) })
          active(operation)
          if (!confirmed || !/^[a-f0-9]{32}$/.test(string(confirmed.attachmentId)) || !confirmed.attachmentToken) throw new Error('附件确认结果不完整')
          update(operation, { ...confirmed, status: 'READY', progress: 100, errorMessage: '' })
          return
        } catch (error) {
          active(operation)
          if (error.authRequired) throw error
          // 无法确认原对象时重新申请 UUID；旧对象由服务端暂存清理回收，绝不覆盖。
          operation.ticket = null
          operation.uploadAttempted = false
        }
      }
      if (!operation.ticket) {
        const prepared = operation.prepared
        const originalFileName = string(operation.file.name) || string(operation.file.tempFilePath).split('/').pop() || `reference.${prepared.extension}`
        const response = await request({ url: `${REFERENCE_API}/upload-tickets`, method: 'POST', data: { ...owner,
          originalFileName, mimeType: prepared.mimeType, sizeBytes: prepared.sizeBytes } })
        operation.ticket = response.ticket ? { ...response.ticket, uploadToken: response.uploadToken } : response
      }
      active(operation)
      if (!operation.uploaded) {
        update(operation, { status: 'UPLOADING', progress: 0, sizeBytes: operation.prepared.sizeBytes, durationMs: operation.prepared.durationMs })
        operation.uploadAttempted = true
        await upload(wxApi, operation.ticket, operation.prepared.path, operation, patch => update(operation, patch))
        operation.uploaded = true
      }
      active(operation); update(operation, { status: 'CONFIRMING' })
      const result = await request({ url: `${REFERENCE_API}/complete`, method: 'POST', timeout: CONFIRM_TIMEOUT_MS, data: confirmationData(operation) })
      active(operation)
      if (!result || !/^[a-f0-9]{32}$/.test(string(result.attachmentId)) || !result.attachmentToken) throw new Error('附件确认结果不完整，请重试确认')
      update(operation, { ...result, status: 'READY', progress: 100, errorMessage: '' })
    } catch (error) {
      if (!operation.cancelled && !disposed) { update(operation, { status: 'FAILED', errorMessage: error.message || '附件处理失败，请重试' }); if (onError) onError(error) }
    } finally { operation.running = false; running--; pump() }
  }
  function pump() {
    while (!disposed && running < 2 && queue.length) {
      const operation = queue.shift()
      if (operation.cancelled) continue
      running++; operation.running = true; run(operation)
    }
  }
  function cancel(key) {
    const operation = operations.get(key)
    if (!operation) return
    operation.cancelled = true
    if (operation.uploadTask && operation.uploadTask.abort) operation.uploadTask.abort()
    // 只清理本工具生成的临时成品；相册源文件永远不删除。
    for (const path of operation.temporaryFiles) {
      removeTemporaryFile(wxApi, path, operation.file.tempFilePath)
    }
  }
  return {
    add(key, file) { const operation = { key, file, cancelled: false, temporaryFiles: new Set() }; operations.set(key, operation); queue.push(operation); pump() },
    retry(key) { const operation = operations.get(key); if (!operation || operation.running || operation.cancelled || queue.includes(operation)) return false; queue.push(operation); pump(); return true },
    cancel,
    dispose() { disposed = true; for (const key of operations.keys()) cancel(key) }
  }
}
module.exports = { REFERENCE_API, LIMITS, NOTE_MAX, CANCELLED, referenceLimits, referenceUiState, normalizeReferenceItems, referenceBusiness,
  referenceNextNo, referenceBasis, hasReferenceState, referencePayload, referenceSaveFields, referenceSaveDeferred, validateReferenceInput, createReferenceUploader }
