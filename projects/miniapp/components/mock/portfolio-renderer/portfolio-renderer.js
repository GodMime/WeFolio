Component({
  properties: {
    active: {type:Boolean,value:true},
    copiedField: {type:String,value:''},
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

  data: {imageFailed:false,singleWorkAnimationFailed:false},
  observers: {component() {this.setData({imageFailed:false,singleWorkAnimationFailed:false})}},

  methods: {
    handleCopy(event) { this.triggerEvent('copy',Object.assign({componentKey:this.data.component.componentKey},event.detail || {})) },
    handleHyperlink() { this.triggerEvent('hyperlink',{componentKey:this.data.component.componentKey}) },
    handleGridMeasure(event) { this.triggerEvent('gridmeasure',Object.assign({componentKey:this.data.component.componentKey},event.detail || {})) },
    handleImageError() { this.setData({imageFailed:true}) },
    handleImageRetry() { this.setData({imageFailed:false}) },
    handleSingleWorkAnimationError() { this.setData({singleWorkAnimationFailed:true}) },
    handleProfileQr() { this.triggerEvent('previewqr',{qrUrl:this.data.component.profile.wechatQrUrl}) },

    handleDisplayTagTap(event) {
      const dataset = event.currentTarget && event.currentTarget.dataset
      this.triggerEvent('displaytagtap', {
        componentKey: this.data.component.componentKey || '',
        groupKey: dataset && dataset.groupKey
      })
    },

    handleWorkTap(event) {
      const dataset = event.currentTarget && event.currentTarget.dataset
      this.triggerEvent('worktap', Object.assign({componentKey:this.data.component.componentKey}, dataset || {},event.detail || {}))
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
    }
  }
})
