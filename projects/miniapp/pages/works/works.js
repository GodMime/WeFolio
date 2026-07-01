const { request } = require('../../utils/request')
const { normalizeId } = require('../../utils/id')
const { handleAuthRequired, hasLocalToken } = require('../../utils/session')
const {
  DEFAULT_WORK_TAG_COLOR,
  WORK_TAG_COLOR_OPTIONS,
  WORK_TAG_MAX_COUNT,
  buildWorkFieldCounters,
  buildWorkUpdatePayload,
  buildWorkTagDeleteBlockedMessage,
  buildWorkTagPayload,
  createWorkTagForm,
  normalizeWorkList,
  validateWorkForm,
  validateWorkTagForm
} = require('../../utils/works')

const ADD_WORK_PAGE_URL = '/pages/work-add/work-add'
const MINE_PAGE_URL = '/pages/index/index'
const SCHEDULE_PAGE_URL = '/pages/schedule/schedule'
const WORK_TAGS_API_URL = '/api/mine/works/tags'
const WORK_TAG_DELETE_API_PREFIX = '/api/mine/works/tags/delete'
const WORKS_API_PREFIX = '/api/mine/works'
const EDIT_COVER_CLIENT_PREFIX = 'edit-work'

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

function getEditFileName(work = {}) {
  if (work.originalFileName) {
    return work.originalFileName
  }
  return work.mediaType === 'VIDEO' ? `work-${work.id || Date.now()}.mp4` : `work-${work.id || Date.now()}.jpg`
}

function buildBaseWorkEditForm(work = {}) {
  const fileName = getEditFileName(work)
  const previewPath = work.coverUrl || work.mediaUrl || ''
  return {
    id: work.id,
    mediaType: work.mediaType,
    title: work.title || '',
    description: work.description || '',
    originalFileName: work.originalFileName || fileName,
    fileName,
    mediaUrl: work.mediaUrl || '',
    previewPath,
    coverPath: work.coverUrl || '',
    width: Math.max(0, Number(work.width || 0)),
    height: Math.max(0, Number(work.height || 0))
  }
}

function buildImageEditForm(work = {}) {
  return Object.assign(buildBaseWorkEditForm(work), {
    isVideo: false
  })
}

function buildVideoEditForm(work = {}) {
  const durationMs = Math.max(0, Number(work.durationMs || 0))
  return Object.assign(buildBaseWorkEditForm(work), {
    clientId: `${EDIT_COVER_CLIENT_PREFIX}-${work.id}`,
    isVideo: true,
    tempFilePath: work.mediaUrl || '',
    localVideoPath: '',
    coverEditorReady: false,
    durationMs,
    durationText: work.durationText || formatFrameTime(durationMs),
    coverFrameTimeMs: 0,
    coverFrameSelected: false
  })
}

function hasOwnField(object, field) {
  return Object.prototype.hasOwnProperty.call(object || {}, field)
}

Page({
  requestSeq: 0,
  uploadTasks: {},

  data: {
    loading: true,
    loadingMore: false,
    errorMessage: '',
    keyword: '',
    selectedTagId: null,
    batchMode: false,
    tagManageMode: false,
    tagDialogVisible: false,
    tagDialogMode: 'create',
    tagDialogTitle: '新增标签',
    tagForm: createWorkTagForm({ color: DEFAULT_WORK_TAG_COLOR }),
    tagColorOptions: WORK_TAG_COLOR_OPTIONS,
    tagSaving: false,
    tagErrorText: '',
    list: normalizeWorkList({}),
    imageEditSheetVisible: false,
    imageEditForm: null,
    imageEditFieldCounters: buildWorkFieldCounters({}),
    imageEditSaving: false,
    imageEditErrorText: '',
    videoEditSheetVisible: false,
    videoEditForm: null,
    videoEditFieldCounters: buildWorkFieldCounters({}),
    videoEditSaving: false,
    videoDownloading: false,
    videoFrameTimeMs: 0,
    videoFrameTimeText: '00:00',
    videoFrameExporting: false,
    videoEditErrorText: '',
    tabs: [
      { key: 'schedule', label: '档期', icon: 'schedule' },
      { key: 'work', label: '作品', icon: 'work', active: true },
      { key: 'portfolio', label: '作品集', icon: 'portfolio' },
      { key: 'mine', label: '我的', icon: 'mine' }
    ]
  },

  onShow() {
    this.bootstrap()
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

  bootstrap() {
    if (!hasLocalToken()) {
      this.redirectToLogin()
      return
    }
    this.loadWorks(true)
  },

  async loadWorks(reset = false) {
    const requestSeq = this.requestSeq + 1
    this.requestSeq = requestSeq
    const currentList = this.data.list
    const nextPage = reset ? 1 : currentList.page + 1
    if (reset) {
      this.setData({
        loading: true,
        errorMessage: ''
      })
    } else {
      this.setData({ loadingMore: true })
    }
    try {
      const response = await request({
        url: '/api/mine/works',
        data: {
          keyword: this.data.keyword,
          tagId: this.data.selectedTagId || undefined,
          page: nextPage,
          pageSize: currentList.pageSize || 20
        }
      })
      if (requestSeq !== this.requestSeq) {
        return
      }
      const normalized = normalizeWorkList(response)
      if (!reset) {
        normalized.works = currentList.works.concat(normalized.works)
        normalized.empty = normalized.works.length === 0
      }
      this.setData({
        list: normalized,
        loading: false,
        loadingMore: false,
        errorMessage: ''
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({
          loading: false,
          loadingMore: false
        })
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        loading: false,
        loadingMore: false,
        errorMessage: error && error.message ? error.message : '作品加载失败'
      })
    }
  },

  redirectToLogin() {
    wx.redirectTo({
      url: '/pages/login/login'
    })
  },

  handleRetry() {
    this.loadWorks(true)
  },

  handleSearchInput(event) {
    this.setData({
      keyword: event.detail.value || ''
    })
  },

  handleSearchConfirm() {
    this.loadWorks(true)
  },

  handleFilterShellTap() {
  },

  handleCloseTagManageMode() {
    if (!this.data.tagManageMode) {
      return
    }
    this.setData({
      tagManageMode: false
    })
  },

  handleTagTap(event) {
    const tagId = normalizeId(event.currentTarget.dataset.id)
    if (this.data.tagManageMode && tagId) {
      this.openTagDialog('edit', this.findTagById(tagId))
      return
    }
    this.setData({
      selectedTagId: tagId || null,
      tagManageMode: false
    })
    this.loadWorks(true)
  },

  handleTagLongPress(event) {
    const tagId = normalizeId(event.currentTarget.dataset.id)
    if (!tagId) {
      return
    }
    this.setData({
      tagManageMode: true
    })
  },

  handleOpenCreateTagDialog() {
    if ((this.data.list.tags || []).length >= WORK_TAG_MAX_COUNT) {
      wx.showToast({
        title: '标签最多保留 10 个',
        icon: 'none'
      })
      return
    }
    this.openTagDialog('create')
  },

  openTagDialog(mode, tag = {}) {
    const isEdit = mode === 'edit'
    this.setData({
      tagDialogVisible: true,
      tagDialogMode: isEdit ? 'edit' : 'create',
      tagDialogTitle: isEdit ? '编辑标签' : '新增标签',
      tagForm: createWorkTagForm(isEdit ? tag : { color: DEFAULT_WORK_TAG_COLOR }),
      tagErrorText: '',
      tagSaving: false,
      tagManageMode: isEdit
    })
  },

  handleCloseTagDialog() {
    if (this.data.tagSaving) {
      return
    }
    this.setData({
      tagDialogVisible: false,
      tagErrorText: ''
    })
  },

  handleTagNameInput(event) {
    this.setData({
      'tagForm.name': event.detail.value || '',
      tagErrorText: ''
    })
  },

  handleSelectTagColor(event) {
    this.setData({
      'tagForm.color': event.currentTarget.dataset.color || DEFAULT_WORK_TAG_COLOR,
      tagErrorText: ''
    })
  },

  async handleSaveTag() {
    if (this.data.tagSaving) {
      return
    }
    const editingTagId = this.data.tagDialogMode === 'edit' ? this.data.tagForm.id : null
    const validation = validateWorkTagForm(this.data.tagForm, this.data.list.tags, editingTagId)
    if (!validation.valid) {
      this.setData({
        tagErrorText: validation.message
      })
      return
    }
    this.setData({
      tagSaving: true,
      tagErrorText: ''
    })
    try {
      await request({
        url: editingTagId ? `${WORK_TAGS_API_URL}/${editingTagId}` : WORK_TAGS_API_URL,
        method: editingTagId ? 'PUT' : 'POST',
        data: buildWorkTagPayload(this.data.tagForm)
      })
      wx.showToast({
        title: editingTagId ? '标签已更新' : '标签已新增',
        icon: 'success'
      })
      this.setData({
        tagDialogVisible: false,
        tagManageMode: false,
        tagSaving: false
      })
      await this.loadWorks(true)
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({
          tagSaving: false
        })
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        tagSaving: false,
        tagErrorText: error && error.message ? error.message : '标签保存失败'
      })
    }
  },

  async handleTagDeleteTap(event) {
    const tagId = normalizeId(event.currentTarget.dataset.id)
    const tag = this.findTagById(tagId)
    if (!tag) {
      return
    }
    if (tag.count > 0) {
      wx.showToast({
        title: buildWorkTagDeleteBlockedMessage(tag),
        icon: 'none',
        duration: 2600
      })
      return
    }
    try {
      await request({
        url: `${WORK_TAG_DELETE_API_PREFIX}/${tagId}`,
        method: 'POST'
      })
      wx.showToast({
        title: '标签已删除',
        icon: 'success'
      })
      this.setData({
        selectedTagId: this.data.selectedTagId === tagId ? null : this.data.selectedTagId,
        tagManageMode: false
      })
      await this.loadWorks(true)
    } catch (error) {
      if (error && error.authRequired) {
        handleAuthRequired(error.message)
        return
      }
      wx.showToast({
        title: error && error.message ? error.message : '标签删除失败',
        icon: 'none',
        duration: 2600
      })
    }
  },

  findTagById(tagId) {
    return (this.data.list.tags || []).find((tag) => tag.id === tagId) || null
  },

  handleAddTap() {
    wx.navigateTo({
      url: ADD_WORK_PAGE_URL
    })
  },

  handleWorkTap(event) {
    const workId = normalizeId(event.currentTarget.dataset.id)
    if (!workId) {
      return
    }
    const work = this.findWorkById(workId)
    if (!work) {
      return
    }
    if (work.mediaType === 'VIDEO') {
      this.openVideoEditSheet(work)
      return
    }
    this.openImageEditSheet(work)
  },

  openImageEditSheet(work) {
    const imageEditForm = buildImageEditForm(work)
    this.setData({
      imageEditSheetVisible: true,
      imageEditForm,
      imageEditFieldCounters: buildWorkFieldCounters(imageEditForm),
      imageEditSaving: false,
      imageEditErrorText: '',
      videoEditSheetVisible: false,
      videoEditForm: null,
      videoEditFieldCounters: buildWorkFieldCounters({}),
      videoEditSaving: false,
      videoDownloading: false,
      videoFrameExporting: false,
      videoEditErrorText: '',
      tagManageMode: false
    })
  },

  openVideoEditSheet(work) {
    const videoEditForm = buildVideoEditForm(work)
    this.setData({
      imageEditSheetVisible: false,
      imageEditForm: null,
      imageEditFieldCounters: buildWorkFieldCounters({}),
      imageEditSaving: false,
      imageEditErrorText: '',
      videoEditSheetVisible: true,
      videoEditForm,
      videoEditFieldCounters: buildWorkFieldCounters(videoEditForm),
      videoEditSaving: false,
      videoDownloading: false,
      videoFrameTimeMs: videoEditForm.coverFrameTimeMs,
      videoFrameTimeText: formatFrameTime(videoEditForm.coverFrameTimeMs),
      videoFrameExporting: false,
      videoEditErrorText: '',
      tagManageMode: false
    })
  },

  findWorkById(workId) {
    return (this.data.list.works || []).find((work) => work.id === workId) || null
  },

  handleEditPanelTap() {
  },

  handleCloseImageEditor() {
    if (this.data.imageEditSaving) {
      return
    }
    this.setData({
      imageEditSheetVisible: false,
      imageEditForm: null,
      imageEditFieldCounters: buildWorkFieldCounters({}),
      imageEditErrorText: ''
    })
  },

  handleCloseVideoEditor() {
    if (this.data.videoEditSaving || this.data.videoDownloading || this.data.videoFrameExporting) {
      return
    }
    this.setData({
      videoEditSheetVisible: false,
      videoEditForm: null,
      videoEditFieldCounters: buildWorkFieldCounters({}),
      videoEditErrorText: ''
    })
  },

  handleImageEditInput(event) {
    const field = event.currentTarget.dataset.field
    if (!field || !this.data.imageEditForm) {
      return
    }
    const value = event.detail.value || ''
    const imageEditForm = Object.assign({}, this.data.imageEditForm, {
      [field]: value
    })
    this.setData({
      [`imageEditForm.${field}`]: value,
      imageEditFieldCounters: buildWorkFieldCounters(imageEditForm),
      imageEditErrorText: ''
    })
  },

  handleVideoEditInput(event) {
    const field = event.currentTarget.dataset.field
    if (!field || !this.data.videoEditForm) {
      return
    }
    const value = event.detail.value || ''
    const videoEditForm = Object.assign({}, this.data.videoEditForm, {
      [field]: value
    })
    this.setData({
      [`videoEditForm.${field}`]: value,
      videoEditFieldCounters: buildWorkFieldCounters(videoEditForm),
      videoEditErrorText: ''
    })
  },

  async handleConfirmImageEdit() {
    const imageEditForm = this.data.imageEditForm
    if (!imageEditForm || this.data.imageEditSaving) {
      return
    }
    const validation = validateWorkForm(imageEditForm)
    if (!validation.valid) {
      this.setData({ imageEditErrorText: validation.message })
      return
    }
    this.setData({
      imageEditSaving: true,
      imageEditErrorText: ''
    })
    try {
      const payload = buildWorkUpdatePayload({
        title: imageEditForm.title,
        description: imageEditForm.description
      })
      const response = await request({
        url: `${WORKS_API_PREFIX}/${imageEditForm.id}`,
        method: 'PUT',
        data: payload
      })
      this.patchWorkInList(imageEditForm, response && response.work ? response.work : payload)
      wx.showToast({
        title: '作品已更新',
        icon: 'success'
      })
      this.setData({
        imageEditSheetVisible: false,
        imageEditForm: null,
        imageEditFieldCounters: buildWorkFieldCounters({}),
        imageEditSaving: false,
        imageEditErrorText: ''
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ imageEditSaving: false })
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        imageEditSaving: false,
        imageEditErrorText: error && error.message ? error.message : '作品保存失败'
      })
    }
  },

  handleVideoEditMetadata(event) {
    const detail = event.detail || {}
    if (!this.data.videoEditForm || !detail.duration) {
      return
    }
    const durationMs = Math.round(Number(detail.duration || 0) * 1000)
    const frameTimeMs = clampNumber(this.data.videoFrameTimeMs, 0, durationMs)
    const width = Math.max(0, Math.round(Number(detail.width || detail.videoWidth || this.data.videoEditForm.width || 0)))
    const height = Math.max(0, Math.round(Number(detail.height || detail.videoHeight || this.data.videoEditForm.height || 0)))
    this.setData({
      'videoEditForm.durationMs': durationMs,
      'videoEditForm.durationText': formatFrameTime(durationMs),
      'videoEditForm.coverFrameTimeMs': frameTimeMs,
      'videoEditForm.width': width,
      'videoEditForm.height': height,
      videoFrameTimeMs: frameTimeMs,
      videoFrameTimeText: formatFrameTime(frameTimeMs)
    })
  },

  handleVideoEditTimeUpdate(event) {
    if (!this.data.videoEditForm || this.data.videoFrameExporting) {
      return
    }
    const currentTime = event.detail && event.detail.currentTime
    const frameTimeMs = clampNumber(Math.round(Number(currentTime || 0) * 1000), 0, this.data.videoEditForm.durationMs || 0)
    this.setData({
      'videoEditForm.coverFrameTimeMs': frameTimeMs,
      videoFrameTimeMs: frameTimeMs,
      videoFrameTimeText: formatFrameTime(frameTimeMs)
    })
  },

  handleVideoCoverSliderChanging(event) {
    this.updateVideoCoverFrameTime(event.detail.value, false)
  },

  handleVideoCoverSliderChange(event) {
    this.updateVideoCoverFrameTime(event.detail.value, true)
  },

  updateVideoCoverFrameTime(value, syncVideo) {
    if (!this.data.videoEditForm) {
      return
    }
    const frameTimeMs = clampNumber(value, 0, this.data.videoEditForm.durationMs || 0)
    this.setData({
      'videoEditForm.coverFrameTimeMs': frameTimeMs,
      videoFrameTimeMs: frameTimeMs,
      videoFrameTimeText: formatFrameTime(frameTimeMs)
    })
    if (syncVideo && wx.createVideoContext) {
      const videoContext = wx.createVideoContext('workCoverVideo', this)
      if (videoContext && videoContext.seek) {
        videoContext.seek(frameTimeMs / 1000)
      }
    }
  },

  async handleExportVideoCover() {
    const videoEditForm = this.data.videoEditForm
    if (!videoEditForm || !videoEditForm.isVideo) {
      return
    }
    this.setData({
      'videoEditForm.coverFrameSelected': true,
      'videoEditForm.coverFrameTimeMs': this.data.videoFrameTimeMs,
      videoEditErrorText: ''
    })
    wx.showToast({
      title: '已选择当前帧',
      icon: 'success'
    })
  },

  async handleStartVideoCoverEdit() {
    const videoEditForm = this.data.videoEditForm
    if (!videoEditForm || !videoEditForm.isVideo || this.data.videoDownloading) {
      return
    }
    this.setData({
      'videoEditForm.coverEditorReady': true,
      'videoEditForm.tempFilePath': videoEditForm.tempFilePath || videoEditForm.mediaUrl || '',
      videoEditErrorText: ''
    })
  },

  async handleConfirmVideoEdit() {
    const videoEditForm = this.data.videoEditForm
    if (!videoEditForm || this.data.videoEditSaving || this.data.videoFrameExporting || this.data.videoDownloading) {
      return
    }
    const validation = validateWorkForm(videoEditForm)
    if (!validation.valid) {
      this.setData({ videoEditErrorText: validation.message })
      return
    }
    this.setData({
      videoEditSaving: true,
      videoEditErrorText: ''
    })
    try {
      const payload = buildWorkUpdatePayload({
        title: videoEditForm.title,
        description: videoEditForm.description,
        ...(videoEditForm.coverFrameSelected ? {
          coverFrameTimeMs: videoEditForm.coverFrameTimeMs,
          width: videoEditForm.width,
          height: videoEditForm.height
        } : {})
      })
      const response = await request({
        url: `${WORKS_API_PREFIX}/${videoEditForm.id}`,
        method: 'PUT',
        data: payload
      })
      this.patchWorkInList(videoEditForm, response && response.work ? response.work : payload)
      wx.showToast({
        title: '作品已更新',
        icon: 'success'
      })
      this.setData({
        videoEditSheetVisible: false,
        videoEditForm: null,
        videoEditFieldCounters: buildWorkFieldCounters({}),
        videoEditSaving: false,
        videoEditErrorText: ''
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ videoEditSaving: false })
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        videoEditSaving: false,
        videoEditErrorText: error && error.message ? error.message : '作品保存失败'
      })
    }
  },

  patchWorkInList(sourceForm, patch = {}) {
    if (!sourceForm) {
      return
    }
    const workId = sourceForm.id
    const works = (this.data.list.works || []).map((work) => {
      if (work.id !== workId) {
        return work
      }
      return Object.assign({}, work, {
        title: hasOwnField(patch, 'title') ? patch.title : sourceForm.title,
        description: hasOwnField(patch, 'description') ? patch.description : sourceForm.description,
        coverUrl: patch.coverUrl || (sourceForm.customCoverPath ? sourceForm.customCoverPath : work.coverUrl),
        hasCover: Boolean(patch.coverUrl || sourceForm.customCoverPath || work.coverUrl),
        updatedAt: patch.updatedAt || work.updatedAt
      })
    })
    this.setData({
      'list.works': works
    })
  },

  handleBatchTap() {
    this.setData({
      batchMode: !this.data.batchMode
    })
  },

  handleScrollToLower() {
    if (this.data.loading || this.data.loadingMore || !this.data.list.hasMore) {
      return
    }
    this.loadWorks(false)
  },

  handleTabTap(event) {
    const label = event.currentTarget.dataset.label
    if (label === '作品') {
      return
    }
    if (label === '档期') {
      wx.redirectTo({
        url: SCHEDULE_PAGE_URL
      })
      return
    }
    if (label === '我的') {
      wx.redirectTo({
        url: MINE_PAGE_URL
      })
      return
    }
    wx.showToast({
      title: `${label}页面接入中`,
      icon: 'none'
    })
  }
})
