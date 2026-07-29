function positiveId(value) {
  const id = Number(value)
  return Number.isInteger(id) && id > 0 ? id : null
}

function normalizeSingleWorkConfig(config = {}) {
  return {
    memberUserId: positiveId(config.memberUserId),
    workId: positiveId(config.workId),
    showTitle: typeof config.showTitle === 'boolean' ? config.showTitle : true,
    showDescription: typeof config.showDescription === 'boolean' ? config.showDescription : false
  }
}

function validateSingleWorkConfig(config = {}) {
  const normalized = normalizeSingleWorkConfig(config)
  if (!normalized.memberUserId) return { valid: false, message: '请先选择团队成员' }
  if (!normalized.workId) return { valid: false, message: '请选择一个作品' }
  return { valid: true, message: '' }
}

function syncDraft(component, requestSources) {
  const draft = normalizeSingleWorkConfig(component.properties.config || {})
  const patch = { draft, errorMessage: '' }
  if (requestSources) patch.restoredMemberUserId = null
  component.setData(patch)
  if (requestSources) {
    component.triggerEvent('loadmembers', { portfolioId: component.properties.portfolioId })
  }
}

Component({
  properties: {
    themeMode: { type: String, value: 'light' },
    portfolioId: { type: Number, value: 0 },
    componentKey: { type: String, value: '' },
    config: {
      type: Object,
      value: {},
      observer() {
        syncDraft(this, false)
      }
    },
    members: {
      type: Array,
      value: [],
      observer(value) {
        const memberUserId = positiveId(this.data.draft.memberUserId)
        if (
          this.properties.editMode &&
          Array.isArray(value) &&
          value.length &&
          memberUserId &&
          this.data.restoredMemberUserId !== memberUserId
        ) {
          this.setData({ restoredMemberUserId: memberUserId })
          this.triggerEvent('memberchange', {
            portfolioId: this.properties.portfolioId,
            memberUserId
          })
        }
      }
    },
    works: { type: Array, value: [] },
    work: { type: Object, value: null },
    showTitle: { type: Boolean, value: true },
    showDescription: { type: Boolean, value: false },
    activeVideoKey: { type: String, value: '' },
    editMode: {
      type: Boolean,
      value: false,
      observer(value, oldValue) {
        if (value !== oldValue) syncDraft(this, value === true)
      }
    }
  },
  data: {
    draft: normalizeSingleWorkConfig(),
    errorMessage: '',
    restoredMemberUserId: null
  },
  methods: {
    beginEdit() {
      syncDraft(this, true)
    },
    selectMember(event) {
      const memberUserId = positiveId(event.currentTarget.dataset.id)
      this.setData({
        draft: Object.assign({}, this.data.draft, { memberUserId, workId: null }),
        restoredMemberUserId: memberUserId,
        errorMessage: ''
      })
      this.triggerEvent('memberchange', {
        portfolioId: this.properties.portfolioId,
        memberUserId
      })
    },
    selectWork(event) {
      const item = event.currentTarget.dataset.item || {}
      this.setData({
        draft: Object.assign({}, this.data.draft, { workId: positiveId(item.workId) }),
        errorMessage: ''
      })
    },
    handleShowTitleChange(event) {
      this.setData({
        draft: Object.assign({}, this.data.draft, {
          showTitle: Boolean(event.detail && event.detail.value)
        })
      })
    },
    handleShowDescriptionChange(event) {
      this.setData({
        draft: Object.assign({}, this.data.draft, {
          showDescription: Boolean(event.detail && event.detail.value)
        })
      })
    },
    saveEdit() {
      const config = normalizeSingleWorkConfig(this.data.draft)
      const validation = validateSingleWorkConfig(config)
      if (!validation.valid) {
        this.setData({ errorMessage: validation.message })
        return
      }
      this.triggerEvent('save', { config })
    },
    cancelEdit() {
      syncDraft(this, false)
      this.triggerEvent('cancel')
    },
    handleMediaTap() {
      const work = this.properties.work
      if (!work) return
      if (work.mediaType === 'VIDEO') {
        this.triggerEvent('activate', {
          componentKey: this.properties.componentKey,
          work
        })
        return
      }
      this.triggerEvent('preview', {
        componentKey: this.properties.componentKey,
        work
      })
    },
    handleVideoError(event) {
      this.triggerEvent('videoerror', {
        componentKey: this.properties.componentKey,
        error: event.detail
      })
    },
    pauseVideo() {
      if (this.properties.activeVideoKey !== this.properties.componentKey) return
      const context = wx.createVideoContext &&
        wx.createVideoContext(`teamSingleWorkVideo-${this.properties.componentKey}`, this)
      if (context && typeof context.pause === 'function') context.pause()
    }
  },
  lifetimes: {
    attached() {
      syncDraft(this, this.properties.editMode === true)
    }
  }
})

module.exports = {
  normalizeSingleWorkConfig,
  validateSingleWorkConfig
}
