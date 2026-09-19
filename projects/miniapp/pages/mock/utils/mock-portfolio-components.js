const { selectMockWorksFor } = require('./mock-work-media')
const MOCK_COMPONENT_TYPES = ['CAROUSEL', 'PROFILE', 'SCHEDULE_QUERY', 'WORK_GRID', 'WORK_LIST', 'SINGLE_WORK', 'QR_CONTACT', 'CONTACT_FORM', 'TEXT_SECTION', 'DIVIDER', 'VIDEO_CAROUSEL', 'STRUCTURED_TEXT_SECTION', 'TEXT_GRID', 'CONTACT_INFO', 'HYPERLINK']
const NEW_DEFAULTS = {
  VIDEO_CAROUSEL: { title: '视频作品', workIds: [107, 110, 111], showComponentTitle: true, showTitle: true, showDescription: false, displayStyle: 'STACKED', showSwipeHint: true },
  STRUCTURED_TEXT_SECTION: { blocks: [{blockKey:'intro',type:'TITLE',content:'用影像记录故事',fontFamily:'SYSTEM',fontSizeRpx:40,fontWeight:'BOLD',color:'AUTO',alignment:'LEFT',marginTopRpx:0,marginBottomRpx:16,lineHeight:1.5}], backgroundEnabled:false,backgroundTreatment:'GRADIENT' },
  CONTACT_INFO: {contactPhone:'示例电话',contactWechat:'demo_wefolio',borderEnabled:true,borderWidthRpx:1,borderColor:'AUTO',horizontalPaddingRpx:24,verticalPaddingRpx:24},
  HYPERLINK: {workId:109,actionType:'INTERNAL_PORTFOLIO',targetPortfolioId:9002,showClickIcon:true,iconPosition:'OVERLAY'}
}
function createMockComponentConfig(type) {
  if (type === 'CONTACT_INFO') return require('./mock-portfolio-hyperlink').createMockContactInfoConfig()
  if (type === 'HYPERLINK') return require('./mock-portfolio-hyperlink').createMockHyperlinkConfig()
  if (type === 'TEXT_GRID') return require('./mock-portfolio-text-grid').createMockTextGrid()
  return JSON.parse(JSON.stringify(NEW_DEFAULTS[type] || {}))
}
function validateMockComponentConfig(type, config = {}, works = []) {
  const fail = message => ({valid:false,message})
  if (!MOCK_COMPONENT_TYPES.includes(type)) return fail('暂不支持的组件')
  const allowed = new Set(selectMockWorksFor(type, works).map(w => Number(w.id || w.workId)))
  const ids = Array.isArray(config.workIds) ? config.workIds.map(Number) : []
  if (['CAROUSEL','VIDEO_CAROUSEL'].includes(type)) {
    const min = type === 'VIDEO_CAROUSEL' ? 3 : 1
    const max = type === 'VIDEO_CAROUSEL' ? 8 : 9
    if (ids.length < min || ids.length > max || new Set(ids).size !== ids.length) return fail(`请选择 ${min}–${max} 个不同作品`)
    if (ids.some(id => !allowed.has(id))) return fail('作品类型不符合组件要求，请重新选择')
  }
  if (type === 'VIDEO_CAROUSEL' && (!['STACKED','PORTRAIT_CARDS'].includes(config.displayStyle) || Array.from(config.title || '').length > 10)) return fail('请检查视频标题或展示样式')
  if (type === 'SINGLE_WORK' && !allowed.has(Number(config.workId))) return fail('请选择图片、视频或动图作品')
  if (type === 'QR_CONTACT' && config.qrUrlSource === 'CUSTOM' && !String(config.qrUrl || '').trim()) return fail('请选择自定义二维码图片')
  if (['WORK_GRID','WORK_LIST'].includes(type)) {
    const groups = config.groups || [{name:'全部作品',workIds:ids}]
    if (groups.some(g => !g.name || Array.from(g.name).length > 20 || !Array.isArray(g.workIds) || g.workIds.some(id => !allowed.has(Number(id))))) return fail('请检查分组名称和作品类型')
  }
  if (type === 'PROFILE') {
    if (config.layout && !['VERTICAL','HORIZONTAL'].includes(config.layout)) return fail('请选择有效布局')
    if (config.profileBorderColor && !/^(AUTO|#[a-fA-F0-9]{6})$/.test(config.profileBorderColor)) return fail('请输入合法边框颜色')
    for (const [field,min,max] of [['profileBorderWidthRpx',1,12],['profileHorizontalMarginRpx',0,96],['profileVerticalMarginRpx',0,96]]) {
      if (config[field] !== undefined && (!Number.isInteger(Number(config[field])) || Number(config[field]) < min || Number(config[field]) > max)) return fail('边框宽度或留白超出范围')
    }
  }
  let message = ''
  if (type === 'TEXT_SECTION' || type === 'STRUCTURED_TEXT_SECTION') {
    const text = require('./mock-portfolio-text')
    message = type === 'TEXT_SECTION' ? text.validateMockSimpleTextConfig(config,works) : text.validateMockStructuredTextConfig(config,works)
    message = message || text.validateMockTextBackground(config,works,{structured:type === 'STRUCTURED_TEXT_SECTION'})
  }
  if (type === 'TEXT_GRID') {
    const grid = require('./mock-portfolio-text-grid')
    message = grid.validateMockTextGrid(config, { requireText: true })
    if (!message) {
      try { grid.buildMockGridViewModel(config) }
      catch (error) { message = error.message }
    }
  }
  if (type === 'CONTACT_INFO') message = require('./mock-portfolio-hyperlink').validateMockContactInfoConfig(config)
  if (type === 'HYPERLINK') message = require('./mock-portfolio-hyperlink').validateMockHyperlinkConfig(config,works)
  // 兼容工具返回文案或结果对象的校验接口。
  if (message && typeof message === 'object') return message
  if (message) return fail(message)
  if (type === 'DIVIDER' && (!Number.isInteger(Number(config.heightPx)) || Number(config.heightPx) < 1 || !/^(BLACK|WHITE|GRAY|TRANSPARENT|#[a-fA-F0-9]{6})$/.test(config.color))) return fail('请输入合法颜色和正整数高度')
  return {valid:true,message:''}
}
module.exports = { MOCK_COMPONENT_TYPES, createMockComponentConfig, validateMockComponentConfig }
