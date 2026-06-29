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
  buildUploadCompleteFailureMessage,
  buildUploadCompletePayload,
  buildUploadTicketPayload,
  createChooseMediaOptions,
  normalizeChosenMediaFiles,
  runWorkUploadQueue,
  uploadToCos,
  validateChosenMediaFiles
} = require('../../utils/work-upload')

const WORKS_PAGE_URL = '/pages/works/works'
const WORK_TAGS_API_URL = '/api/mine/works/tags'

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
    selectedUnifiedTagCount: 0
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
        errorMessage: ''
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
    this.setData({ files })
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
      errorMessage: ''
    })
    try {
      const ticketResponse = await request({
        url: '/api/mine/works/upload-tickets',
        method: 'POST',
        data: buildUploadTicketPayload(this.data.files)
      })
      const filesWithTickets = this.attachTickets(this.data.files, ticketResponse.items || [])
      this.setData({ files: filesWithTickets })
      await runWorkUploadQueue(filesWithTickets, (file) => this.uploadSingleFile(file))
      const completeResponse = await request({
        url: '/api/mine/works/upload-complete',
        method: 'POST',
        data: buildUploadCompletePayload(this.data.files)
      })
      const failedItems = (completeResponse.items || []).filter((item) => !item.success)
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
        this.setData({ saving: false })
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        saving: false,
        errorMessage: error && error.message ? error.message : '上传失败，请重试'
      })
    }
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

  updateFile(fileId, patch) {
    const index = this.data.files.findIndex((item) => item.id === fileId)
    if (index < 0) {
      return
    }
    const nextFile = Object.assign({}, this.data.files[index], patch)
    this.setData({
      [`files[${index}]`]: nextFile
    })
  }
})
