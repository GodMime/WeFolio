Component({
  properties: {
    visible: {
      type: Boolean,
      value: false
    },
    disabled: {
      type: Boolean,
      value: false
    }
  },

  methods: {
    noop() {},

    handleClose() {
      if (this.data.disabled) {
        return
      }
      this.triggerEvent('close', {})
    },

    handleTimeline() {
      if (this.data.disabled) {
        return
      }
      this.triggerEvent('timeline', {})
    }
  }
})
