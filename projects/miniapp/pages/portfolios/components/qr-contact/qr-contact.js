Component({
  properties: {
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
