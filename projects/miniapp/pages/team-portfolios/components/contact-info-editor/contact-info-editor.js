const { loadContactProfile } = require('../../utils/portfolio-component-platform')
const { BORDER_NUMBER_FIELDS, normalizeContactInfo, validateContactInfo } = require('../../utils/portfolio-contact-info')
const CONTACT_FIELDS = ['contactPhone', 'contactWechat']
const BORDER_STEP = 1
Component({
  properties: { visible: { type: Boolean, value: false }, config: { type: Object, value: {} }, isNew: { type: Boolean, value: false }, backgroundColor: { type: String, value: '#FFFFFF' } },
  data: { draft: normalizeContactInfo(), loading: false, error: '', scrollTop: 0,
    borderFields: Object.entries(BORDER_NUMBER_FIELDS).map(([field, settings]) => ({ field, ...settings })), borderStep: BORDER_STEP },
  observers: { visible(visible) {
    this._session = (this._session || 0) + 1
    if (visible) {
      this._editing = true
      this._fieldRevisions = { contactPhone: 0, contactWechat: 0 }
      this.setData({ draft: normalizeContactInfo(this.properties.config), error: '', loading: false, previewThemeMode: this.previewThemeMode() })
      this.setData({ scrollTop: this._scrollTop || 0 }, () => { this._scrollTop = 0; this.setData({ scrollTop: 0 }) })
      if (this.properties.isNew) this.handleRefill()
    } else this._editing = false
  } },
  lifetimes: { detached() { this._editing = false; this._session = (this._session || 0) + 1 } },
  methods: {
    noop() {},
    handleScroll(event) { this._scrollTop = event.detail.scrollTop },
    previewThemeMode() { const color = this.properties.backgroundColor || '#FFFFFF'; const channels = [1, 3, 5].map(index => parseInt(color.slice(index, index + 2), 16)); return channels[0] * 0.299 + channels[1] * 0.587 + channels[2] * 0.114 < 128 ? 'dark' : 'light' },
    async handleRefill() {
      if (!this.properties.visible) return
      const session = this._session
      const requestRevision = this._refillRequestRevision = (this._refillRequestRevision || 0) + 1
      const fieldRevisions = { ...this._fieldRevisions }
      const isCurrentRequest = () => session === this._session && requestRevision === this._refillRequestRevision && this.properties.visible
      this.setData({ loading: true, error: '' })
      try {
        const profile = await loadContactProfile()
        if (!isCurrentRequest()) return
        const normalized = normalizeContactInfo(profile), draft = { ...this.data.draft }
        // 只保护本次读取开始后编辑过的字段，未编辑的另一项仍可自动带入。
        CONTACT_FIELDS.forEach(field => {
          if ((fieldRevisions[field] || 0) === ((this._fieldRevisions || {})[field] || 0)) draft[field] = normalized[field]
        })
        this.setData({ draft })
      } catch (error) {
        if (isCurrentRequest()) this.setData({ error: error.message || '资料读取失败，可直接填写联系信息' })
      } finally {
        if (isCurrentRequest()) this.setData({ loading: false })
      }
    },
    handleInput(event) {
      const field = event.currentTarget.dataset.field
      if (!CONTACT_FIELDS.includes(field)) return
      this._fieldRevisions = this._fieldRevisions || {}
      this._fieldRevisions[field] = (this._fieldRevisions[field] || 0) + 1
      this.setData({ draft: { ...this.data.draft, [field]: event.detail.value }, error: '' })
    },
    handleBorderToggle(event) {
      if (!this.properties.visible || !this._editing) return
      this.updateBorderDraft({ contactBorder: event.detail.value === true })
    },
    updateBorderDraft(values) {
      const draft = { ...this.data.draft, ...values }
      this.setData({ draft, error: validateContactInfo(draft) })
    },
    handleBorderNumberInput(event) {
      const field = event.currentTarget.dataset.field
      if (!this.properties.visible || !this._editing || !this.data.draft.contactBorder || !BORDER_NUMBER_FIELDS[field]) return
      // 保留空输入和非法值，让完成按钮明确拒绝，避免悄悄保存旧数字。
      const raw = event.detail.value
      const numeric = Number(raw)
      const value = (typeof raw === 'number' || (typeof raw === 'string' && raw.trim())) && Number.isFinite(numeric) ? numeric : raw
      this.updateBorderDraft({ [field]: value })
    },
    handleBorderStep(event) {
      const { field, delta } = event.currentTarget.dataset, settings = BORDER_NUMBER_FIELDS[field]
      if (!this.properties.visible || !this._editing || !this.data.draft.contactBorder || !settings || Math.abs(Number(delta)) !== BORDER_STEP) return
      const current = normalizeContactInfo(this.data.draft)[field]
      this.updateBorderDraft({ [field]: Math.max(settings.min, Math.min(settings.max, current + Number(delta))) })
    },
    handleBorderColor(event) {
      if (!this.properties.visible || !this._editing || !this.data.draft.contactBorder) return
      this.updateBorderDraft({ contactBorderColor: event.detail.color })
    },
    handleCancel() {
      // 先使请求失效，避免父页面更新 visible 前的迟到响应重新写入面板。
      this._session = (this._session || 0) + 1
      this._editing = false
      this.triggerEvent('cancel')
    },
    handleConfirm() { if (!this.properties.visible || !this._editing || this.data.loading) return; const error = validateContactInfo(this.data.draft, true); if (error) { this.setData({ error }); return } this._editing = false; this.triggerEvent('confirm', normalizeContactInfo(this.data.draft)) }
  }
})
