const { buildContactInfoStyle } = require('../../utils/portfolio-contact-info')

const COPY_FIELDS = ['contactPhone', 'contactWechat']
const COPY_FEEDBACK_DURATION_MS = 2000
const COPY_SUCCESS_MESSAGE = '已复制'
const COPY_FAILURE_MESSAGE = '复制失败，请重试'

Component({
  properties: { config: { type: Object, value: {} }, themeMode: { type: String, value: 'light' } },
  data: {
    spacingStyle: '',
    borderStyle: '',
    copiedFields: { contactPhone: false, contactWechat: false }
  },
  observers: {
    'config, themeMode'() {
      this.clearCopyFeedback()
      this.updateBorderStyle()
    }
  },
  lifetimes: {
    attached() {
      this._copyDetached = false
      this.clearCopyFeedback()
      this.updateBorderStyle()
    },
    detached() {
      this._copyDetached = true
      this.clearCopyFeedback(false)
    }
  },
  methods: {
    updateBorderStyle() { this.setData(buildContactInfoStyle(this.properties.config, this.properties.themeMode)) },

    /** 更换联系方式或卸载组件时，清理反馈并使在途复制回调失效。 */
    clearCopyFeedback(resetState = true) {
      Object.values(this._copyTimers || {}).forEach(timer => clearTimeout(timer))
      this._copyTimers = {}
      this._copyRequests = {}
      if (resetState && !this._copyDetached) {
        this.setData({ copiedFields: { contactPhone: false, contactWechat: false } })
      }
    },

    handleCopy(event) {
      if (this._copyDetached) return
      const field = event.currentTarget.dataset.field
      if (!COPY_FIELDS.includes(field)) return
      const data = String((this.properties.config || {})[field] || '')
      if (!data) return

      // 两个按钮分别计时；同一按钮的旧请求不能覆盖新一次复制的结果。
      const request = {}
      this._copyRequests = this._copyRequests || {}
      this._copyTimers = this._copyTimers || {}
      this._copyRequests[field] = request
      if (this._copyTimers[field] !== undefined) clearTimeout(this._copyTimers[field])
      delete this._copyTimers[field]
      this.setData({ [`copiedFields.${field}`]: false })
      const isCurrentRequest = () => !this._copyDetached
        && this._copyRequests[field] === request
        && String((this.properties.config || {})[field] || '') === data

      wx.setClipboardData({
        data,
        success: () => {
          if (!isCurrentRequest()) return
          this.setData({ [`copiedFields.${field}`]: true })
          this._copyTimers[field] = setTimeout(() => {
            if (!isCurrentRequest()) return
            delete this._copyTimers[field]
            this.setData({ [`copiedFields.${field}`]: false })
          }, COPY_FEEDBACK_DURATION_MS)
          wx.showToast({ title: COPY_SUCCESS_MESSAGE, icon: 'success' })
        },
        fail: () => {
          if (!isCurrentRequest()) return
          wx.showToast({ title: COPY_FAILURE_MESSAGE, icon: 'none' })
        }
      })
    }
  }
})
