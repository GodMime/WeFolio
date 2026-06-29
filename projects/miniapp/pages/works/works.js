const { request } = require('../../utils/request')
const { normalizeId } = require('../../utils/id')
const { handleAuthRequired, hasLocalToken } = require('../../utils/session')
const {
  DEFAULT_WORK_TAG_COLOR,
  WORK_TAG_COLOR_OPTIONS,
  WORK_TAG_MAX_COUNT,
  buildWorkTagDeleteBlockedMessage,
  buildWorkTagPayload,
  createWorkTagForm,
  normalizeWorkList,
  validateWorkTagForm
} = require('../../utils/works')

const ADD_WORK_PAGE_URL = '/pages/work-add/work-add'
const EDIT_WORK_PAGE_URL = '/pages/work-edit/work-edit'
const MINE_PAGE_URL = '/pages/index/index'
const SCHEDULE_PAGE_URL = '/pages/schedule/schedule'
const WORK_TAGS_API_URL = '/api/mine/works/tags'
const WORK_TAG_DELETE_API_PREFIX = '/api/mine/works/tags/delete'

Page({
  requestSeq: 0,

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
    wx.navigateTo({
      url: `${EDIT_WORK_PAGE_URL}?workId=${workId}`
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
