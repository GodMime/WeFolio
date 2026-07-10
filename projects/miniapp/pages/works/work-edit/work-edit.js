const { request } = require('../../../utils/request')
const { normalizeId } = require('../../../utils/id')
const { handleMaintainerAuthRequired, hasLocalToken } = require('../../../utils/session')
const {
  buildWorkFieldCounters,
  buildWorkUpdatePayload,
  normalizeWorkDetail,
  validateWorkForm
} = require('../utils/works')

const WORKS_PAGE_URL = '/pages/works/works'

function buildEmptyForm() {
  return {
    title: '',
    description: '',
    tags: [],
    tagInput: ''
  }
}

Page({
  data: {
    workId: null,
    loading: true,
    saving: false,
    deleting: false,
    errorMessage: '',
    detail: normalizeWorkDetail({}),
    form: buildEmptyForm(),
    fieldCounters: buildWorkFieldCounters(buildEmptyForm())
  },

  onLoad(options = {}) {
    const workId = normalizeId(options.workId)
    if (!workId) {
      this.setData({
        loading: false,
        errorMessage: '作品不存在'
      })
      return
    }
    this.setData({ workId })
    this.bootstrap()
  },

  bootstrap() {
    if (!hasLocalToken()) {
      this.redirectToLogin()
      return
    }
    this.loadDetail()
  },

  redirectToLogin() {
    wx.redirectTo({
      url: '/pages/login/login'
    })
  },

  async loadDetail() {
    this.setData({
      loading: true,
      errorMessage: ''
    })
    try {
      const response = await request({
        url: `/api/mine/works/${this.data.workId}`
      })
      const detail = normalizeWorkDetail(response)
      const form = {
        title: detail.work.title,
        description: detail.work.description,
        tags: detail.work.tags.map((item) => item.name),
        tagInput: ''
      }
      this.setData({
        detail,
        form,
        fieldCounters: buildWorkFieldCounters(form),
        loading: false
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ loading: false })
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({
        loading: false,
        errorMessage: error && error.message ? error.message : '作品加载失败'
      })
    }
  },

  handleInput(event) {
    const field = event.currentTarget.dataset.field
    if (!field) {
      return
    }
    const form = Object.assign({}, this.data.form, {
      [field]: event.detail.value || ''
    })
    this.setData({
      [`form.${field}`]: event.detail.value || '',
      fieldCounters: buildWorkFieldCounters(form)
    })
  },

  handleTagInput(event) {
    this.setData({
      'form.tagInput': event.detail.value || ''
    })
  },

  handleAddTag() {
    const tag = String(this.data.form.tagInput || '').trim()
    if (!tag) {
      return
    }
    const tags = Array.from(new Set(this.data.form.tags.concat(tag)))
    this.setData({
      'form.tags': tags,
      'form.tagInput': ''
    })
  },

  handleRemoveTag(event) {
    const index = Number(event.currentTarget.dataset.index)
    if (!Number.isFinite(index)) {
      return
    }
    const tags = this.data.form.tags.slice()
    tags.splice(index, 1)
    this.setData({
      'form.tags': tags
    })
  },

  async handleSave() {
    const payload = buildWorkUpdatePayload(this.data.form)
    const validation = validateWorkForm(payload)
    if (!validation.valid) {
      wx.showToast({
        title: validation.message,
        icon: 'none'
      })
      return
    }
    this.setData({ saving: true })
    try {
      const response = await request({
        url: `/api/mine/works/${this.data.workId}`,
        method: 'PUT',
        data: payload
      })
      const detail = normalizeWorkDetail(response)
      this.setData({
        detail,
        saving: false
      })
      wx.showToast({
        title: '已保存',
        icon: 'success'
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ saving: false })
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({ saving: false })
      wx.showToast({
        title: error && error.message ? error.message : '保存失败',
        icon: 'none'
      })
    }
  },

  async handleDeleteTap() {
    this.setData({ deleting: true })
    try {
      const check = await request({
        url: `/api/mine/works/${this.data.workId}/delete-check`
      })
      if (!check.canDelete) {
        this.setData({ deleting: false })
        wx.showModal({
          title: '无法删除',
          content: check.message || '作品已被引用',
          showCancel: false,
          confirmText: '知道了'
        })
        return
      }
      const confirmed = await this.confirmDelete(check.message || '确认删除该作品？')
      if (!confirmed) {
        this.setData({ deleting: false })
        return
      }
      await request({
        url: `/api/mine/works/delete/${this.data.workId}`,
        method: 'POST'
      })
      wx.showToast({
        title: '已删除',
        icon: 'success'
      })
      wx.redirectTo({
        url: WORKS_PAGE_URL
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ deleting: false })
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({ deleting: false })
      wx.showToast({
        title: error && error.message ? error.message : '删除失败',
        icon: 'none'
      })
    }
  },

  confirmDelete(content) {
    return new Promise((resolve) => {
      wx.showModal({
        title: '删除作品',
        content,
        confirmText: '删除',
        confirmColor: '#a9354f',
        success(response) {
          resolve(Boolean(response.confirm))
        },
        fail() {
          resolve(false)
        }
      })
    })
  }
})
