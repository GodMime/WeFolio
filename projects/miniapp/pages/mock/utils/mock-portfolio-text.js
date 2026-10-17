const { buildRemoteFontStyle } = require('../../../utils/portfolio-text-typography')
// Mock 文字规则独立维护；仅接受本地作品数据，不访问正式业务模块。
const BLOCK_TYPES = ['EYEBROW', 'TITLE', 'PARAGRAPH', 'LIST', 'HINT', 'SPACER']
const FONT_FAMILIES = ['SYSTEM', 'WECHAT_SANS_SS']
const ALIGNMENTS = ['LEFT', 'CENTER', 'RIGHT']
const FONT_WEIGHTS = ['NORMAL', 'BOLD']
const BACKGROUND_TYPES = ['IMAGE', 'ANIMATION', 'VIDEO']
const TREATMENTS = ['ORIGINAL', 'DARK_MASK', 'GRADIENT']
const BLOCK_PRESETS = {
  EYEBROW: [24, 'NORMAL', 0, 16],
  TITLE: [44, 'BOLD', 24, 16],
  PARAGRAPH: [28, 'NORMAL', 0, 16],
  LIST: [26, 'NORMAL', 0, 16],
  HINT: [24, 'NORMAL', 32, 0]
}
const COLOR = /^(AUTO|#[\da-fA-F]{6})$/
const copy = value => Array.isArray(value) ? value.map(copy) : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).map(([key,item])=>[key,copy(item)])) : value
const integer = (value,min,max) => Number.isInteger(value) && value >= min && value <= max
const validLineHeight = value => typeof value === 'number' && value >= 0.5 && value <= 3 && Math.abs(value * 10 - Math.round(value * 10)) < 0.000001
const spacing = (value,max=128) => integer(value,0,max) && value % 4 === 0
let blockSerial = 0

/** 新区块使用独立默认值，历史配置只补缺省字段。 */
function createMockStructuredBlock(type = 'PARAGRAPH', blockKey = `mock_block_${++blockSerial}`) {
  if (type === 'SPACER') return {blockKey,type,marginTopRpx:0,marginBottomRpx:0,heightRpx:32}
  const preset = BLOCK_PRESETS[type] || BLOCK_PRESETS.PARAGRAPH
  return {blockKey,type,fontFamily:'SYSTEM',fontSizeRpx:preset[0],fontWeight:preset[1],marginTopRpx:preset[2],marginBottomRpx:preset[3],color:'AUTO',alignment:'LEFT',...(type === 'LIST' ? {items:['']} : {content:''})}
}
function normalizeMockTextBackground(config = {}, {structured=false} = {}) {
  return {backgroundEnabled:false,backgroundTreatment:'GRADIENT',...(!structured ? {verticalAlignment:'CENTER'} : {}),...Object.fromEntries(['backgroundEnabled','backgroundWorkId','backgroundTreatment',...(!structured?['verticalAlignment']:[])].filter(key=>Object.prototype.hasOwnProperty.call(config,key)).map(key=>[key,copy(config[key])]))}
}
function finalizeMockTextBackground(config = {}, options) {
  const result = normalizeMockTextBackground(config,options)
  if (!result.backgroundEnabled) delete result.backgroundWorkId
  return result
}
function validateMockTextBackground(config = {}, works, options) {
  const background = normalizeMockTextBackground(config,options)
  if(typeof background.backgroundEnabled !== 'boolean' || !TREATMENTS.includes(background.backgroundTreatment)) return '请选择有效的文字背景配置'
  if(background.verticalAlignment !== undefined && !['TOP','CENTER','BOTTOM'].includes(background.verticalAlignment)) return '请选择有效的垂直对齐方式'
  if(!background.backgroundEnabled) return ''
  if(!Number.isSafeInteger(Number(background.backgroundWorkId)) || Number(background.backgroundWorkId)<=0) return '请选择背景作品'
  if(Array.isArray(works)) {
    const work = works.find(item=>String(item.id || item.workId) === String(background.backgroundWorkId))
    if(!work || !BACKGROUND_TYPES.includes(work.mediaType) || !work.mediaUrl) return '背景仅支持本地图片、动图或视频'
  }
  return ''
}
function normalizeMockSimpleTextConfig(config = {}) {
  return {content:'',fontFamily:'SYSTEM',fontSizeRpx:26,color:'AUTO',alignment:'LEFT',...copy(config),...normalizeMockTextBackground(config)}
}
function normalizeMockStructuredTextConfig(config = {}) {
  const result = {...copy(config),...normalizeMockTextBackground(config,{structured:true}),blocks:Array.isArray(config.blocks) ? config.blocks.map(block=>block && typeof block === 'object' ? ({...createMockStructuredBlock(block.type,block.blockKey),...copy(block)}) : block) : config.blocks === undefined ? [] : config.blocks}
  delete result.verticalAlignment
  return result
}
function validateStyle(value, weight = false) {
  if(!FONT_FAMILIES.includes(value.fontFamily) || !integer(value.fontSizeRpx,10,96)) return '字体或字号无效，字号须为 10–96 rpx'
  if(typeof value.color !== 'string' || !COLOR.test(value.color)) return '颜色须为 AUTO 或完整六位 HEX'
  if(!ALIGNMENTS.includes(value.alignment)) return '请选择有效的对齐方式'
  if(Object.prototype.hasOwnProperty.call(value,'lineHeight') && !validLineHeight(value.lineHeight)) return '行间距须为 0.5–3.0，步长 0.1'
  if(weight && !FONT_WEIGHTS.includes(value.fontWeight)) return '请选择有效字重'
  return ''
}
function validateMockSimpleTextConfig(config,works) {
  if(!config || typeof config !== 'object') return '文字配置无效'
  const value = normalizeMockSimpleTextConfig(config)
  if(typeof value.content !== 'string' || Array.from(value.content).length>200) return '简单文字最多 200 字'
  return validateStyle(value) || validateMockTextBackground(value,works)
}
function validateMockStructuredTextConfig(config,works) {
  if(!config || !Array.isArray(config.blocks)) return '请设置文字区块'
  const value = normalizeMockStructuredTextConfig(config)
  if(value.blocks.length<1 || value.blocks.length>20) return '须添加 1–20 个区块'
  const keys = new Set()
  let count = 0
  for(const block of value.blocks) {
    if(!block || !BLOCK_TYPES.includes(block.type) || !block.blockKey || keys.has(block.blockKey)) return '区块类型或标识无效'
    keys.add(block.blockKey)
    if(!spacing(block.marginTopRpx) || !spacing(block.marginBottomRpx)) return '区块间距须为 0–128 rpx，步长 4'
    if(block.type === 'SPACER') {
      if(!spacing(block.heightRpx,512) || Object.prototype.hasOwnProperty.call(block,'lineHeight')) return '留白高度须为 0–512，步长 4，不能设置行间距'
      continue
    }
    const error = validateStyle(block,true)
    if(error) return error
    const strings = block.type === 'LIST' ? block.items : [block.content]
    if(!Array.isArray(strings) || strings.length<1 || strings.length>10 || strings.some(item=>typeof item !== 'string' || !item.trim())) return '列表须为 1–10 项文字'
    count += strings.reduce((sum,item)=>sum+Array.from(item).length,0)
  }
  if(count>2000) return '结构化文字最多 2000 字'
  return validateMockTextBackground(value,works,{structured:true})
}
/** 行距缺省时不生成 CSS，保留旧版样式语义。 */
function mockTextStyle(value,themeMode='light',fontContext={}) {
  return buildRemoteFontStyle(value,fontContext) + `font-size:${value.fontSizeRpx}rpx;color:${value.color==='AUTO' ? themeMode==='dark'?'#F8F9FA':'#212529':value.color};text-align:${String(value.alignment||'LEFT').toLowerCase()};${value.fontWeight ? `font-weight:${value.fontWeight==='BOLD'?700:400};`:''}${validLineHeight(value.lineHeight)?`line-height:${value.lineHeight};`:''}`
}
function buildMockTextViewModel(config,works=[],{structured=false,themeMode='light',fontContext={}}={}) {
  const value = structured ? normalizeMockStructuredTextConfig(config) : normalizeMockSimpleTextConfig(config)
  const background = value.backgroundEnabled ? works.find(item=>String(item.id||item.workId)===String(value.backgroundWorkId) && BACKGROUND_TYPES.includes(item.mediaType)) : null
  const blocks = structured ? value.blocks : [{...value,type:'PARAGRAPH',blockKey:'simple',marginTopRpx:0,marginBottomRpx:0}]
  const frameHeight = background && Number(background.width) > 0 && Number(background.height) > 0
    ? 750 * Number(background.height) / Number(background.width) : 0
  const displayTheme = background ? 'dark' : themeMode
  return {simpleText:!structured,title:structured?'':value.title||'',background:background ? {...copy(background),isVideo:background.mediaType==='VIDEO'} : null,treatment:value.backgroundTreatment,verticalAlignment:value.verticalAlignment||'TOP',frameStyle:frameHeight>0?`min-height:${frameHeight}rpx;`:'',blocks:(Array.isArray(blocks)?blocks:[]).map(block=>({...copy(block),fontClass:!block.fontId&&block.fontFamily==='WECHAT_SANS_SS'?'font-wechat-sans-ss':'',style:`margin-top:${block.marginTopRpx}rpx;margin-bottom:${block.marginBottomRpx}rpx;${block.type==='SPACER'?`height:${block.heightRpx}rpx;`:mockTextStyle(block,displayTheme,fontContext)}`}))}
}
const MOCK_WECHAT_FONT_FAMILY = 'WeFolioWechatSansSS'
const MOCK_WECHAT_FONT_SOURCE = 'WeChatSansSS'
let mockTextFontCapability = {apiAvailable:false,loadedFamilies:{WECHAT_SANS_SS:false}}
const copyMockTextFontCapability = value => ({apiAvailable:value.apiAvailable===true,loadedFamilies:{WECHAT_SANS_SS:value.loadedFamilies.WECHAT_SANS_SS===true}})
function getMockTextFontCapability() { return copyMockTextFontCapability(mockTextFontCapability) }
function isMockTextFontAvailable(fontFamily,capability=mockTextFontCapability) {
  return fontFamily === 'SYSTEM' || capability.apiAvailable === true && capability.loadedFamilies && capability.loadedFamilies[fontFamily] === true
}
/** 使用平台内置字体，并把真实可用性回传给 Mock 编辑页。 */
function registerMockTextFont(wxApi,onComplete) {
  const finish = loaded => {
    mockTextFontCapability={apiAvailable:Boolean(wxApi&&typeof wxApi.loadBuiltInFontFace==='function'),loadedFamilies:{WECHAT_SANS_SS:loaded===true}}
    const capability=getMockTextFontCapability()
    if(typeof onComplete==='function')onComplete(capability)
    return capability
  }
  if (!wxApi || typeof wxApi.loadBuiltInFontFace !== 'function') return Promise.resolve(finish(false))
  return new Promise(resolve=>{
    try {
      wxApi.loadBuiltInFontFace({family:MOCK_WECHAT_FONT_FAMILY,source:MOCK_WECHAT_FONT_SOURCE,global:true,success(){resolve(finish(true))},fail(){resolve(finish(false))}})
    } catch (_) { resolve(finish(false)) }
  })
}
module.exports = {registerMockTextFont,getMockTextFontCapability,isMockTextFontAvailable,BLOCK_TYPES,FONT_FAMILIES,ALIGNMENTS,FONT_WEIGHTS,TREATMENTS,copy,integer,validLineHeight,mockTextStyle,createMockStructuredBlock,normalizeMockTextBackground,finalizeMockTextBackground,validateMockTextBackground,normalizeMockSimpleTextConfig,normalizeMockStructuredTextConfig,validateMockSimpleTextConfig,validateMockStructuredTextConfig,buildMockTextViewModel}
