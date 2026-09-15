const { normalizeTextBackground, BACKGROUND_MEDIA_TYPES, VIDEO_MEDIA_TYPE, buildTextBackgroundWorkPreview } = require('../../utils/portfolio-text-sections')
const TREATMENTS = [{ value: 'ORIGINAL', label: '原图直出' }, { value: 'DARK_MASK', label: '全局暗色遮罩' }, { value: 'GRADIENT', label: '固定渐进遮罩' }]
const ALIGNMENTS = [{ value: 'TOP', label: '置顶' }, { value: 'CENTER', label: '居中' }, { value: 'BOTTOM', label: '下沉' }]
Component({
  properties: {
    config: { type: Object, value: {} }, structured: { type: Boolean, value: false },
    options: { type: Array, value: [] }, loading: { type: Boolean, value: false },
    error: { type: String, value: '' }, hasMore: { type: Boolean, value: false }
  },
  data: { treatments: TREATMENTS, alignments: ALIGNMENTS, keyword: '', pickerOpen: false, selectionError: '',
    selectedWorkPreview: {}, displayOptions: [] },
  observers: {
    'config, options'(config, options) {
      this.setData({ selectedWorkPreview: buildTextBackgroundWorkPreview(config && config.backgroundWork),
        displayOptions: (Array.isArray(options) ? options : []).map(buildTextBackgroundWorkPreview) })
    }
  },
  methods: {
    handleToggle(event) {
      const source = this.properties.config
      const config = Object.assign({}, source, normalizeTextBackground(source, { structured: this.properties.structured }), { backgroundEnabled: event.detail.value })
      if (!config.backgroundEnabled) {
        this.setData({ pickerOpen: false })
        this.triggerEvent('request', { cancelCandidates: true })
        config.backgroundLoading = false
        if (source.backgroundLoading && !source.backgroundWork) {
          config.backgroundInvalid = true
          config.backgroundLoadError = '背景作品尚未加载，请重新加载当前背景'
        }
      }
      this.triggerEvent('change', config)
    },
    handleOption(event) {
      const { field, value } = event.currentTarget.dataset
      this.triggerEvent('change', Object.assign({}, this.properties.config, { [field]: value }))
    },
    handleOpenPicker() {
      // 同一入口同时负责收起，避免用户重复点击时反复重置作品列表。
      if (this.data.pickerOpen) {
        this.handleClosePicker()
        return
      }
      this.setData({ pickerOpen: true, keyword: '', selectionError: '' })
      this.triggerEvent('request', { reset: true, keyword: '' })
    },
    handleClosePicker() { this.setData({ pickerOpen: false }) },
    handleRetrySelected() {
      const config = this.properties.config
      if (!config.backgroundEnabled || !config.backgroundWorkId) return
      this.triggerEvent('request', { restore: true, workId: config.backgroundWorkId })
    },
    handleKeyword(event) { this.setData({ keyword: event.detail.value }) },
    handleSearch() { this.triggerEvent('request', { reset: true, keyword: this.data.keyword }) },
    handleMore() {
      // 由弹层实际滚动区转发触底；收起、加载中或失败时不自动重复请求。
      if (!this.data.pickerOpen || !this.properties.config.backgroundEnabled
        || this.properties.loading || this.properties.error || !this.properties.hasMore) return
      this.triggerEvent('request', { reset: false })
    },
    handleRetry() {
      if (!this.data.pickerOpen || !this.properties.config.backgroundEnabled || this.properties.loading) return
      // 续页失败保留已浏览的作品，并继续使用请求层保存的搜索条件和页码。
      if (this.properties.hasMore) this.triggerEvent('request', { reset: false })
      else this.handleSearch()
    },
    handleSelect(event) {
      const work = this.properties.options.find(item => String(item.id) === String(event.currentTarget.dataset.id))
      if (!work || !BACKGROUND_MEDIA_TYPES.includes(work.mediaType) || !work.url) {
        this.setData({ selectionError: '请选择有效的图片、动图或视频作品' }); return
      }
      // 展示副本随作品一起替换，不能留下上一作品的失效状态或资源 ID。
      const config = Object.assign({}, this.properties.config, { backgroundWorkId: work.id,
        backgroundInvalid: false, backgroundLoading: false, backgroundLoadError: '', backgroundWork: { workId: work.id, mediaType: work.mediaType,
          url: work.url, width: work.width, height: work.height,
          ...(work.mediaType === VIDEO_MEDIA_TYPE ? { posterUrl: work.posterUrl || '' } : {}) } })
      if (work.memberUserId) config.backgroundMemberUserId = work.memberUserId
      this.triggerEvent('request', { cancelCandidates: true })
      this.triggerEvent('change', config)
      this.setData({ pickerOpen: false, selectionError: '' })
    }
  }
})
