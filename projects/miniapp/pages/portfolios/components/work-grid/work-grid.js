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
    displayTags: {
      type: Array,
      value: []
    },
    activeGroup: {
      type: Object,
      value: {}
    },
    showTitle: {
      type: Boolean,
      value: true
    },
    showDescription: {
      type: Boolean,
      value: false
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
