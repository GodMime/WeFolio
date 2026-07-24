Component({
  properties: {
    profile: {
      type: Object,
      value: {}
    }
  },

  methods: {
    handlePreviewQr() {
      const profile = this.data.profile || {}
      this.triggerEvent('previewqr', { qrUrl: profile.wechatQrUrl || '' })
    }
  }
})
