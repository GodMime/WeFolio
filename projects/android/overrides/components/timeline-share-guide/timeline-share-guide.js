Component({
  properties: {
    visible: { type: Boolean, value: false },
    back: { type: Boolean, value: false }
  },
  methods: {
    noop() {},
    handleBack() { this.triggerEvent('back', {}) },
    handleClose() { this.triggerEvent('close', {}) },
    handleShare() {
      const pages = getCurrentPages()
      const current = pages[pages.length - 1]
      if (current && current.androidShare) current.androidShare(1)
    }
  }
})
