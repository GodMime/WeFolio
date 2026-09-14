const { buildTextBackgroundPresentation, buildTextBackgroundFrameStyle, handleTextBackgroundLoad } = require('../../utils/portfolio-text-sections')
const { buildTextColorStyle } = require('../../../../utils/portfolio-text-color')
const { buildPortfolioTextLineHeightStyle } = require('../../../../utils/portfolio-text-typography')
const { textBackgroundVideo } = require('../../utils/portfolio-text-background-video')
Component({
  lifetimes: textBackgroundVideo.lifetimes,
  pageLifetimes: textBackgroundVideo.pageLifetimes,
  properties: {
    ...textBackgroundVideo.properties,
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
  data: { ...textBackgroundVideo.data, background: {}, backgroundFailed: false, textColorStyle: '', frameStyle: '', lineHeightStyle: '' },
  observers: { ...textBackgroundVideo.observers, textSection() { this.updatePresentation() } },
  methods: {
    ...textBackgroundVideo.methods,
    updatePresentation() {
      const section = this.properties.textSection || {}
      const background = buildTextBackgroundPresentation(section)
      this.setData({ background, backgroundFailed: false,
        frameStyle: buildTextBackgroundFrameStyle(background, this._textBackgroundLayout),
        textColorStyle: buildTextColorStyle(section.color),
        lineHeightStyle: buildPortfolioTextLineHeightStyle(section.lineHeight),
        verticalClass: section.verticalAlignment === 'TOP' ? 'text-vertical-top' : section.verticalAlignment === 'BOTTOM' ? 'text-vertical-bottom' : 'text-vertical-center' }, () => this.syncBackgroundVideo())
    },
    handleBackgroundError() { this.setData({ backgroundFailed: true }) },
    handleBackgroundLoad(event) { handleTextBackgroundLoad(this, event) }
  }
})
