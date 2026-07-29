Component({
  properties: {
    themeMode: {
      type: String,
      value: 'light'
    },
    componentKey: {
      type: String,
      value: ''
    },
    work: {
      type: Object,
      value: null
    },
    showTitle: {
      type: Boolean,
      value: true
    },
    showDescription: {
      type: Boolean,
      value: false
    },
    activeVideoKey: {
      type: String,
      value: ''
    },
    repairMode: {
      type: Boolean,
      value: false
    }
  },

  methods: {
    handleSingleWorkTap(event) {
      const dataset = event.currentTarget && event.currentTarget.dataset
      this.triggerEvent('singleworktap', Object.assign({
        componentKey: this.data.componentKey
      }, dataset || {}))
    },

    handleVideoError(event) {
      this.triggerEvent('videoerror', {
        componentKey: this.data.componentKey,
        error: event.detail
      })
    },

    pauseVideo() {
      if (!this.data.componentKey || this.data.componentKey !== this.data.activeVideoKey) {
        return
      }
      const videoContext = wx.createVideoContext &&
        wx.createVideoContext(`singleWorkVideo-${this.data.componentKey}`, this)
      if (videoContext && typeof videoContext.pause === 'function') {
        videoContext.pause()
      }
    }
  }
})
