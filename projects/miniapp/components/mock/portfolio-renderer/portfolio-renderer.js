Component({
  properties: {
    component: {
      type: Object,
      value: {}
    },
    activeSingleWorkVideoKey: {
      type: String,
      value: ''
    },
    themeMode: {
      type: String,
      value: 'light'
    }
  },

  methods: {
    handleDisplayTagTap(event) {
      const dataset = event.currentTarget && event.currentTarget.dataset
      this.triggerEvent('displaytagtap', {
        componentKey: this.data.component.componentKey || '',
        groupKey: dataset && dataset.groupKey
      })
    },

    handleWorkTap(event) {
      const dataset = event.currentTarget && event.currentTarget.dataset
      this.triggerEvent('worktap', Object.assign({}, dataset || {}))
    },

    handleSingleWorkTap(event) {
      const dataset = event.currentTarget && event.currentTarget.dataset
      this.triggerEvent('singleworktap', Object.assign({
        componentKey: this.data.component.componentKey || ''
      }, dataset || {}))
    },

    handleSingleWorkVideoError() {
      this.triggerEvent('singleworkvideoerror')
    },

    handlePreviewQr() {
      const qrContact = this.data.component.qrContact || {}
      this.triggerEvent('previewqr', { qrUrl: qrContact.qrUrl || '' })
    },

    handleLockedAction() {
      this.triggerEvent('lockedaction')
    }
  }
})
