Component({
  properties: {
    tabs: {
      type: Array,
      value: []
    }
  },

  methods: {
    handleTabTap(event) {
      const url = event.currentTarget.dataset.url
      const active = event.currentTarget.dataset.active
      if (!url || active) {
        return
      }
      wx.redirectTo({ url })
    }
  }
})
