const { request } = require('../../utils/request')
const { handleAuthRequired, hasLocalToken } = require('../../utils/session')
const {
  applyUnifiedWorkTags,
  buildUnifiedWorkTagItems,
  buildUnifiedWorkTagNames,
  buildWorkTagPickerOptions,
  normalizeWorkTags
} = require('../../utils/works')
const {
  applyUploadCompleteResults,
  buildUploadCompleteFailureMessage,
  buildUploadCompletePayload,
  buildCoverUploadTicketPayload,
  buildUploadTicketPayload,
  buildThumbFileName,
  buildUploadProgressSummary,
  createChooseMediaOptions,
  normalizeChosenMediaFiles,
  prepareCoverUploadFiles,
  runWorkUploadQueue,
  uploadToCos,
  validateChosenMediaFiles
} = require('../../utils/work-upload')
const { writeRgbaFrameToCanvas } = require('../../utils/frame-canvas')

const WORKS_PAGE_URL = '/pages/works/works'
const WORK_TAGS_API_URL = '/api/mine/works/tags'
const COVER_CANVAS_ID = 'workCoverCanvas'
const DEFAULT_CANVAS_SIZE = 1
const VIDEO_FRAME_FILE_TYPE = 'jpg'
const VIDEO_FRAME_QUALITY = 0.92
const TITLE_MAX_LENGTH = 30
const DESCRIPTION_MAX_LENGTH = 1000
const SWIPE_REVEAL_THRESHOLD = -32
const SWIPE_CLOSE_THRESHOLD = 24
const SWIPE_VERTICAL_TOLERANCE = 48
const COVER_BATCH_PREFIX = 'cover'

function clampNumber(value, min, max) {
  const numberValue = Number(value)
  if (!Number.isFinite(numberValue)) {
    return min
  }
  return Math.max(min, Math.min(max, numberValue))
}

function formatFrameTime(milliseconds) {
  const totalSeconds = Math.max(0, Math.round(Number(milliseconds || 0) / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

function buildEditForm(file, index) {
  const durationMs = Math.max(0, Number(file.durationMs || 0))
  const frameTimeMs = clampNumber(file.coverFrameTimeMs || 0, 0, durationMs)
  return {
    index,
    id: file.id,
    fileName: file.fileName,
    mediaType: file.mediaType,
    isVideo: file.mediaType === 'VIDEO',
    tempFilePath: file.tempFilePath,
    coverPath: file.coverPath || '',
    title: file.title || '',
    description: file.description || '',
    durationMs,
    durationText: formatFrameTime(durationMs),
    coverFrameTimeMs: frameTimeMs,
    customCoverPath: file.customCoverPath || '',
    customCoverSize: file.customCoverSize || 0,
    customCoverWidth: file.customCoverWidth || 0,
    customCoverHeight: file.customCoverHeight || 0,
    customCoverFileName: file.customCoverFileName || '',
    customCoverIdempotencyKey: file.customCoverIdempotencyKey || ''
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
    errorMessage: '',
    unifiedTags: [],
    tagPickerVisible: false,
    tagLoading: false,
    tagErrorText: '',
    tagPickerTags: [],
    selectedUnifiedTagCount: 0,
    uploadOverallProgress: 0,
    uploadOverallText: '保存作品',
    revealedFileId: '',
    fileTouchStart: null,
    editSheetVisible: false,
    editForm: null,
    editFrameTimeMs: 0,
    editFrameTimeText: '00:00',
    editFrameExporting: false,
    editErrorText: '',
    frameCanvasWidth: DEFAULT_CANVAS_SIZE,
    frameCanvasHeight: DEFAULT_CANVAS_SIZE
  },

  onLoad() {
    if (!hasLocalToken()) {
      this.redirectToLogin()
    }
  },

  onUnload() {
    Object.keys(this.uploadTasks).forEach((key) => {
      const task = this.uploadTasks[key]
      if (task && task.abort) {
        task.abort()
      }
    })
    this.uploadTasks = {}
  },

  redirectToLogin() {
    wx.redirectTo({
      url: '/pages/login/login'
    })
  },

  async handleChooseMedia() {
    const remainingCount = 9 - this.data.files.length
    if (remainingCount <= 0) {
      wx.showToast({
        title: '一次最多上传 9 个作品',
        icon: 'none'
      })
      return
    }
    try {
      const response = await this.chooseMedia(createChooseMediaOptions(remainingCount))
      const selectedFiles = applyUnifiedWorkTags(
        normalizeChosenMediaFiles(response.tempFiles || []),
        this.data.unifiedTags
      )
      const nextFiles = this.data.files.concat(selectedFiles)
      const validation = validateChosenMediaFiles(nextFiles)
      if (!validation.valid) {
        wx.showToast({
          title: validation.message,
          icon: 'none'
        })
        return
      }
      this.setData({
        files: nextFiles,
        errorMessage: '',
        revealedFileId: ''
      })
    } catch (error) {
      if (error && /cancel/.test(error.errMsg || error.message || '')) {
        return
      }
      wx.showToast({
        title: error && error.message ? error.message : '选择作品失败',
        icon: 'none'
      })
    }
  },

  chooseMedia(options) {
    return new Promise((resolve, reject) => {
      wx.chooseMedia(Object.assign({}, options, {
        success: resolve,
        fail: reject
      }))
    })
  },

  handleFileInput(event) {
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
    if (this.data.saving) {
      return
    }
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
      editFrameTimeMs: editForm.coverFrameTimeMs,
      editFrameTimeText: formatFrameTime(editForm.coverFrameTimeMs),
      editErrorText: '',
      revealedFileId: ''
    })
  },

  handleEditPanelTap() {
  },

  handleCloseFileEditor() {
    if (this.data.editFrameExporting) {
      return
    }
    this.setData({
      editSheetVisible: false,
      editForm: null,
      editErrorText: ''
    })
  },

  handleEditInput(event) {
    const field = event.currentTarget.dataset.field
    if (!field || !this.data.editForm) {
      return
    }
    this.setData({
      [`editForm.${field}`]: event.detail.value || ''
    })
  },

  handleEditVideoMetadata(event) {
    const detail = event.detail || {}
    if (!this.data.editForm || !detail.duration) {
      return
    }
    const durationMs = Math.round(Number(detail.duration || 0) * 1000)
    const frameTimeMs = clampNumber(this.data.editFrameTimeMs, 0, durationMs)
    this.setData({
      'editForm.durationMs': durationMs,
      'editForm.durationText': formatFrameTime(durationMs),
      'editForm.coverFrameTimeMs': frameTimeMs,
      editFrameTimeMs: frameTimeMs,
      editFrameTimeText: formatFrameTime(frameTimeMs)
    })
  },

  handleEditVideoTimeUpdate(event) {
    if (!this.data.editForm || this.data.editFrameExporting) {
      return
    }
    const currentTime = event.detail && event.detail.currentTime
    const frameTimeMs = clampNumber(Math.round(Number(currentTime || 0) * 1000), 0, this.data.editForm.durationMs || 0)
    this.setData({
      'editForm.coverFrameTimeMs': frameTimeMs,
      editFrameTimeMs: frameTimeMs,
      editFrameTimeText: formatFrameTime(frameTimeMs)
    })
  },

  handleCoverSliderChanging(event) {
    this.updateCoverFrameTime(event.detail.value, false)
  },

  handleCoverSliderChange(event) {
    this.updateCoverFrameTime(event.detail.value, true)
  },

  updateCoverFrameTime(value, syncVideo) {
    if (!this.data.editForm) {
      return
    }
    const frameTimeMs = clampNumber(value, 0, this.data.editForm.durationMs || 0)
    this.setData({
      'editForm.coverFrameTimeMs': frameTimeMs,
      editFrameTimeMs: frameTimeMs,
      editFrameTimeText: formatFrameTime(frameTimeMs)
    })
    if (syncVideo && wx.createVideoContext) {
      const videoContext = wx.createVideoContext('workCoverVideo', this)
      if (videoContext && videoContext.seek) {
        videoContext.seek(frameTimeMs / 1000)
      }
    }
  },

  async handleExportVideoCover() {
    const editForm = this.data.editForm
    if (!editForm || !editForm.isVideo) {
      return
    }
    if (!wx.createVideoDecoder) {
      this.setData({ editErrorText: '当前基础库不支持视频帧导出，请保留默认封面' })
      wx.showToast({
        title: '当前环境不支持',
        icon: 'none'
      })
      return
    }
    this.setData({
      editFrameExporting: true,
      editErrorText: ''
    })
    let decoder = null
    try {
      decoder = wx.createVideoDecoder()
      await decoder.start({
        source: editForm.tempFilePath,
        abortAudio: true
      })
      await decoder.seek(this.data.editFrameTimeMs)
      const frame = await this.readVideoFrame(decoder)
      const coverPath = await this.writeFrameToCanvas(frame)
      const fileInfo = await this.getFileInfo(coverPath)
      this.setData({
        'editForm.coverPath': coverPath,
        'editForm.customCoverPath': coverPath,
        'editForm.customCoverSize': fileInfo.size || 0,
        'editForm.customCoverWidth': frame.width,
        'editForm.customCoverHeight': frame.height,
        'editForm.customCoverFileName': buildThumbFileName(editForm.fileName),
        'editForm.customCoverIdempotencyKey': `cover-ticket-${editForm.id}-${this.data.editFrameTimeMs}`,
        'editForm.coverFrameTimeMs': this.data.editFrameTimeMs,
        editErrorText: ''
      })
      wx.showToast({
        title: '已设置封面',
        icon: 'success'
      })
    } catch (error) {
      const message = error && error.message ? error.message : '封面导出失败，请用真机重试'
      this.setData({ editErrorText: message })
      wx.showToast({
        title: '封面导出失败',
        icon: 'none'
      })
    } finally {
      if (decoder && decoder.stop) {
        decoder.stop()
      }
      if (decoder && decoder.remove) {
        decoder.remove()
      }
      this.setData({ editFrameExporting: false })
    }
  },

  async readVideoFrame(decoder) {
    for (let index = 0; index < 12; index++) {
      const frame = decoder.getFrameData()
      if (frame && frame.data && frame.width && frame.height) {
        return frame
      }
      await new Promise((resolve) => setTimeout(resolve, 80))
    }
    throw new Error('未能读取当前帧，请换一个时间点重试')
  },

  async writeFrameToCanvas(frame) {
    return writeRgbaFrameToCanvas({
      page: this,
      canvasId: COVER_CANVAS_ID,
      frame,
      canvasWidthDataKey: 'frameCanvasWidth',
      canvasHeightDataKey: 'frameCanvasHeight',
      fileType: VIDEO_FRAME_FILE_TYPE,
      quality: VIDEO_FRAME_QUALITY
    })
  },

  getFileInfo(filePath) {
    return new Promise((resolve, reject) => {
      wx.getFileInfo({
        filePath,
        success: resolve,
        fail: reject
      })
    })
  },

  handleConfirmFileEdit() {
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
      description,
      coverFrameTimeMs: editForm.coverFrameTimeMs || 0
    }
    if (editForm.isVideo && editForm.customCoverPath) {
      Object.assign(patch, {
        coverPath: editForm.coverPath,
        customCoverPath: editForm.customCoverPath,
        customCoverSize: editForm.customCoverSize,
        customCoverWidth: editForm.customCoverWidth,
        customCoverHeight: editForm.customCoverHeight,
        customCoverFileName: editForm.customCoverFileName,
        customCoverIdempotencyKey: editForm.customCoverIdempotencyKey
      })
    }
    this.setData({
      [`files[${editForm.index}]`]: Object.assign({}, this.data.files[editForm.index], patch),
      editSheetVisible: false,
      editForm: null,
      editErrorText: ''
    })
  },

  handleOpenTagPicker() {
    if (this.data.saving) {
      return
    }
    this.setData({
      tagPickerVisible: true,
      tagErrorText: ''
    })
    this.loadTagPickerTags()
  },

  async loadTagPickerTags() {
    this.setData({
      tagLoading: true,
      tagErrorText: ''
    })
    try {
      const response = await request({
        url: WORK_TAGS_API_URL
      })
      const tagPickerTags = buildWorkTagPickerOptions(normalizeWorkTags(response), this.data.unifiedTags)
      this.setData({
        tagPickerTags,
        selectedUnifiedTagCount: buildUnifiedWorkTagNames(tagPickerTags).length,
        tagLoading: false,
        tagErrorText: ''
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({
          tagLoading: false,
          tagPickerVisible: false
        })
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        tagLoading: false,
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
    const tagPickerTags = (this.data.tagPickerTags || []).map((tag) => Object.assign({}, tag, {
      selected: false
    }))
    this.setData({
      tagPickerTags,
      selectedUnifiedTagCount: 0
    })
  },

  handleConfirmTagPicker() {
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
    const index = Number(event.currentTarget.dataset.index)
    if (!Number.isFinite(index)) {
      return
    }
    const files = this.data.files.slice()
    files.splice(index, 1)
    this.setData({
      files,
      revealedFileId: '',
      editSheetVisible: false,
      editForm: null
    })
  },

  async handleSubmit() {
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
      const ticketPayload = buildUploadTicketPayload(this.data.files)
      let filesWithTickets = this.data.files
      if (ticketPayload.files.length > 0) {
        const ticketResponse = await request({
          url: '/api/mine/works/upload-tickets',
          method: 'POST',
          data: ticketPayload
        })
        filesWithTickets = this.attachTickets(this.data.files, ticketResponse.items || [])
      }
      this.setUploadFiles(filesWithTickets)
      await runWorkUploadQueue(filesWithTickets, (file) => this.uploadSingleFile(file))
      await this.uploadCustomCoverFiles()
      const completePayload = buildUploadCompletePayload(this.data.files)
      if (completePayload.items.length === 0) {
        wx.showToast({
          title: '已保存作品',
          icon: 'success'
        })
        wx.redirectTo({
          url: WORKS_PAGE_URL
        })
        return
      }
      const completeResponse = await request({
        url: '/api/mine/works/upload-complete',
        method: 'POST',
        data: completePayload
      })
      const completeItems = completeResponse.items || []
      const filesWithCompleteResults = applyUploadCompleteResults(this.data.files, completeItems)
      this.setUploadFiles(filesWithCompleteResults)
      const failedItems = completeItems.filter((item) => !item.success)
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
      if (error && error.authRequired) {
        this.setData({
          saving: false,
          uploadOverallProgress: 0,
          uploadOverallText: '保存作品'
        })
        handleAuthRequired(error.message)
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

  setUploadFiles(files) {
    const summary = buildUploadProgressSummary(files, this.data.saving)
    this.setData({
      files,
      uploadOverallProgress: summary.percent,
      uploadOverallText: summary.text
    })
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
    const filesWithCoverInfo = await prepareCoverUploadFiles(this.data.files)
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
    const filesWithCoverTickets = this.attachCoverTickets(filesWithCoverInfo, ticketResponse.items || [])
    this.setUploadFiles(filesWithCoverTickets)
    const coverFiles = filesWithCoverTickets.filter((file) => getCoverUploadPath(file) && file.customCoverUploadTicket)
    for (const file of coverFiles) {
      await this.uploadSingleCoverFile(file)
    }
  },

  async uploadSingleFile(file) {
    if (!file.uploadTicket) {
      throw new Error('上传票据缺失')
    }
    this.updateFile(file.id, {
      status: 'UPLOADING',
      progress: 1
    })
    await uploadToCos(file, file.uploadTicket, {
      onTask: (target, task) => {
        this.uploadTasks[target.id] = task
      },
      onProgress: (target, progress) => {
        this.updateFile(target.id, {
          progress: progress.progress || 0
        })
      }
    })
    this.updateFile(file.id, {
      status: 'UPLOADED',
      progress: 100
    })
    delete this.uploadTasks[file.id]
  },

  async uploadSingleCoverFile(file) {
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
    await uploadToCos(coverUploadFile, file.customCoverUploadTicket, {
      onTask: (target, task) => {
        this.uploadTasks[target.id] = task
      },
      onProgress: (target, progress) => {
        this.updateFile(file.id, {
          customCoverProgress: progress.progress || 0
        })
      }
    })
    this.updateFile(file.id, {
      customCoverStatus: 'UPLOADED',
      customCoverProgress: 100
    })
    delete this.uploadTasks[`${file.id}-cover`]
  },

  updateFile(fileId, patch) {
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
