const { buildStructuredTextPresentation, buildTextBackgroundPresentation, buildTextBackgroundFrameStyle, handleTextBackgroundLoad } = require('../../utils/portfolio-text-sections')
Component({
  properties: {
    structuredTextSection: { type: Object, value: {} }, themeMode: { type: String, value: 'light' },
    repairMode: { type: Boolean, value: false }
  },
  data: { presentation: { blocks: [] }, background: {}, backgroundFailed: false, backgroundLoaded: false, frameStyle: '' },
  observers: { 'structuredTextSection, themeMode': function () { this.refreshPresentation() } },
  methods: {
    refreshPresentation() {
      const config = this.properties.structuredTextSection || {}
      const background = buildTextBackgroundPresentation(config)
      const presentation = buildStructuredTextPresentation(config, this.properties.themeMode)
      presentation.blocks = presentation.blocks.map(block => Object.assign({}, block, {
        listItems: block.type === 'LIST' && Array.isArray(block.items)
          ? block.items.map((content, index) => ({ key: `${block.blockKey}-${index}`, content })) : []
      }))
      this.setData({ presentation,
        background, frameStyle: buildTextBackgroundFrameStyle(background, this._textBackgroundLayout),
        backgroundFailed: false, backgroundLoaded: false })
    },
    handleBackgroundError() { this.setData({ backgroundFailed: true }) },
    handleBackgroundLoad(event) {
      handleTextBackgroundLoad(this, event)
      this.setData({ backgroundLoaded: true, backgroundFailed: false })
    }
  }
})
