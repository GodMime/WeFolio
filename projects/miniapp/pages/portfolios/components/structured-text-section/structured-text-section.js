const { buildStructuredTextPresentation, buildTextBackgroundPresentation, buildTextBackgroundFrameStyle, handleTextBackgroundLoad } = require('../../utils/portfolio-text-sections')
const { textBackgroundVideo } = require('../../utils/portfolio-text-background-video')
Component({
  lifetimes: textBackgroundVideo.lifetimes,
  pageLifetimes: textBackgroundVideo.pageLifetimes,
  properties: {
    ...textBackgroundVideo.properties,
    structuredTextSection: { type: Object, value: {} }, themeMode: { type: String, value: 'light' },
    repairMode: { type: Boolean, value: false }
  },
  data: { ...textBackgroundVideo.data, presentation: { blocks: [] }, background: {}, backgroundFailed: false, backgroundLoaded: false, frameStyle: '' },
  observers: { ...textBackgroundVideo.observers, 'structuredTextSection, themeMode': function () { this.refreshPresentation() } },
  methods: {
    ...textBackgroundVideo.methods,
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
        backgroundFailed: false, backgroundLoaded: false }, () => this.syncBackgroundVideo())
    },
    handleBackgroundError() { this.setData({ backgroundFailed: true }) },
    handleBackgroundLoad(event) {
      handleTextBackgroundLoad(this, event)
      this.setData({ backgroundLoaded: true, backgroundFailed: false })
    }
  }
})
