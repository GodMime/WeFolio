const { request } = require('../../../utils/request')
const { handleMaintainerAuthRequired, hasLocalToken } = require('../../../utils/session')
const {
  applyUnifiedWorkTags,
  buildUnifiedWorkTagItems,
  buildUnifiedWorkTagNames,
  buildWorkTagOptionListHeight,
  buildWorkTagPickerOptions,
  normalizeWorkTags
} = require('../utils/works')
const {
  applyUploadCompleteResults,
  applyAnimationSingleFrameFallbacks,
  buildUploadCompleteFailureMessage,
  buildUploadCompletePayload,
  buildCoverUploadTicketPayload,
  buildUploadTicketPayload,
  buildUploadProgressSummary,
  chooseAudioFiles,
  createChooseMediaOptions,
  classifyChosenMediaFiles,
  enrichVideoFileMetadata,
  normalizeChosenMediaFiles,
  AUDIO_MAX_BYTES,
  readAudioDuration,
  prepareCoverUploadFiles,
  measureChosenMediaFiles,
  prepareWorkMainFiles,
  releasePreparedWorkFiles,
  runWorkUploadQueue,
  uploadToCos,
  validateChosenMediaFiles
} = require('../utils/work-upload')
const { getCanvasNode } = require('../utils/work-thumbnail-crop')
const { buildMediaErrorMessage, createCompressionSession, COMPRESSION_CANCELLED, COMPRESSION_TIMEOUT, METADATA_TIMEOUT_MS } = require('../utils/work-compression-runtime')
const { calculateFileSha256: calculateLocalFileSha256 } = require('../utils/sha256')

const WORKS_PAGE_URL = '/pages/works/works'
const WORK_TAGS_API_URL = '/api/mine/works/tags'
const TITLE_MAX_LENGTH = 30
const DESCRIPTION_MAX_LENGTH = 1000
const SWIPE_REVEAL_THRESHOLD = -32
const SWIPE_CLOSE_THRESHOLD = 24
const SWIPE_VERTICAL_TOLERANCE = 48
const COVER_BATCH_PREFIX = 'cover'
const WORK_COMPRESSION_CANVAS_ID = 'workCompressionCanvas'
const PREPARATION_CANCELLED_MESSAGE = '已取消处理'
const PREPARATION_READING_TEXT = '正在读取文件'
const PREPARATION_READING_HINT = '正在准备作品信息'
const PREPARATION_READING_COUNT_TEXT = '准备中'
const CANCEL_PREPARATION_TEXT = '取消处理'
const COMPRESSION_CANVAS_BUSY = 'MEDIA_COMPRESSION_CANVAS_BUSY'
const COMPRESSION_CANVAS_BUSY_MESSAGE = '画布处理尚未结束，请返回后重新进入'
const COMPRESSION_CANVAS_FAILED_MESSAGE = '图片处理失败，请选择较小文件后重试'
const COMPRESSION_MEDIA_NAMES = { VIDEO: '视频', IMAGE: '图片' }
const MEDIA_PREPARATION_FAILED_LOG = '[work-add] media preparation failed'
const MEDIA_PREPARATION_FAILED_TITLE = '作品处理失败'

function preparationCancelled() {
  return Object.assign(new Error(PREPARATION_CANCELLED_MESSAGE), { code: COMPRESSION_CANCELLED })
}

function buildCompressionProgressDisplay({ mediaType, index, total, stage }) {
  const compressing = stage === 'compressing'
  const choosingTitle = compressing
    ? `正在压缩${COMPRESSION_MEDIA_NAMES[mediaType] || COMPRESSION_MEDIA_NAMES.IMAGE}`
    : PREPARATION_READING_TEXT
  const choosingCountText = compressing ? `第 ${index}/${total} 个` : PREPARATION_READING_COUNT_TEXT
  return {
    choosingTitle,
    choosingCountText,
    choosingHint: compressing
      ? `${COMPRESSION_MEDIA_NAMES[mediaType] || COMPRESSION_MEDIA_NAMES.IMAGE}压缩可能需要一点时间`
      : PREPARATION_READING_HINT,
    choosingText: compressing ? `${choosingTitle}，${choosingCountText}` : choosingTitle
  }
}

function formatFrameTime(milliseconds) {
  const totalSeconds = Math.max(0, Math.round(Number(milliseconds || 0) / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

function buildEditForm(file, index) {
  const durationMs = Math.max(0, Number(file.durationMs || 0))
  return {
    index,
    id: file.id,
    fileName: file.fileName,
    mediaType: file.mediaType,
    isVideo: file.mediaType === 'VIDEO',
    isAnimation: file.mediaType === 'ANIMATION',
    isAudio: file.mediaType === 'AUDIO',
    audioCoverUrl: file.audioCoverUrl || '',
    tempFilePath: file.tempFilePath,
    coverPath: file.coverPath || '',
    title: file.title || '',
    description: file.description || '',
    durationMs,
    durationText: formatFrameTime(durationMs),
    coverFrameTimeMs: 0
  }
}

function getCoverUploadPath(file) {
  if (!file) {
    return ''
  }
  return file.customCoverPath || file.coverPath || ''
}

Page({
  uploadTasks: {},

  data: {
    files: [],
    saving: false,
    choosing: false,
    choosingText: '',
    choosingTitle: '',
    choosingCountText: '',
    choosingHint: '',
    preparationCancelled: false,
    canCancelPreparation: false,
    cancelPreparationText: CANCEL_PREPARATION_TEXT,
    compressionCanvasWidth: 1,
    compressionCanvasHeight: 1,
    errorMessage: '',
    unifiedTags: [],
    tagPickerVisible: false,
    tagLoading: false,
    tagErrorText: '',
    tagPickerTags: [],
    tagOptionListHeight: 0,
    selectedUnifiedTagCount: 0,
    uploadOverallProgress: 0,
    uploadOverallText: '保存作品',
    revealedFileId: '',
    fileTouchStart: null,
    editSheetVisible: false,
    editForm: null,
    editErrorText: ''
  },

  onLoad() {
    this.disposed = false
    this.uploadTasks = {}
    this.initializeCompressionState()
    if (!hasLocalToken()) {
      this.redirectToLogin()
    }
  },

  onUnload() {
    this.disposed = true
    this.initializeCompressionState()
    this.compressionGeneration += 1
    if (this.compressionSession) this.compressionSession.cancel()
    this.compressionSession = null
    if (this.cancelAudioRead) this.cancelAudioRead()
    const tasks = new Set(Object.values(this.uploadTasks).concat([...this.activeUploads].map(entry => entry.task)))
    tasks.forEach((task) => {
      if (task && task.abort) {
        task.abort()
      }
    })
    this.uploadTasks = {}
    this.releaseUnusedPreparedFiles()
  },

  redirectToLogin() {
    wx.redirectTo({
      url: '/pages/login/login'
    })
  },

  // 兼容页面生命周期与已有测试直接调用事件入口，资源表始终按页面实例保存。
  initializeCompressionState() {
    if (this.compressionGeneration === undefined) this.compressionGeneration = 0
    if (this.compressionSession === undefined) this.compressionSession = null
    if (!this.compressionOwnedPaths) this.compressionOwnedPaths = {}
    if (!this.activeLocalReads) this.activeLocalReads = new Set()
    if (!this.activeUploads) this.activeUploads = new Set()
    if (!this.pendingPreparedReleases) this.pendingPreparedReleases = new Set()
    if (this.compressionCanvasLease === undefined) this.compressionCanvasLease = null
  },

  isCurrentPreparation(generation, session) {
    return !this.disposed && this.compressionGeneration === generation && (!session || this.compressionSession === session)
  },

  async handleChooseMedia(event = {}) {
    if (this.data.saving || this.data.choosing) return
    this.initializeCompressionState()
    if (this.disposed) return
    const remainingCount = 9 - this.data.files.length
    if (remainingCount <= 0) {
      wx.showToast({ title: '一次最多上传 9 个作品', icon: 'none' })
      return
    }
    const audio = event.currentTarget && event.currentTarget.dataset.mediaType === 'AUDIO'
    const generation = ++this.compressionGeneration
    let session
    let result
    let adopted = false
    this.setData({ choosing: true, ...buildCompressionProgressDisplay({}), preparationCancelled: false, canCancelPreparation: false,
      editSheetVisible: false, editForm: null, tagPickerVisible: false })
    try {
      const response = audio
        ? await chooseAudioFiles(remainingCount, { wxApi: wx })
        : await this.chooseMedia(createChooseMediaOptions(remainingCount))
      if (!this.isCurrentPreparation(generation)) return
      const rawFiles = audio ? (response.tempFiles || []).map((file) => Object.assign({}, file, { fileType: 'audio' })) : response.tempFiles || []
      const normalizedFiles = normalizeChosenMediaFiles(rawFiles)
      let mediaFiles = []
      if (audio) {
        for (const file of normalizedFiles) {
          if (!file.mimeType) throw new Error('音频仅支持 MP3、M4A、AAC、WAV')
          if (file.size <= 0 || file.size > AUDIO_MAX_BYTES) throw new Error('音频文件不能为空且不能超过 50MB')
          file.durationMs = await readAudioDuration(file.tempFilePath, {
            wxApi: wx,
            onCancelReady: (cancel) => { this.cancelAudioRead = cancel }
          })
          if (!this.isCurrentPreparation(generation)) return
          file.metaText = `默认标题 · 音频 ${formatFrameTime(file.durationMs)}`
        }
        const classifiedFiles = await classifyChosenMediaFiles(normalizedFiles, { wxApi: wx })
        if (!this.isCurrentPreparation(generation)) return
        mediaFiles = await enrichVideoFileMetadata(classifiedFiles, { wxApi: wx })
        if (!this.isCurrentPreparation(generation)) return
      } else {
        const protectedPaths = rawFiles.concat(normalizedFiles, this.data.files).flatMap(file =>
          [file.tempFilePath, file.path, file.thumbTempFilePath, file.coverPath, file.customCoverPath].filter(Boolean))
        session = createCompressionSession({ wxApi: wx, protectedPaths })
        this.compressionSession = session
        this.setData({ canCancelPreparation: true })
        session.assertActive()
        const measuredFiles = measureChosenMediaFiles(normalizedFiles, { wxApi: wx })
        const classifiedFiles = await classifyChosenMediaFiles(measuredFiles, { wxApi: wx, session })
        session.assertActive()
        result = await prepareWorkMainFiles(classifiedFiles, {
          wxApi: wx, session,
          getCanvas: request => this.getCompressionCanvas(request, session),
          onProgress: progress => {
            if (this.isCurrentPreparation(generation, session)) this.setData(buildCompressionProgressDisplay(progress))
          }
        })
        session.assertActive()
        for (const file of result.files) {
          const enriched = await session.call(({ success, fail }) => {
            enrichVideoFileMetadata([file], { wxApi: wx }).then(success, fail)
          }, {}, { timeoutMs: METADATA_TIMEOUT_MS })
          session.assertActive()
          mediaFiles.push(...enriched)
        }
      }
      const selectedFiles = applyUnifiedWorkTags(mediaFiles, this.data.unifiedTags)
      const nextFiles = this.data.files.concat(selectedFiles)
      const validation = validateChosenMediaFiles(nextFiles)
      if (!validation.valid) throw new Error(validation.message)
      if (session) session.assertActive()
      if (!this.isCurrentPreparation(generation, session)) return
      this.setData({ files: nextFiles, errorMessage: '', revealedFileId: '', preparationCancelled: false })
      if (result) Object.assign(this.compressionOwnedPaths, result.ownedPathsByClientId)
      adopted = true
    } catch (error) {
      if (!this.isCurrentPreparation(generation, session)) return
      if (error && error.code === COMPRESSION_CANCELLED) return
      // 音频读取沿用原有独立取消边界，不扩散到图片、视频压缩错误。
      if (audio && error && /cancel/i.test(error.errMsg || error.message || '')) return
      if (audio && error && error.errMsg) console.warn('选择音频作品失败', { errMsg: error.errMsg, errno: error.errno })
      const message = audio
        ? error && error.message ? error.message : '选择作品失败'
        : buildMediaErrorMessage(error)
      if (!audio) {
        this.setData({ errorMessage: message })
        // 只记录已脱敏的错误说明，不把本地文件路径或原生对象写入日志。
        console.warn(MEDIA_PREPARATION_FAILED_LOG, message)
        wx.showModal({ title: MEDIA_PREPARATION_FAILED_TITLE, content: message, showCancel: false })
      } else {
        wx.showToast({ title: message, icon: 'none' })
      }
    } finally {
      if (result && !adopted) releasePreparedWorkFiles({ wxApi: wx, ownedPathsByClientId: result.ownedPathsByClientId })
      if (session) session.dispose()
      if (this.isCurrentPreparation(generation, session)) {
        this.compressionSession = null
        this.setData({ choosing: false, choosingText: '', choosingTitle: '', choosingCountText: '', choosingHint: '', canCancelPreparation: false })
      }
    }
  },

  chooseMedia(options) {
    return new Promise((resolve, reject) => {
      wx.chooseMedia(Object.assign({}, options, {
        success: resolve,
        fail(error) {
          if (/cancel/i.test(error && (error.errMsg || error.message) || '')) reject(preparationCancelled())
          else reject(error)
        }
      }))
    })
  },

  handleCancelPreparation() {
    const session = this.compressionSession
    if (this.disposed || this.data.saving || !this.data.canCancelPreparation || !session) return
    this.compressionGeneration += 1
    this.compressionSession = null
    session.cancel()
    this.setData({ choosing: false, choosingText: '', choosingTitle: '', choosingCountText: '', choosingHint: '', canCancelPreparation: false,
      preparationCancelled: true })
  },

  async getCompressionCanvas({ width, height, ownerToken, timeoutMs }, session) {
    session.assertActive()
    if (this.disposed || this.compressionSession !== session || !ownerToken) throw preparationCancelled()
    let lease = this.compressionCanvasLease
    if (lease && !lease.released && lease.ownerToken !== ownerToken) {
      throw Object.assign(new Error(COMPRESSION_CANVAS_BUSY_MESSAGE), { code: COMPRESSION_CANVAS_BUSY })
    }
    if (!lease || lease.released) {
      lease = { ownerToken, canvas: null, released: false }
      lease.release = () => {
        if (lease.released || this.compressionCanvasLease !== lease || lease.ownerToken !== ownerToken) return
        lease.released = true
        this.compressionCanvasLease = null
        if (!this.disposed) {
          if (lease.canvas) { lease.canvas.width = 1; lease.canvas.height = 1 }
          this.setData({ compressionCanvasWidth: 1, compressionCanvasHeight: 1 })
        }
        lease.canvas = null
      }
      this.compressionCanvasLease = lease
    }
    const deadline = Date.now() + timeoutMs
    const remaining = () => {
      const milliseconds = deadline - Date.now()
      if (!(milliseconds > 0)) throw Object.assign(new Error(COMPRESSION_CANVAS_FAILED_MESSAGE), { code: COMPRESSION_TIMEOUT })
      return milliseconds
    }
    try {
      await session.call(({ success }) => {
        this.setData({ compressionCanvasWidth: width, compressionCanvasHeight: height }, success)
      }, {}, { timeoutMs: remaining() })
      session.assertActive()
      const canvas = await session.call(({ success, fail }) => {
        getCanvasNode(this, WORK_COMPRESSION_CANVAS_ID, wx).then(success, fail)
      }, {}, { timeoutMs: remaining() })
      session.assertActive()
      if (this.disposed || this.compressionSession !== session || this.compressionCanvasLease !== lease || lease.released) throw preparationCancelled()
      if (!canvas) throw new Error(COMPRESSION_CANVAS_FAILED_MESSAGE)
      lease.canvas = canvas
      canvas.width = width
      canvas.height = height
      return lease
    } catch (error) {
      lease.release()
      if (error && (error.code === COMPRESSION_CANCELLED || error.code === COMPRESSION_TIMEOUT)) throw error
      throw Object.assign(new Error(COMPRESSION_CANVAS_FAILED_MESSAGE), { cause: error })
    }
  },

  assertPageActive() {
    if (this.disposed) throw preparationCancelled()
  },

  // 每项原生读取、上传独立占用路径；并发队列提前失败不能代表其他任务已结束。
  async withLocalFileUse(paths, operation, uploads = false) {
    this.initializeCompressionState()
    this.assertPageActive()
    const uses = uploads ? this.activeUploads : this.activeLocalReads
    const entry = { paths: paths.filter(Boolean) }
    uses.add(entry)
    try { return await operation(entry) } finally {
      uses.delete(entry)
      if (this.disposed) this.releaseUnusedPreparedFiles()
      else if (this.pendingPreparedReleases.size) this.releaseUnusedPreparedFiles([...this.pendingPreparedReleases])
    }
  },

  releaseUnusedPreparedFiles(clientIds) {
    this.initializeCompressionState()
    const busy = new Set([...this.activeLocalReads, ...this.activeUploads].flatMap(entry => entry.paths))
    const releasable = {}
    for (const clientId of clientIds || Object.keys(this.compressionOwnedPaths)) {
      const owned = this.compressionOwnedPaths[clientId] || []
      releasable[clientId] = owned.filter(path => !busy.has(path))
      const retained = owned.filter(path => busy.has(path))
      if (retained.length) {
        this.compressionOwnedPaths[clientId] = retained
        this.pendingPreparedReleases.add(clientId)
      } else {
        delete this.compressionOwnedPaths[clientId]
        this.pendingPreparedReleases.delete(clientId)
      }
    }
    releasePreparedWorkFiles({ wxApi: wx, ownedPathsByClientId: releasable })
  },

  handleFileInput(event) {
    if (this.data.saving || this.data.choosing) return
    const index = Number(event.currentTarget.dataset.index)
    const field = event.currentTarget.dataset.field
    if (!Number.isFinite(index) || !field) {
      return
    }
    this.setData({
      [`files[${index}].${field}`]: event.detail.value || ''
    })
  },

  handleFileTouchStart(event) {
    const touch = (event.touches && event.touches[0]) || {}
    this.setData({
      fileTouchStart: {
        id: event.currentTarget.dataset.id || '',
        x: touch.clientX || 0,
        y: touch.clientY || 0
      }
    })
  },

  handleFileTouchMove() {
  },

  handleFileTouchEnd(event) {
    const start = this.data.fileTouchStart
    if (!start || !start.id) {
      return
    }
    const touch = (event.changedTouches && event.changedTouches[0]) || {}
    const deltaX = (touch.clientX || start.x) - start.x
    const deltaY = Math.abs((touch.clientY || start.y) - start.y)
    if (deltaY <= SWIPE_VERTICAL_TOLERANCE && deltaX < SWIPE_REVEAL_THRESHOLD) {
      this.setData({
        revealedFileId: start.id,
        fileTouchStart: null
      })
      return
    }
    if (deltaX > SWIPE_CLOSE_THRESHOLD || Math.abs(deltaX) < 8) {
      this.setData({
        revealedFileId: '',
        fileTouchStart: null
      })
      return
    }
    this.setData({ fileTouchStart: null })
  },

  handleFileTouchCancel() {
    this.setData({ fileTouchStart: null })
  },

  handleOpenFileEditor(event) {
    if (this.data.saving || this.data.choosing) return
    const index = Number(event.currentTarget.dataset.index)
    const file = this.data.files[index]
    if (!file) {
      return
    }
    if (this.data.revealedFileId === file.id) {
      this.setData({ revealedFileId: '' })
      return
    }
    const editForm = buildEditForm(file, index)
    this.setData({
      editSheetVisible: true,
      editForm,
      editErrorText: '',
      revealedFileId: ''
    })
  },

  handleEditPanelTap() {
  },

  handleCloseFileEditor() {
    this.setData({
      editSheetVisible: false,
      editForm: null,
      editErrorText: ''
    })
  },

  handleEditInput(event) {
    if (this.data.saving || this.data.choosing) return
    const field = event.currentTarget.dataset.field
    if (!field || !this.data.editForm) {
      return
    }
    this.setData({
      [`editForm.${field}`]: event.detail.value || ''
    })
  },

  handleConfirmFileEdit() {
    if (this.data.saving || this.data.choosing) return
    const editForm = this.data.editForm
    if (!editForm) {
      return
    }
    const title = String(editForm.title || '').trim()
    const description = String(editForm.description || '').trim()
    if (!title) {
      this.setData({ editErrorText: '作品标题不能为空' })
      return
    }
    if (title.length > TITLE_MAX_LENGTH) {
      this.setData({ editErrorText: `作品标题不能超过 ${TITLE_MAX_LENGTH} 字` })
      return
    }
    if (description.length > DESCRIPTION_MAX_LENGTH) {
      this.setData({ editErrorText: `作品说明不能超过 ${DESCRIPTION_MAX_LENGTH} 字` })
      return
    }
    const patch = {
      title,
      description
    }
    this.setData({
      [`files[${editForm.index}]`]: Object.assign({}, this.data.files[editForm.index], patch),
      editSheetVisible: false,
      editForm: null,
      editErrorText: ''
    })
  },

  handleOpenTagPicker() {
    if (this.data.saving || this.data.choosing) return
    this.setData({
      tagPickerVisible: true,
      tagErrorText: ''
    })
    this.loadTagPickerTags()
  },

  async loadTagPickerTags() {
    this.setData({
      tagLoading: true,
      tagErrorText: '',
      tagOptionListHeight: 0
    })
    try {
      const response = await request({
        url: WORK_TAGS_API_URL
      })
      if (this.disposed) return
      const tagPickerTags = buildWorkTagPickerOptions(normalizeWorkTags(response), this.data.unifiedTags)
      this.setData({
        tagPickerTags,
        tagOptionListHeight: buildWorkTagOptionListHeight(tagPickerTags),
        selectedUnifiedTagCount: buildUnifiedWorkTagNames(tagPickerTags).length,
        tagLoading: false,
        tagErrorText: ''
      })
    } catch (error) {
      if (this.disposed) return
      if (error && error.authRequired) {
        this.setData({
          tagLoading: false,
          tagPickerVisible: false,
          tagOptionListHeight: 0
        })
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({
        tagLoading: false,
        tagOptionListHeight: 0,
        tagErrorText: error && error.message ? error.message : '标签加载失败'
      })
    }
  },

  handleRetryLoadTags() {
    this.loadTagPickerTags()
  },

  handleTagPickerPanelTap() {
  },

  handleCloseTagPicker() {
    this.setData({
      tagPickerVisible: false,
      tagErrorText: ''
    })
  },

  handleToggleUnifiedTag(event) {
    if (this.data.saving || this.data.choosing) return
    const tagId = String(event.currentTarget.dataset.id || '')
    if (!tagId) {
      return
    }
    const tagPickerTags = (this.data.tagPickerTags || []).map((tag) => {
      if (String(tag.id) !== tagId) {
        return tag
      }
      return Object.assign({}, tag, {
        selected: !tag.selected
      })
    })
    this.setData({
      tagPickerTags,
      selectedUnifiedTagCount: buildUnifiedWorkTagNames(tagPickerTags).length
    })
  },

  handleClearUnifiedTags() {
    if (this.data.saving || this.data.choosing) return
    const tagPickerTags = (this.data.tagPickerTags || []).map((tag) => Object.assign({}, tag, {
      selected: false
    }))
    this.setData({
      tagPickerTags,
      selectedUnifiedTagCount: 0
    })
  },

  handleConfirmTagPicker() {
    if (this.data.saving || this.data.choosing) return
    const unifiedTags = buildUnifiedWorkTagItems(this.data.tagPickerTags)
    this.setData({
      files: applyUnifiedWorkTags(this.data.files, unifiedTags),
      unifiedTags,
      selectedUnifiedTagCount: unifiedTags.length,
      tagPickerVisible: false,
      tagErrorText: ''
    })
  },

  handleRemoveFile(event) {
    if (this.data.saving || this.data.choosing) return
    const index = Number(event.currentTarget.dataset.index)
    if (!Number.isFinite(index)) {
      return
    }
    const files = this.data.files.slice()
    const removed = files.splice(index, 1)
    this.releaseUnusedPreparedFiles(removed.map(file => file.clientId))
    this.setData({
      files,
      revealedFileId: '',
      editSheetVisible: false,
      editForm: null
    })
  },

  async handleSubmit() {
    if (this.data.saving || this.data.choosing) return
    if (!hasLocalToken()) {
      this.redirectToLogin()
      return
    }
    const validation = validateChosenMediaFiles(this.data.files)
    if (!validation.valid) {
      wx.showToast({
        title: validation.message,
        icon: 'none'
      })
      return
    }
    this.setData({
      saving: true,
      errorMessage: '',
      uploadOverallProgress: 0,
      uploadOverallText: '准备上传'
    })
    try {
      let roundResult = await this.uploadAndConfirmRound(this.data.files)
      this.assertPageActive()
      const filesWithFallbacks = applyAnimationSingleFrameFallbacks(roundResult.files)
      const fallbackRequired = filesWithFallbacks.some(
        (file, index) => file !== roundResult.files[index]
      )
      if (fallbackRequired) {
        this.setUploadFiles(filesWithFallbacks)
        roundResult = await this.uploadAndConfirmRound(filesWithFallbacks)
        this.assertPageActive()
      }
      const failedItems = roundResult.items.filter((item) => !item.success)
      if (failedItems.length > 0) {
        throw new Error(buildUploadCompleteFailureMessage(failedItems))
      }
      wx.showToast({
        title: '已保存作品',
        icon: 'success'
      })
      wx.redirectTo({
        url: WORKS_PAGE_URL
      })
    } catch (error) {
      if (this.disposed) return
      if (error && error.authRequired) {
        this.setData({
          saving: false,
          uploadOverallProgress: 0,
          uploadOverallText: '保存作品'
        })
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({
        saving: false,
        uploadOverallProgress: 0,
        uploadOverallText: '保存作品',
        errorMessage: error && error.message ? error.message : '上传失败，请重试'
      })
    }
  },

  async uploadAndConfirmRound(files) {
    const filesWithSha256 = await this.ensureFileSha256(files)
    this.assertPageActive()
    this.setUploadFiles(filesWithSha256)
    const ticketPayload = buildUploadTicketPayload(filesWithSha256)
    let filesWithTickets = filesWithSha256
    if (ticketPayload.files.length > 0) {
      const ticketResponse = await request({
        url: '/api/mine/works/upload-tickets',
        method: 'POST',
        data: ticketPayload
      })
      this.assertPageActive()
      filesWithTickets = this.attachTickets(filesWithSha256, ticketResponse.items || [])
    }
    this.setUploadFiles(filesWithTickets)
    // Promise.all提前拒绝后，其他worker仍会继续；失败批次禁止再启动尚未上传的文件。
    let uploadRoundActive = true
    try {
      await runWorkUploadQueue(filesWithTickets, async file => {
        if (!uploadRoundActive) throw preparationCancelled()
        try {
          return await this.uploadSingleFile(file)
        } catch (error) {
          uploadRoundActive = false
          throw error
        }
      })
    } finally {
      uploadRoundActive = false
    }
    this.assertPageActive()
    await this.uploadCustomCoverFiles()
    this.assertPageActive()
    const completePayload = buildUploadCompletePayload(this.data.files)
    if (completePayload.items.length === 0) {
      return {
        files: this.data.files,
        items: []
      }
    }
    const completeResponse = await request({
      url: '/api/mine/works/upload-complete',
      method: 'POST',
      data: completePayload
    })
    this.assertPageActive()
    const completeItems = completeResponse.items || []
    const filesWithCompleteResults = applyUploadCompleteResults(
      this.data.files, completeItems
    )
    this.setUploadFiles(filesWithCompleteResults)
    return {
      files: filesWithCompleteResults,
      items: completeItems
    }
  },

  setUploadFiles(files) {
    if (this.disposed) return
    const summary = buildUploadProgressSummary(files, this.data.saving)
    this.setData({
      files,
      uploadOverallProgress: summary.percent,
      uploadOverallText: summary.text
    })
  },

  async calculateFileSha256(filePath) {
    return calculateLocalFileSha256(filePath, { wxApi: wx })
  },

  async ensureFileSha256(files) {
    this.assertPageActive()
    const nextFiles = files.map((file) => Object.assign({}, file))
    for (let index = 0; index < nextFiles.length; index++) {
      const file = nextFiles[index]
      if (!file || file.confirmedWorkId || file.taskId || file.sha256) {
        continue
      }
      if (!file.tempFilePath) {
        throw new Error('作品文件缺失，请重新选择')
      }
      this.setData({ uploadOverallText: '计算文件指纹' })
      file.sha256 = await this.withLocalFileUse([file.tempFilePath], () => this.calculateFileSha256(file.tempFilePath))
      this.assertPageActive()
      this.setData({ [`files[${index}].sha256`]: file.sha256 })
    }
    return nextFiles
  },

  async ensureCoverSha256(files) {
    this.assertPageActive()
    const nextFiles = files.map((file) => Object.assign({}, file))
    for (let index = 0; index < nextFiles.length; index++) {
      const file = nextFiles[index]
      if (!file || file.confirmedWorkId || file.customCoverStatus === 'UPLOADED') {
        continue
      }
      const coverPath = getCoverUploadPath(file)
      if (!coverPath) {
        if (file.mediaType === 'IMAGE' && file.sha256) {
          file.coverSha256 = file.sha256
        }
        continue
      }
      if (file.customCoverPath && file.customCoverSha256) {
        continue
      }
      if (!file.customCoverPath && file.coverSha256) {
        continue
      }
      this.setData({ uploadOverallText: '计算文件指纹' })
      const sha256 = await this.withLocalFileUse([coverPath], () => this.calculateFileSha256(coverPath))
      this.assertPageActive()
      if (file.customCoverPath) {
        file.customCoverSha256 = sha256
        this.setData({ [`files[${index}].customCoverSha256`]: sha256 })
      } else {
        file.coverSha256 = sha256
        this.setData({ [`files[${index}].coverSha256`]: sha256 })
      }
    }
    return nextFiles
  },

  attachTickets(files, tickets) {
    const ticketMap = tickets.reduce((result, ticket) => {
      result[ticket.clientId] = ticket
      return result
    }, {})
    return files.map((file) => {
      const ticket = ticketMap[file.clientId]
      if (!ticket) {
        return file
      }
      return Object.assign({}, file, {
        taskId: ticket.taskId,
        uploadTicket: ticket,
        status: 'UPLOADING',
        progress: 0
      })
    })
  },

  attachCoverTickets(files, tickets) {
    const ticketMap = tickets.reduce((result, ticket) => {
      result[ticket.clientId] = ticket
      return result
    }, {})
    return files.map((file) => {
      if (!getCoverUploadPath(file)) {
        return file
      }
      const ticket = ticketMap[`${file.clientId}-cover`]
      if (!ticket) {
        return file
      }
      return Object.assign({}, file, {
        customCoverTaskId: ticket.taskId,
        customCoverUploadTicket: ticket,
        customCoverStatus: 'UPLOADING',
        customCoverProgress: 0
      })
    })
  },

  async uploadCustomCoverFiles() {
    const coverSources = this.data.files.flatMap(file => [file.tempFilePath, getCoverUploadPath(file)])
    const filesWithPreparedCover = await this.withLocalFileUse(coverSources, () => prepareCoverUploadFiles(this.data.files, { wxApi: wx }))
    this.assertPageActive()
    const filesWithCoverInfo = await this.ensureCoverSha256(filesWithPreparedCover)
    this.assertPageActive()
    const coverTicketPayload = buildCoverUploadTicketPayload(filesWithCoverInfo, `${COVER_BATCH_PREFIX}-${Date.now()}`)
    if (!coverTicketPayload.files.length) {
      return
    }
    this.setUploadFiles(filesWithCoverInfo)
    const ticketResponse = await request({
      url: '/api/mine/works/upload-tickets',
      method: 'POST',
      data: coverTicketPayload
    })
    this.assertPageActive()
    const filesWithCoverTickets = this.attachCoverTickets(filesWithCoverInfo, ticketResponse.items || [])
    this.setUploadFiles(filesWithCoverTickets)
    const coverFiles = filesWithCoverTickets.filter((file) => getCoverUploadPath(file) && file.customCoverUploadTicket)
    for (const file of coverFiles) {
      await this.uploadSingleCoverFile(file)
    }
  },

  async uploadSingleFile(file) {
    this.assertPageActive()
    if (!file.uploadTicket) {
      throw new Error('上传票据缺失')
    }
    this.updateFile(file.id, {
      status: 'UPLOADING',
      progress: 1
    })
    await this.uploadFileToCos(file, file.uploadTicket, (target, progress) => {
      this.updateFile(target.id, { progress: progress.progress || 0 })
    })
    this.updateFile(file.id, {
      status: 'UPLOADED',
      progress: 100
    })
  },

  async uploadSingleCoverFile(file) {
    this.assertPageActive()
    if (!file.customCoverUploadTicket) {
      throw new Error('封面上传票据缺失')
    }
    const coverPath = getCoverUploadPath(file)
    if (!coverPath) {
      throw new Error('封面文件缺失')
    }
    this.updateFile(file.id, {
      customCoverStatus: 'UPLOADING',
      customCoverProgress: 1
    })
    const coverUploadFile = Object.assign({}, file, {
      id: `${file.id}-cover`,
      tempFilePath: coverPath
    })
    await this.uploadFileToCos(coverUploadFile, file.customCoverUploadTicket, (target, progress) => {
      this.updateFile(file.id, { customCoverProgress: progress.progress || 0 })
    })
    this.updateFile(file.id, {
      customCoverStatus: 'UPLOADED',
      customCoverProgress: 100
    })
  },

  async uploadFileToCos(file, ticket, onProgress) {
    return this.withLocalFileUse([file.tempFilePath], async entry => {
      try {
        return await uploadToCos(file, ticket, {
          wxApi: wx,
          onTask: (target, task) => {
            entry.task = task
            this.uploadTasks[target.id] = task
          },
          onProgress
        })
      } finally {
        // 重试可能仍有旧并发任务收尾，旧任务不能撤销新任务的中止句柄。
        if (this.uploadTasks[file.id] === entry.task) delete this.uploadTasks[file.id]
      }
    }, true)
  },

  updateFile(fileId, patch) {
    if (this.disposed) return
    const index = this.data.files.findIndex((item) => item.id === fileId)
    if (index < 0) {
      return
    }
    const nextFile = Object.assign({}, this.data.files[index], patch)
    const nextFiles = this.data.files.slice()
    nextFiles[index] = nextFile
    const summary = buildUploadProgressSummary(nextFiles, this.data.saving)
    this.setData({
      [`files[${index}]`]: nextFile,
      uploadOverallProgress: summary.percent,
      uploadOverallText: summary.text
    })
  }
})
