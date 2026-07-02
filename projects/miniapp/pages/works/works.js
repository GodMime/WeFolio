const { request } = require('../../utils/request')
const { normalizeId } = require('../../utils/id')
const { handleAuthRequired, hasLocalToken } = require('../../utils/session')
const {
  buildThumbFileName,
  createChooseCoverImageOptions,
  prepareLocalCoverUploadFile,
  uploadToCos
} = require('../../utils/work-upload')
const { calculateFileSha256: calculateLocalFileSha256 } = require('../../utils/sha256')
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
const PORTFOLIOS_PAGE_URL = '/pages/portfolios/portfolios'
const WORK_TAGS_API_URL = '/api/mine/works/tags'
const WORK_TAG_DELETE_API_PREFIX = '/api/mine/works/tags/delete'
const WORKS_API_PREFIX = '/api/mine/works'
const WORK_DELETE_API_PREFIX = '/api/mine/works/delete'
const WORK_BATCH_DELETE_CHECK_API_URL = '/api/mine/works/delete-check'
const WORK_BATCH_DELETE_API_URL = '/api/mine/works/delete'
const WORK_SORT_ITEMS_API_URL = '/api/mine/works/sort-items'
const WORK_SORT_API_URL = '/api/mine/works/sort'
const EDIT_COVER_CLIENT_PREFIX = 'edit-work'
const SWIPE_REVEAL_THRESHOLD = -32
const SWIPE_CLOSE_THRESHOLD = 24
const SWIPE_VERTICAL_TOLERANCE = 48
const DELETE_CONFIRM_COLOR = '#a9354f'
const SORT_SCOPE_ALL = 'ALL'
const SORT_SCOPE_TAG = 'TAG'
const SORT_ORDER_STEP = 1000
const SORT_DRAG_SCALE = 1.015
const COVER_EDIT_MODE_FRAME = 'frame'
const COVER_EDIT_MODE_LOCAL = 'local'
const DEFAULT_COVER_MIME_TYPE = 'image/jpeg'
const DEFAULT_COVER_FILE_NAME = 'cover.jpg'
const LOCAL_COVER_CLIENT_SUFFIX = '-local-cover'

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

function fileNameFromPath(filePath) {
  const value = String(filePath || '').trim()
  const segments = value.split('/')
  return segments[segments.length - 1] || DEFAULT_COVER_FILE_NAME
}

function mimeTypeFromFileName(fileName) {
  const value = String(fileName || '').toLowerCase()
  if (value.endsWith('.png')) {
    return 'image/png'
  }
  if (value.endsWith('.webp')) {
    return 'image/webp'
  }
  if (value.endsWith('.gif')) {
    return 'image/gif'
  }
  return DEFAULT_COVER_MIME_TYPE
}

function normalizeChosenCoverFile(response = {}) {
  const files = Array.isArray(response.tempFiles) ? response.tempFiles : []
  const file = files[0] || {}
  const filePath = String(file.tempFilePath || file.path || '').trim()
  const fileName = String(file.name || fileNameFromPath(filePath)).trim() || DEFAULT_COVER_FILE_NAME
  return {
    filePath,
    fileName,
    mimeType: String(file.mimeType || file.type || mimeTypeFromFileName(fileName)).trim() || DEFAULT_COVER_MIME_TYPE,
    fileSize: Math.max(0, Number(file.size || 0)),
    width: Math.max(0, Math.round(Number(file.width || 0))),
    height: Math.max(0, Math.round(Number(file.height || 0)))
  }
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
    coverEditMode: '',
    durationMs,
    durationText: work.durationText || formatFrameTime(durationMs),
    coverFrameTimeMs: 0,
    coverFrameSelected: false,
    localCoverPath: '',
    localCoverFileName: '',
    localCoverMimeType: DEFAULT_COVER_MIME_TYPE,
    localCoverSize: 0,
    localCoverWidth: 0,
    localCoverHeight: 0,
    localCoverSha256: '',
    localCoverIdempotencyKey: ''
  })
}

function hasOwnField(object, field) {
  return Object.prototype.hasOwnProperty.call(object || {}, field)
}

function buildWorkDeleteBlockedMessage(checkResult = {}, work = {}) {
  if (checkResult.message) {
    return checkResult.message
  }
  const referenceCount = Number(checkResult.referenceCount || work.referenceCount || 0)
  if (referenceCount > 0) {
    return `作品已被 ${referenceCount} 个作品集引用，请先从作品集中移除`
  }
  return '作品已被作品集引用，请先从作品集中移除'
}

function hasSelectedWork(selectedIds = [], workId) {
  return selectedIds.some((id) => id === workId)
}

function getVisibleWorkIds(works = []) {
  return works
    .map((work) => work && work.id)
    .filter(Boolean)
}

function mergeSelectedWorkIds(selectedIds = [], workIds = []) {
  return workIds.reduce((result, workId) => (
    hasSelectedWork(result, workId) ? result : result.concat(workId)
  ), selectedIds.slice())
}

function buildBatchSelectAllText(works = [], selectedIds = []) {
  const visibleWorkIds = getVisibleWorkIds(works)
  if (!visibleWorkIds.length) {
    return '全选'
  }
  return visibleWorkIds.every((workId) => hasSelectedWork(selectedIds, workId)) ? '取消全选' : '全选'
}

function applyWorkSelections(works = [], selectedIds = []) {
  return works.map((work) => Object.assign({}, work, {
    selected: hasSelectedWork(selectedIds, work.id)
  }))
}

function normalizeSortWorks(raw = {}) {
  return normalizeWorkList({
    works: Array.isArray(raw.works) ? raw.works : []
  }).works
}

function readTouchClientY(event = {}) {
  const touch = (event.touches && event.touches[0]) || (event.changedTouches && event.changedTouches[0]) || {}
  const clientY = Number(touch.clientY)
  return Number.isFinite(clientY) ? clientY : null
}

function resolveSortTargetIndex(rects = [], clientY) {
  if (!Array.isArray(rects) || !rects.length || !Number.isFinite(clientY)) {
    return -1
  }
  for (let index = 0; index < rects.length; index += 1) {
    const rect = rects[index] || {}
    const top = Number(rect.top)
    const height = Number(rect.height)
    if (!Number.isFinite(top) || !Number.isFinite(height)) {
      continue
    }
    if (clientY < top + height / 2) {
      return index
    }
  }
  return rects.length - 1
}

function buildSortDragStyle(offsetY = 0) {
  const roundedOffset = Math.round(Number(offsetY) || 0)
  return `transform: translate3d(0, ${roundedOffset}px, 0) scale(${SORT_DRAG_SCALE}); transition: transform 80ms linear, box-shadow 160ms ease, border-color 160ms ease, background 160ms ease; z-index: 2;`
}

Page({
  requestSeq: 0,
  uploadTasks: {},
  sortDragRects: [],

  data: {
    loading: true,
    loadingMore: false,
    errorMessage: '',
    keyword: '',
    selectedTagId: null,
    batchMode: false,
    selectedWorkIds: [],
    batchSelectedCountText: '0 已选',
    batchSelectAllText: '全选',
    batchDeleting: false,
    revealedWorkId: null,
    workTouchStart: null,
    deletingWorkId: null,
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
    videoCoverUploadProgress: 0,
    videoEditErrorText: '',
    videoPreviewVisible: false,
    videoPreview: null,
    sortMode: false,
    sortScope: SORT_SCOPE_ALL,
    sortTagId: null,
    sortTitle: '调整作品顺序',
    sortWorks: [],
    sortLoading: false,
    sortSaving: false,
    sortErrorText: '',
    sortDraggingWorkId: null,
    sortDragStartY: null,
    sortDragOffsetY: 0,
    sortDragStyle: '',
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
      normalized.works = applyWorkSelections(normalized.works, this.data.selectedWorkIds)
      if (!reset) {
        normalized.works = applyWorkSelections(currentList.works.concat(normalized.works), this.data.selectedWorkIds)
        normalized.empty = normalized.works.length === 0
      }
      this.setData({
        list: normalized,
        loading: false,
        loadingMore: false,
        errorMessage: '',
        batchSelectAllText: buildBatchSelectAllText(normalized.works, this.data.selectedWorkIds)
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

  handleClearSearch() {
    if (!this.data.keyword) {
      return
    }
    this.setData({
      keyword: ''
    })
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
    if (this.data.sortMode) {
      return
    }
    const tagId = normalizeId(event.currentTarget.dataset.id)
    if (this.data.tagManageMode && tagId) {
      this.openTagDialog('edit', this.findTagById(tagId))
      return
    }
    this.setData({
      selectedTagId: tagId || null,
      tagManageMode: false,
      batchMode: false,
      selectedWorkIds: [],
      batchSelectedCountText: '0 已选',
      batchSelectAllText: '全选',
      revealedWorkId: null
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

  handleWorkTouchStart(event) {
    if (this.data.deletingWorkId || this.data.batchMode || this.data.sortMode) {
      this.setData({ workTouchStart: null })
      return
    }
    const workId = normalizeId(event.currentTarget.dataset.id)
    const work = this.findWorkById(workId)
    if (!work) {
      this.setData({ workTouchStart: null })
      return
    }
    const touch = (event.touches && event.touches[0]) || {}
    this.setData({
      workTouchStart: {
        workId,
        x: touch.clientX || 0,
        y: touch.clientY || 0
      }
    })
  },

  handleWorkTouchMove() {
  },

  handleWorkTouchEnd(event) {
    const start = this.data.workTouchStart
    if (!start || !start.workId) {
      return
    }
    const touch = (event.changedTouches && event.changedTouches[0]) || {}
    const deltaX = (touch.clientX || start.x) - start.x
    const deltaY = Math.abs((touch.clientY || start.y) - start.y)
    if (deltaY <= SWIPE_VERTICAL_TOLERANCE && deltaX < SWIPE_REVEAL_THRESHOLD) {
      this.setData({
        revealedWorkId: start.workId,
        workTouchStart: null
      })
      return
    }
    if (deltaX > SWIPE_CLOSE_THRESHOLD || Math.abs(deltaX) < 8) {
      this.setData({
        revealedWorkId: null,
        workTouchStart: null
      })
      return
    }
    this.setData({ workTouchStart: null })
  },

  handleWorkTouchCancel() {
    this.setData({ workTouchStart: null })
  },

  handleWorkTap(event) {
    const workId = normalizeId(event.currentTarget.dataset.id)
    if (!workId) {
      return
    }
    if (this.data.batchMode) {
      this.toggleWorkSelection(workId)
      return
    }
    if (this.data.sortMode) {
      return
    }
    if (this.data.revealedWorkId === workId) {
      this.setData({ revealedWorkId: null })
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

  async handleDeleteWorkTap(event) {
    const workId = normalizeId(event.currentTarget.dataset.id)
    const work = this.findWorkById(workId)
    if (!work || this.data.deletingWorkId) {
      return
    }
    try {
      const checkResult = await request({
        url: `${WORKS_API_PREFIX}/${workId}/delete-check`
      })
      if (!checkResult || !checkResult.canDelete) {
        this.setData({ revealedWorkId: null })
        wx.showModal({
          title: '无法删除',
          content: buildWorkDeleteBlockedMessage(checkResult, work),
          showCancel: false,
          confirmText: '知道了'
        })
        return
      }
      wx.showModal({
        title: '删除作品',
        content: `确认删除“${work.title || '该作品'}”？删除后会从素材库移除。`,
        confirmText: '删除',
        confirmColor: DELETE_CONFIRM_COLOR,
        success: async (result) => {
          if (!result.confirm) {
            this.setData({ revealedWorkId: null })
            return
          }
          this.setData({ deletingWorkId: workId })
          try {
            await request({
              url: `${WORK_DELETE_API_PREFIX}/${workId}`,
              method: 'POST'
            })
            this.setData({
              deletingWorkId: null,
              revealedWorkId: null
            })
            wx.showToast({
              title: '作品已删除',
              icon: 'success'
            })
            await this.loadWorks(true)
          } catch (error) {
            if (error && error.authRequired) {
              this.setData({
                deletingWorkId: null,
                revealedWorkId: null
              })
              handleAuthRequired(error.message)
              return
            }
            this.setData({
              deletingWorkId: null,
              revealedWorkId: null
            })
            wx.showToast({
              title: error && error.message ? error.message : '作品删除失败',
              icon: 'none',
              duration: 2600
            })
          }
        }
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleAuthRequired(error.message)
        return
      }
      this.setData({ revealedWorkId: null })
      wx.showToast({
        title: error && error.message ? error.message : '删除检查失败',
        icon: 'none',
        duration: 2600
      })
    }
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
      videoCoverUploadProgress: 0,
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
      videoCoverUploadProgress: 0,
      videoEditErrorText: '',
      tagManageMode: false
    })
  },

  findWorkById(workId) {
    return (this.data.list.works || []).find((work) => work.id === workId) || null
  },

  handleEditPanelTap() {
  },

  handleVideoPreviewPanelTap() {
  },

  handlePlayVideoTap(event) {
    if (this.data.batchMode || this.data.sortMode) {
      return
    }
    const workId = normalizeId(event.currentTarget.dataset.id)
    const work = this.findWorkById(workId)
    if (!work || work.mediaType !== 'VIDEO') {
      return
    }
    const src = work.mediaUrl || ''
    if (!src) {
      wx.showToast({
        title: '视频地址缺失',
        icon: 'none'
      })
      return
    }
    this.setData({
      videoPreviewVisible: true,
      videoPreview: {
        src,
        poster: work.coverUrl || '',
        title: work.title || '视频作品'
      },
      revealedWorkId: null,
      tagManageMode: false
    })
  },

  handleCloseVideoPreview() {
    this.setData({
      videoPreviewVisible: false,
      videoPreview: null
    })
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
      videoEditErrorText: '',
      videoCoverUploadProgress: 0
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

  chooseCoverImage(options) {
    return new Promise((resolve, reject) => {
      wx.chooseMedia(Object.assign({}, options, {
        success: resolve,
        fail: reject
      }))
    })
  },

  async handleChooseVideoCoverUpload() {
    const videoEditForm = this.data.videoEditForm
    if (!videoEditForm || this.data.videoEditSaving || this.data.videoFrameExporting || this.data.videoDownloading) {
      return
    }
    try {
      const response = await this.chooseCoverImage(createChooseCoverImageOptions())
      const coverFile = normalizeChosenCoverFile(response)
      if (!coverFile.filePath) {
        wx.showToast({
          title: '封面文件缺失',
          icon: 'none'
        })
        return
      }
      this.setData({
        'videoEditForm.coverEditMode': COVER_EDIT_MODE_LOCAL,
        'videoEditForm.coverEditorReady': false,
        'videoEditForm.coverFrameSelected': false,
        'videoEditForm.localCoverPath': coverFile.filePath,
        'videoEditForm.localCoverFileName': coverFile.fileName,
        'videoEditForm.localCoverMimeType': coverFile.mimeType,
        'videoEditForm.localCoverSize': coverFile.fileSize,
        'videoEditForm.localCoverWidth': coverFile.width,
        'videoEditForm.localCoverHeight': coverFile.height,
        'videoEditForm.localCoverSha256': '',
        'videoEditForm.localCoverIdempotencyKey': `cover-ticket-${videoEditForm.clientId}-${Date.now()}`,
        videoCoverUploadProgress: 0,
        videoEditErrorText: ''
      })
    } catch (error) {
      if (error && /cancel/.test(error.errMsg || error.message || '')) {
        return
      }
      wx.showToast({
        title: error && error.message ? error.message : '选择封面失败',
        icon: 'none'
      })
    }
  },

  async handleExportVideoCover() {
    const videoEditForm = this.data.videoEditForm
    if (!videoEditForm || !videoEditForm.isVideo || videoEditForm.coverEditMode === COVER_EDIT_MODE_LOCAL) {
      return
    }
    this.setData({
      'videoEditForm.coverEditMode': COVER_EDIT_MODE_FRAME,
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
      'videoEditForm.coverEditMode': COVER_EDIT_MODE_FRAME,
      'videoEditForm.coverEditorReady': true,
      'videoEditForm.tempFilePath': videoEditForm.tempFilePath || videoEditForm.mediaUrl || '',
      'videoEditForm.localCoverPath': '',
      'videoEditForm.localCoverFileName': '',
      'videoEditForm.localCoverSha256': '',
      'videoEditForm.localCoverIdempotencyKey': '',
      videoCoverUploadProgress: 0,
      videoEditErrorText: ''
    })
  },

  async uploadVideoCoverOnConfirm(videoEditForm) {
    if (!videoEditForm.localCoverPath) {
      throw new Error('封面文件缺失')
    }
    this.setData({
      videoCoverUploadProgress: 1,
      videoEditErrorText: ''
    })
    const preparedCover = await prepareLocalCoverUploadFile(videoEditForm.localCoverPath)
    const coverSha256 = await calculateLocalFileSha256(preparedCover.filePath)
    const compressed = preparedCover.filePath !== videoEditForm.localCoverPath
    const coverFileName = compressed
      ? buildThumbFileName(videoEditForm.originalFileName || videoEditForm.fileName)
      : (videoEditForm.localCoverFileName || DEFAULT_COVER_FILE_NAME)
    const coverMimeType = compressed
      ? DEFAULT_COVER_MIME_TYPE
      : (videoEditForm.localCoverMimeType || DEFAULT_COVER_MIME_TYPE)
    const ticket = await request({
      url: `${WORKS_API_PREFIX}/${videoEditForm.id}/cover-upload-ticket`,
      method: 'POST',
      data: {
        clientId: `${videoEditForm.clientId}${LOCAL_COVER_CLIENT_SUFFIX}`,
        fileName: coverFileName,
        mimeType: coverMimeType,
        fileSize: preparedCover.fileSize,
        sha256: coverSha256,
        width: videoEditForm.localCoverWidth,
        height: videoEditForm.localCoverHeight,
        idempotencyKey: videoEditForm.localCoverIdempotencyKey
      }
    })
    const uploadFile = {
      id: `${videoEditForm.clientId}${LOCAL_COVER_CLIENT_SUFFIX}`,
      mediaType: 'IMAGE',
      tempFilePath: preparedCover.filePath
    }
    await uploadToCos(uploadFile, ticket, {
      onTask: (target, task) => {
        this.uploadTasks[target.id] = task
      },
      onProgress: (target, progress) => {
        this.setData({
          videoCoverUploadProgress: progress.progress || 0
        })
      }
    })
    delete this.uploadTasks[uploadFile.id]
    this.setData({
      'videoEditForm.localCoverSha256': coverSha256,
      videoCoverUploadProgress: 100
    })
    return ticket
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
      const coverTicket = videoEditForm.coverEditMode === COVER_EDIT_MODE_LOCAL && videoEditForm.localCoverPath
        ? await this.uploadVideoCoverOnConfirm(videoEditForm)
        : null
      const shouldUpdateCoverFrame = videoEditForm.coverEditMode !== COVER_EDIT_MODE_LOCAL && videoEditForm.coverFrameSelected
      const payload = buildWorkUpdatePayload({
        title: videoEditForm.title,
        description: videoEditForm.description,
        ...(shouldUpdateCoverFrame ? {
          coverFrameTimeMs: videoEditForm.coverFrameTimeMs,
          width: videoEditForm.width,
          height: videoEditForm.height
        } : {}),
        ...(coverTicket && coverTicket.taskId ? {
          coverTaskId: coverTicket.taskId
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
        videoCoverUploadProgress: 0,
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
        videoCoverUploadProgress: 0,
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
    if (this.data.sortMode) {
      return
    }
    const nextBatchMode = !this.data.batchMode
    this.setData({
      batchMode: nextBatchMode,
      revealedWorkId: null,
      selectedWorkIds: nextBatchMode ? this.data.selectedWorkIds : [],
      batchSelectedCountText: nextBatchMode ? this.data.batchSelectedCountText : '0 已选',
      batchSelectAllText: nextBatchMode
        ? buildBatchSelectAllText(this.data.list.works, this.data.selectedWorkIds)
        : '全选',
      'list.works': nextBatchMode
        ? applyWorkSelections(this.data.list.works, this.data.selectedWorkIds)
        : applyWorkSelections(this.data.list.works, [])
    })
  },

  toggleWorkSelection(workId) {
    const selectedWorkIds = hasSelectedWork(this.data.selectedWorkIds, workId)
      ? this.data.selectedWorkIds.filter((id) => id !== workId)
      : this.data.selectedWorkIds.concat(workId)
    this.setSelectedWorkIds(selectedWorkIds)
  },

  setSelectedWorkIds(selectedWorkIds = []) {
    this.setData({
      selectedWorkIds,
      batchSelectedCountText: `${selectedWorkIds.length} 已选`,
      batchSelectAllText: buildBatchSelectAllText(this.data.list.works, selectedWorkIds),
      'list.works': applyWorkSelections(this.data.list.works, selectedWorkIds)
    })
  },

  handleSelectAllBatchTap() {
    if (!this.data.batchMode) {
      return
    }
    const visibleWorkIds = getVisibleWorkIds(this.data.list.works)
    if (!visibleWorkIds.length) {
      return
    }
    const allVisibleSelected = visibleWorkIds.every((workId) => hasSelectedWork(this.data.selectedWorkIds, workId))
    const selectedWorkIds = allVisibleSelected
      ? this.data.selectedWorkIds.filter((workId) => !hasSelectedWork(visibleWorkIds, workId))
      : mergeSelectedWorkIds(this.data.selectedWorkIds, visibleWorkIds)
    this.setSelectedWorkIds(selectedWorkIds)
  },

  handleBatchPortfolioTap() {
    wx.showToast({
      title: '作品集编辑器接入中',
      icon: 'none'
    })
  },

  async handleBatchDeleteTap() {
    if (this.data.batchDeleting) {
      return
    }
    if (!this.data.selectedWorkIds.length) {
      wx.showToast({
        title: '请选择作品',
        icon: 'none'
      })
      return
    }
    this.setData({ batchDeleting: true })
    try {
      const checkResult = await request({
        url: WORK_BATCH_DELETE_CHECK_API_URL,
        method: 'POST',
        data: {
          workIds: this.data.selectedWorkIds
        }
      })
      const items = Array.isArray(checkResult && checkResult.items) ? checkResult.items : []
      const deletableIds = items
        .filter((item) => item && item.canDelete)
        .map((item) => normalizeId(item.workId))
        .filter(Boolean)
      if (!deletableIds.length) {
        this.setData({ batchDeleting: false })
        const firstBlocked = items.find((item) => item && !item.canDelete) || {}
        wx.showModal({
          title: '无法删除',
          content: firstBlocked.message || '所选作品已被作品集引用，请先移除引用',
          showCancel: false,
          confirmText: '知道了'
        })
        return
      }
      const blockedCount = Math.max(0, Number(checkResult.blockedCount || (items.length - deletableIds.length)))
      wx.showModal({
        title: blockedCount > 0 ? '部分作品无法删除' : '删除作品',
        content: blockedCount > 0
          ? `可删除 ${deletableIds.length} 个，${blockedCount} 个已被作品集引用，将保留。`
          : `确认删除 ${deletableIds.length} 个作品？`,
        confirmText: blockedCount > 0 ? '删除可删项' : '删除',
        confirmColor: DELETE_CONFIRM_COLOR,
        success: async (result) => {
          if (!result.confirm) {
            this.setData({ batchDeleting: false })
            return
          }
          try {
            const response = await request({
              url: WORK_BATCH_DELETE_API_URL,
              method: 'POST',
              data: {
                workIds: deletableIds
              }
            })
            const successCount = Number(response && response.successCount ? response.successCount : deletableIds.length)
            wx.showToast({
              title: `已删除 ${successCount} 个作品`,
              icon: 'success'
            })
            this.setData({
              batchMode: false,
              batchDeleting: false
            })
            this.setSelectedWorkIds([])
            await this.loadWorks(true)
          } catch (error) {
            if (error && error.authRequired) {
              this.setData({ batchDeleting: false })
              handleAuthRequired(error.message)
              return
            }
            this.setData({ batchDeleting: false })
            wx.showToast({
              title: error && error.message ? error.message : '作品删除失败',
              icon: 'none',
              duration: 2600
            })
          }
        }
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ batchDeleting: false })
        handleAuthRequired(error.message)
        return
      }
      this.setData({ batchDeleting: false })
      wx.showToast({
        title: error && error.message ? error.message : '删除检查失败',
        icon: 'none',
        duration: 2600
      })
    }
  },

  getSelectedTagName() {
    const tag = this.findTagById(this.data.selectedTagId)
    return tag && tag.name ? tag.name : '当前标签'
  },

  async handleOpenSortMode() {
    if (String(this.data.keyword || '').trim()) {
      wx.showToast({
        title: '清空搜索后调整排序',
        icon: 'none'
      })
      return
    }
    const sortScope = this.data.selectedTagId ? SORT_SCOPE_TAG : SORT_SCOPE_ALL
    const sortTagId = sortScope === SORT_SCOPE_TAG ? this.data.selectedTagId : null
    this.setData({
      sortMode: true,
      sortScope,
      sortTagId,
      sortTitle: sortScope === SORT_SCOPE_TAG ? `调整「${this.getSelectedTagName()}」顺序` : '调整全部作品顺序',
      sortWorks: [],
      sortLoading: true,
      sortSaving: false,
      sortErrorText: '',
      sortDraggingWorkId: null,
      sortDragStartY: null,
      sortDragOffsetY: 0,
      sortDragStyle: '',
      batchMode: false,
      revealedWorkId: null
    })
    this.sortDragRects = []
    this.setSelectedWorkIds([])
    try {
      const response = await request({
        url: WORK_SORT_ITEMS_API_URL,
        data: sortTagId ? {
          scope: sortScope,
          tagId: sortTagId
        } : {
          scope: sortScope
        }
      })
      this.setData({
        sortWorks: normalizeSortWorks(response),
        sortLoading: false,
        sortErrorText: ''
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ sortLoading: false })
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        sortLoading: false,
        sortErrorText: error && error.message ? error.message : '排序列表加载失败'
      })
    }
  },

  handleCloseSortMode() {
    if (this.data.sortSaving) {
      return
    }
    this.setData({
      sortMode: false,
      sortWorks: [],
      sortLoading: false,
      sortSaving: false,
      sortErrorText: '',
      sortDraggingWorkId: null,
      sortDragStartY: null,
      sortDragOffsetY: 0,
      sortDragStyle: ''
    })
    this.sortDragRects = []
  },

  moveSortWork(fromIndex, toIndex) {
    const works = (this.data.sortWorks || []).slice()
    if (fromIndex < 0 || fromIndex >= works.length || toIndex < 0 || toIndex >= works.length) {
      return null
    }
    const [item] = works.splice(fromIndex, 1)
    works.splice(toIndex, 0, item)
    this.setData({ sortWorks: works })
    return works
  },

  captureSortDragRects() {
    if (!wx.createSelectorQuery) {
      this.sortDragRects = []
      return
    }
    try {
      wx.createSelectorQuery()
        .selectAll('.sort-work-row')
        .boundingClientRect((rects) => {
          this.sortDragRects = Array.isArray(rects) ? rects : []
        })
        .exec()
    } catch (error) {
      this.sortDragRects = []
    }
  },

  handleSortDragStart(event) {
    if (this.data.sortSaving || this.data.sortLoading) {
      return
    }
    const index = Number(event.currentTarget.dataset.index)
    const work = (this.data.sortWorks || [])[index]
    if (!work) {
      return
    }
    const clientY = readTouchClientY(event)
    this.setData({
      sortDraggingWorkId: work.id,
      sortDragStartY: clientY,
      sortDragOffsetY: 0,
      sortDragStyle: buildSortDragStyle(0)
    })
    this.captureSortDragRects()
  },

  handleSortDragMove(event) {
    const draggingWorkId = this.data.sortDraggingWorkId
    if (!draggingWorkId || this.data.sortSaving || this.data.sortLoading) {
      return
    }
    const works = this.data.sortWorks || []
    const fromIndex = works.findIndex((work) => work.id === draggingWorkId)
    if (fromIndex < 0) {
      return
    }
    const clientY = readTouchClientY(event)
    if (clientY !== null && this.data.sortDragStartY !== null) {
      const offsetY = clientY - this.data.sortDragStartY
      this.setData({
        sortDragOffsetY: offsetY,
        sortDragStyle: buildSortDragStyle(offsetY)
      })
    }
    let toIndex = resolveSortTargetIndex(this.sortDragRects, clientY)
    if (toIndex < 0) {
      toIndex = Number(event.currentTarget.dataset.index)
    }
    if (!Number.isInteger(toIndex) || toIndex === fromIndex) {
      return
    }
    this.moveSortWork(fromIndex, toIndex)
  },

  clearSortDragState() {
    if (!this.data.sortDraggingWorkId) {
      return
    }
    this.sortDragRects = []
    this.setData({
      sortDraggingWorkId: null,
      sortDragStartY: null,
      sortDragOffsetY: 0,
      sortDragStyle: ''
    })
  },

  handleSortDragEnd() {
    this.clearSortDragState()
  },

  handleSortDragCancel() {
    this.clearSortDragState()
  },

  async handleSaveSort() {
    if (this.data.sortSaving || this.data.sortLoading) {
      return
    }
    const items = (this.data.sortWorks || []).map((work, index) => ({
      workId: work.id,
      sortOrder: (index + 1) * SORT_ORDER_STEP
    }))
    if (!items.length) {
      wx.showToast({
        title: '暂无可排序作品',
        icon: 'none'
      })
      return
    }
    this.setData({
      sortSaving: true,
      sortErrorText: ''
    })
    try {
      await request({
        url: WORK_SORT_API_URL,
        method: 'POST',
        data: {
          scope: this.data.sortScope,
          tagId: this.data.sortTagId || undefined,
          items
        }
      })
      wx.showToast({
        title: '顺序已保存',
        icon: 'success'
      })
      this.setData({
        sortMode: false,
        sortWorks: [],
        sortSaving: false,
        sortErrorText: '',
        sortDraggingWorkId: null,
        sortDragStartY: null,
        sortDragOffsetY: 0,
        sortDragStyle: ''
      })
      this.sortDragRects = []
      await this.loadWorks(true)
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ sortSaving: false })
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        sortSaving: false,
        sortErrorText: error && error.message ? error.message : '顺序保存失败'
      })
    }
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
    if (label === '作品集') {
      wx.redirectTo({
        url: PORTFOLIOS_PAGE_URL
      })
      return
    }
    wx.showToast({
      title: `${label}页面接入中`,
      icon: 'none'
    })
  }
})
