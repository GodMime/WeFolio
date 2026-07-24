Component({
  properties: {
    componentKey: {
      type: String,
      value: ''
    },
    displayTags: {
      type: Array,
      value: []
    },
    activeGroup: {
      type: Object,
      value: {}
    },
    switching: {
      type: Boolean,
      value: false
    }
  },

  methods: {
    handleDisplayTagTap(event) {
      const dataset = event.currentTarget && event.currentTarget.dataset
      this.triggerEvent('displaytagtap', {
        componentKey: this.data.componentKey,
        groupKey: dataset && dataset.groupKey
      })
    },

    handleWorkTap(event) {
      const dataset = event.currentTarget && event.currentTarget.dataset
      this.triggerEvent('worktap', Object.assign({}, dataset || {}))
    }
  }
})
