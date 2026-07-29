Component({
  properties: {
    themeMode: {
      type: String,
      value: 'light'
    },
    qrContact: {
      type: Object,
      value: {}
    }
  },

  methods: {
    handlePreviewQr() {
      const qrContact = this.data.qrContact || {}
      this.triggerEvent('previewqr', { qrUrl: qrContact.qrUrl || '' })
    }
  }
})
