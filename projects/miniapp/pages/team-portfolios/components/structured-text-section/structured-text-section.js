const {
  buildStructuredTextPresentation,
  buildTextBackgroundPresentation,
  buildTextBackgroundFrameStyle,
  handleTextBackgroundLoad
} = require('../../utils/portfolio-text-sections')

Component({
  properties: {
    config: { type: Object, value: {} },
    themeMode: { type: String, value: 'light' },
    repairMode: { type: Boolean, value: false }
  },
  data: {
    blocks: [],
    background: { enabled: false, imageUrl: '', invalid: false },
    frameStyle: '',
    imageFailed: false,
    showRepairHint: false
  },
  observers: {
    'config, themeMode, repairMode'() { this.updatePresentation() }
  },
  methods: {
    updatePresentation() {
      const config = this.properties.config || {}
      const background = buildTextBackgroundPresentation(config)
      const imageFailed = this.data.background.imageUrl === background.imageUrl && this.data.imageFailed
      this.setData({
        blocks: buildStructuredTextPresentation(config, this.properties.themeMode).blocks.map(block => ({
          ...block,
          // 列表允许相同文案，展示键不能使用文案本身。
          listItems: block.type === 'LIST' && Array.isArray(block.items)
            ? block.items.map((content, index) => ({ key: `${block.blockKey}-${index}`, content })) : []
        })),
        background,
        // 使用最小高度而非固定高度，长文案和留白可以继续撑开背景。
        frameStyle: buildTextBackgroundFrameStyle(background, this._textBackgroundLayout),
        imageFailed: Boolean(imageFailed),
        showRepairHint: this.properties.repairMode && background.enabled && (background.invalid || imageFailed)
      })
    },
    handleBackgroundLoad(event) { handleTextBackgroundLoad(this, event) },
    handleBackgroundError() {
      this.setData({ imageFailed: true, showRepairHint: this.properties.repairMode && this.data.background.enabled })
    }
  }
})
