const DEFAULT_COVER = 'https://cdn2.we-folio.dingchenyong.top/system/default-audio-cover-v1-200kb.png'
Component({
  properties: {
    displayStyle: { type: String, value: 'DISC' },
    title: { type: String, value: '背景音频' },
    playing: { type: Boolean, value: false },
    sample: { type: Boolean, value: false },
    coverUrl: {
      type: String, value: '',
      observer(value) { this.setData({ coverSource: value || DEFAULT_COVER, coverFailed: false }) }
    }
  },
  data: { coverSource: DEFAULT_COVER, coverFailed: false },
  methods: {
    handleTap() { if (!this.data.sample) this.triggerEvent('toggle') },
    handleCoverError() {
      if (this.data.coverSource !== DEFAULT_COVER) this.setData({ coverSource: DEFAULT_COVER })
      else this.setData({ coverFailed: true })
    }
  }
})
