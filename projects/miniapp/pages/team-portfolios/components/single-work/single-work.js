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

function resolveCurrentSelection(selectedWork, workId) {
  const selectedWorkId = positiveId(selectedWork && selectedWork.workId)
  if (!workId) return null
  if (selectedWorkId === workId) return Object.assign({}, selectedWork)
  return { workId, status: 'unavailable' }
}

function syncDraft(component, requestSources) {
  const draft = normalizeSingleWorkConfig(component.properties.config || {})
  const currentSelection = component.data.currentSelection
  const currentSelectionWorkId = positiveId(currentSelection && currentSelection.workId)
  const patch = {
    draft,
    currentSelection: currentSelectionWorkId === draft.workId
      ? currentSelection
      : resolveCurrentSelection(component.properties.selectedWork, draft.workId),
    errorMessage: ''
  }
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
            memberUserId,
            selectedWorkId: positiveId(this.data.draft.workId)
          })
        }
      }
    },
    works: { type: Array, value: [] },
    selectedWork: {
      type: Object,
      value: null,
      observer(value) {
        const workId = positiveId(this.data.draft && this.data.draft.workId)
        const savedWorkId = positiveId(this.properties.config && this.properties.config.workId)
        const selectedWorkId = positiveId(value && value.workId)
        const currentSelectionWorkId = positiveId(
          this.data.currentSelection && this.data.currentSelection.workId
        )
        if (workId !== savedWorkId) {
          return
        }
        if (currentSelectionWorkId === workId && selectedWorkId !== workId) {
          return
        }
        this.setData({ currentSelection: resolveCurrentSelection(value, workId) })
      }
    },
    singleWorkLoading: { type: Boolean, value: false },
    singleWorkLoadingMore: { type: Boolean, value: false },
    singleWorkHasMore: { type: Boolean, value: false },
    singleWorkLoadMoreError: { type: String, value: '' },
    work: {
      type: Object,
      value: null,
      observer() {
        this.setData({ animationLoadFailed: false })
      }
    },
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
    restoredMemberUserId: null,
    currentSelection: null,
    animationLoadFailed: false
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
        currentSelection: null,
        errorMessage: ''
      })
      this.triggerEvent('memberchange', {
        portfolioId: this.properties.portfolioId,
        memberUserId,
        selectedWorkId: null
      })
    },
    selectWork(event) {
      const item = event.currentTarget.dataset.item || {}
      this.setData({
        draft: Object.assign({}, this.data.draft, { workId: positiveId(item.workId) }),
        currentSelection: Object.assign({}, item),
        errorMessage: ''
      })
    },
    handleWorksScrollToLower() {
      if (
        this.properties.singleWorkLoading ||
        this.properties.singleWorkLoadingMore ||
        !this.properties.singleWorkHasMore
      ) {
        return
      }
      this.triggerEvent('loadmore', { memberUserId: this.data.draft.memberUserId })
    },
    retryLoadMore() {
      this.triggerEvent('retryloadmore', { memberUserId: this.data.draft.memberUserId })
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
      if (!['IMAGE', 'ANIMATION'].includes(work.mediaType)) return
      this.triggerEvent('preview', {
        componentKey: this.properties.componentKey,
        work: work.mediaType === 'ANIMATION' && this.data.animationLoadFailed
          ? Object.assign({}, work, { mediaUrl: work.coverUrl || '' })
          : work
      })
    },
    handleAnimationLoadError() {
      this.setData({ animationLoadFailed: true })
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
