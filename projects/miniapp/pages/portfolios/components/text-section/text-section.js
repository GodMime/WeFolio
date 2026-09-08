const { buildTextBackgroundPresentation, buildTextBackgroundFrameStyle, handleTextBackgroundLoad } = require('../../utils/portfolio-text-sections')
const { buildTextColorStyle } = require('../../../../utils/portfolio-text-color')
Component({
  properties: {
    repairMode: { type: Boolean, value: false },
    themeMode: {
      type: String,
      value: 'light'
    },
    textSection: {
      type: Object,
      value: {}
    }
  },
  data: { background: {}, backgroundFailed: false, textColorStyle: '', frameStyle: '' },
  observers: { textSection() { this.updatePresentation() } },
  methods: {
    updatePresentation() {
      const section = this.properties.textSection || {}
      const background = buildTextBackgroundPresentation(section)
      this.setData({ background, backgroundFailed: false,
        frameStyle: buildTextBackgroundFrameStyle(background, this._textBackgroundLayout),
        textColorStyle: buildTextColorStyle(section.color),
        verticalClass: section.verticalAlignment === 'TOP' ? 'text-vertical-top' : section.verticalAlignment === 'BOTTOM' ? 'text-vertical-bottom' : 'text-vertical-center' })
    },
    handleBackgroundError() { this.setData({ backgroundFailed: true }) },
    handleBackgroundLoad(event) { handleTextBackgroundLoad(this, event) }
  }
})
