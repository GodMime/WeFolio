Page({
  data: {
    type: ''
  },

  onLoad(options = {}) {
    this.setData({ type: options.type || '' })
  }
})
