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
  buildUploadProgressSummary,
  createChooseMediaOptions,
  enrichVideoFileMetadata,
  normalizeChosenMediaFiles,
  prepareCoverUploadFiles,
  runWorkUploadQueue,
  uploadToCos,
  validateChosenMediaFiles
} = require('../../utils/work-upload')
const { calculateFileSha256: calculateLocalFileSha256 } = require('../../utils/sha256')

const WORKS_PAGE_URL = '/pages/works/works'
const WORK_TAGS_API_URL = '/api/mine/works/tags'
const TITLE_MAX_LENGTH = 30
const DESCRIPTION_MAX_LENGTH = 1000
const SWIPE_REVEAL_THRESHOLD = -32
const SWIPE_CLOSE_THRESHOLD = 24
const SWIPE_VERTICAL_TOLERANCE = 48
const COVER_BATCH_PREFIX = 'cover'

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
    editErrorText: ''
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
      const mediaFiles = await enrichVideoFileMetadata(normalizeChosenMediaFiles(response.tempFiles || []))
      const selectedFiles = applyUnifiedWorkTags(
        mediaFiles,
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
    const field = event.currentTarget.dataset.field
    if (!field || !this.data.editForm) {
      return
    }
    this.setData({
      [`editForm.${field}`]: event.detail.value || ''
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
      const filesWithSha256 = await this.ensureFileSha256(this.data.files)
      this.setUploadFiles(filesWithSha256)
      const ticketPayload = buildUploadTicketPayload(filesWithSha256)
      let filesWithTickets = filesWithSha256
      if (ticketPayload.files.length > 0) {
        const ticketResponse = await request({
          url: '/api/mine/works/upload-tickets',
          method: 'POST',
          data: ticketPayload
        })
        filesWithTickets = this.attachTickets(filesWithSha256, ticketResponse.items || [])
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

  async calculateFileSha256(filePath) {
    return calculateLocalFileSha256(filePath)
  },

  async ensureFileSha256(files) {
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
      file.sha256 = await this.calculateFileSha256(file.tempFilePath)
      this.setData({ [`files[${index}].sha256`]: file.sha256 })
    }
    return nextFiles
  },

  async ensureCoverSha256(files) {
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
      const sha256 = await this.calculateFileSha256(coverPath)
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
    const filesWithPreparedCover = await prepareCoverUploadFiles(this.data.files)
    const filesWithCoverInfo = await this.ensureCoverSha256(filesWithPreparedCover)
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
