Component({
  properties: {
    visible: { type: Boolean, value: false },
    disabled: { type: Boolean, value: false }
  },
  methods: {
    noop() {},
    handleClose() { if (!this.data.disabled) this.triggerEvent('close', {}) },
    handleQrCode() { if (!this.data.disabled) this.triggerEvent('qrcode', {}) },
    handleTimeline() { if (!this.data.disabled) this.triggerEvent('timeline', {}) },
    handleFriend() {
      if (this.data.disabled) return
      const pages = getCurrentPages()
      const current = pages[pages.length - 1]
      if (current && current.androidShare) current.androidShare(0)
    }
  }
})
