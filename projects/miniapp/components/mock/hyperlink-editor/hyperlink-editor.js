const INTERNAL = 'INTERNAL_PORTFOLIO'
const EXTERNAL = 'EXTERNAL_LINK'
const TARGET_ID = 9002
const DEFAULT_PROMPT = '已复制，请在浏览器打开'
const ACTION_TYPES = [
  { value: INTERNAL, label: '内部作品集跳转', description: '跳转至本地只读演示作品集，访客可返回' },
  { value: EXTERNAL, label: '外部链接复制', description: '点击后复制链接或分享内容并展示提示语' }
]
const ICON_POSITIONS = [
  { value: 'OVERLAY', label: '图标悬浮于图片内右下角', description: '悬浮在图片内部右下角，带脉冲引导' },
  { value: 'OVERLAY_BOTTOM_CENTER', label: '图标悬浮于图片内下方', description: '悬浮在图片内部底部居中，带脉冲引导' },
  { value: 'OVERLAY_CENTER', label: '图标悬浮于图片内正中', description: '悬浮在图片内部正中，带脉冲引导' },
  { value: 'BELOW', label: '图标置于图片下方', description: '居中显示在图片下方一行' }
]

Component({
  properties: {
    config: { type: Object, value: {} },
    work: { type: Object, value: null }
  },
  data: { actionTypes: ACTION_TYPES, iconPositions: ICON_POSITIONS },
  methods: {
    emitConfig(config) { this.triggerEvent('configchange', { config }) },
    handleAction(event) {
      const actionType = event.currentTarget.dataset.value
      if (![INTERNAL, EXTERNAL].includes(actionType)) return
      const config = Object.assign({}, this.data.config, { actionType })
      if (actionType === INTERNAL) {
        delete config.externalContent
        delete config.promptText
        config.targetPortfolioId = TARGET_ID
      } else {
        delete config.targetPortfolioId
        config.externalContent = config.externalContent || ''
        config.promptText = config.promptText || DEFAULT_PROMPT
      }
      this.emitConfig(config)
    },
    handleInput(event) {
      const field = event.currentTarget.dataset.field
      if (['externalContent', 'promptText'].includes(field)) this.emitConfig(Object.assign({}, this.data.config, { [field]: event.detail.value }))
    },
    handleIconToggle(event) { this.emitConfig(Object.assign({}, this.data.config, { showClickIcon: event.detail.value })) },
    handleIconPosition(event) {
      const value = event.currentTarget.dataset.value
      if (ICON_POSITIONS.some(item => item.value === value)) this.emitConfig(Object.assign({}, this.data.config, { iconPosition: value }))
    },
    handleSelectWork() { this.triggerEvent('selectwork') },
    handleSelectTarget() { this.emitConfig(Object.assign({}, this.data.config, { targetPortfolioId: TARGET_ID })) }
  }
})
