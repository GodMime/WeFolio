const { normalizeTextBackground } = require('../../utils/portfolio-text-sections')
const TREATMENTS = [{ value: 'ORIGINAL', label: '原图直出' }, { value: 'DARK_MASK', label: '全局暗色遮罩' }, { value: 'GRADIENT', label: '固定渐进遮罩' }]
const ALIGNMENTS = [{ value: 'TOP', label: '置顶' }, { value: 'CENTER', label: '居中' }, { value: 'BOTTOM', label: '下沉' }]
Component({
  properties: {
    config: { type: Object, value: {} }, structured: { type: Boolean, value: false },
    options: { type: Array, value: [] }, loading: { type: Boolean, value: false },
    error: { type: String, value: '' }, hasMore: { type: Boolean, value: false },
    members: { type: Array, value: [] }, memberUserId: { type: Number, value: 0 },
    membersLoading: { type: Boolean, value: false }
  },
  data: { treatments: TREATMENTS, alignments: ALIGNMENTS, keyword: '', pickerOpen: false, selectionError: '' },
  methods: {
    handleToggle(event) {
      if (!event.detail.value) this.setData({ pickerOpen: false })
      const config = Object.assign({}, this.properties.config, normalizeTextBackground(this.properties.config, { team: true, structured: this.properties.structured }), { backgroundEnabled: event.detail.value })
      if (!event.detail.value) config.backgroundLoading = false
      this.triggerEvent('change', config)
      if (!event.detail.value) this.triggerEvent('request', { cancelCandidates: true })
      if (event.detail.value && config.backgroundWorkId && !config.backgroundWork) this.handleRetrySelection()
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
    handleRetrySelection() { this.triggerEvent('request', { restoreSelection: true }) },
    handleMember(event) {
      const memberUserId = Number(event.currentTarget.dataset.id)
      if (!this.properties.members.some(member => Number(member.memberUserId) === memberUserId)) return
      // 浏览成员和已选作品是独立状态；只有选择新作品时才替换持久化归属。
      this.setData({ keyword: '', selectionError: '' })
      this.triggerEvent('request', { reset: true, keyword: '', memberUserId })
    },
    handleKeyword(event) { this.setData({ keyword: event.detail.value }) },
    handleSearch() { this.triggerEvent('request', { reset: true, keyword: this.data.keyword }) },
    handleMore() { if (!this.properties.loading && this.properties.hasMore) this.triggerEvent('request', { reset: false }) },
    handleSelect(event) {
      const work = this.properties.options.find(item => String(item.id) === String(event.currentTarget.dataset.id))
      if (!work || !Number.isInteger(Number(work.memberUserId)) || Number(work.memberUserId) <= 0 || !['IMAGE', 'ANIMATION'].includes(work.mediaType) || !work.url) {
        this.setData({ selectionError: '请选择有效的图片或动图作品' }); return
      }
      // 展示副本随作品一起替换，不能留下上一作品的失效状态或资源 ID。
      const config = Object.assign({}, this.properties.config, { backgroundWorkId: work.id,
        backgroundInvalid: false, backgroundLoading: false, backgroundLoadError: '', backgroundWork: { workId: work.id, mediaType: work.mediaType,
          url: work.url, width: work.width, height: work.height } })
      config.backgroundMemberUserId = Number(work.memberUserId)
      this.triggerEvent('change', config)
      this.triggerEvent('request', { cancelCandidates: true })
      this.setData({ pickerOpen: false, selectionError: '' })
    }
  }
})
