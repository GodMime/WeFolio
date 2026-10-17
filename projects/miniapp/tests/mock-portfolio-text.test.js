const test = require('node:test')
const assert = require('node:assert/strict')
const text = require('../pages/mock/utils/mock-portfolio-text')
const grid = require('../pages/mock/utils/mock-portfolio-text-grid')

test('文字校验保留历史缺省行距并拒绝错误边界', () => {
  assert.equal(text.validateMockSimpleTextConfig({ content: '旧文字', fontSizeRpx: 28 }), '')
  assert.equal(text.normalizeMockSimpleTextConfig({ content: '旧文字', title: '旧标题' }).lineHeight, undefined)
  for (const config of [{ content: '字'.repeat(201) }, { content: '字', lineHeight: 1.55 }, { content: '字', color: '#zzzzzz' }]) assert.ok(text.validateMockSimpleTextConfig(config))
  const block = { ...text.createMockStructuredBlock('TITLE', 'title'), content: '标题' }
  assert.equal(text.validateMockStructuredTextConfig({ blocks: [block] }), '')
  assert.ok(text.validateMockStructuredTextConfig({ blocks: Array.from({length:21}, (_,i) => ({...block,blockKey:String(i)})) }))
  assert.ok(text.validateMockStructuredTextConfig({ blocks: [{...block,content:'字'.repeat(2001)}] }))
  assert.ok(text.validateMockStructuredTextConfig({ blocks: [{...block,marginTopRpx:3}] }))
  assert.ok(text.validateMockStructuredTextConfig({ blocks: [{...text.createMockStructuredBlock('SPACER','space'),lineHeight:1.5}] }))
})

test('文字背景筛选媒体、关闭清引用并保留 GIF 原资源', () => {
  const works = [{id:1,mediaType:'ANIMATION',mediaUrl:'demo.gif'},{id:2,mediaType:'AUDIO',mediaUrl:'demo.mp3'}]
  assert.equal(text.validateMockTextBackground({backgroundEnabled:true,backgroundWorkId:1}, works), '')
  assert.ok(text.validateMockTextBackground({backgroundEnabled:true,backgroundWorkId:2}, works))
  assert.equal(text.finalizeMockTextBackground({backgroundEnabled:false,backgroundWorkId:1}).backgroundWorkId, undefined)
  assert.equal(text.buildMockTextViewModel({content:'测试',backgroundEnabled:true,backgroundWorkId:1},works).background.mediaUrl,'demo.gif')
})

test('网格合并拆分缩放不改输入、不丢文字并可撤销', () => {
  const source = grid.createMockTextGrid()
  source.cells[0].blocks[0].runs[0].text = '保留内容'
  source.cells[1].blocks[0].runs[0].text = '第二段'
  const snapshot = JSON.stringify(source)
  const merged = grid.mergeMockGridCells(source,source.cells.slice(0,2).map(cell=>cell.cellKey))
  assert.equal(JSON.stringify(source),snapshot)
  assert.equal(merged.cells[0].blocks.length,2)
  assert.equal(grid.validateMockTextGrid(merged),'')
  assert.equal(grid.splitMockGridCell(merged,merged.cells[0].cellKey).cells.length,4)
  assert.throws(()=>grid.mergeMockGridCells(source,[source.cells[0].cellKey,source.cells[3].cellKey]),/矩形/)
  assert.throws(()=>grid.resizeMockGrid(source,2,1),/清空/)
  const history = grid.createMockGridHistory(source)
  history.apply(merged)
  assert.deepEqual(history.undo(),source)
  for(let i=0;i<45;i++) history.apply(source)
  assert.equal(history.size(),40)
  history.get().cells[0].blocks[0].runs[0].text='污染'
  assert.equal(history.get().cells[0].blocks[0].runs[0].text,'保留内容')
})

test('网格拒绝空洞、重复覆盖、超限内容与非法样式', () => {
  const source = grid.createMockTextGrid()
  assert.ok(grid.validateMockTextGrid({...source,cells:source.cells.slice(1)}))
  assert.ok(grid.validateMockTextGrid({...source,cells:[...source.cells,source.cells[0]]}))
  assert.ok(grid.validateMockTextGrid({...source,columnWeights:[0,1]}))
  source.cells[0].blocks[0].runs[0].text='字'.repeat(2001)
  assert.ok(grid.validateMockTextGrid(source))
})

test('网格测量扩大行高，合并单元格使用定位布局', () => {
  const source = grid.createMockTextGrid()
  const vm = grid.buildMockGridViewModel(source,'dark',686,{[source.cells[0].cellKey]:500})
  assert.ok(vm.height >= 500)
  assert.match(vm.cells[0].style,/position:absolute/)
  assert.match(vm.cells[0].blocks[0].runs[0].style,/font-size:28rpx/)
})

const fs = require('node:fs')
const vm = require('node:vm')
const path = require('node:path')
function editor(name, config) {
  let definition
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,`../components/mock/${name}/${name}.js`),'utf8'),{Component:value=>{definition=value}, require: require('node:module').createRequire(path.join(__dirname,`../components/mock/${name}/${name}.js`))})
  const events=[]
  const instance={data:{...definition.data,config},setData(value){Object.assign(this.data,value)},triggerEvent(name,detail){events.push({name,detail})},...definition.methods}
  definition.observers.config.call(instance,config)
  return {instance,events}
}
test('结构化编辑临时副本不写入原配置，确认取消以事件返回', () => {
  const config={blocks:[{...text.createMockStructuredBlock('TITLE','title'),content:'原标题'}]}
  const {instance,events}=editor('structured-text-editor',config)
  instance.handleInput({currentTarget:{dataset:{index:0,field:'content'}},detail:{value:'新标题'}})
  assert.equal(config.blocks[0].content,'原标题')
  assert.equal(events[0].name,'configchange')
  instance.handleConfirm()
  assert.equal(events.at(-1).detail.config.blocks[0].content,'新标题')
  instance.handleCancel()
  assert.equal(events.at(-1).name,'cancel')
})
test('网格组件只派发合并操作，背景组件只修改临时配置', () => {
  const config=grid.createMockTextGrid()
  const {instance,events}=editor('text-grid-editor',config)
  instance.handleSelect({currentTarget:{dataset:{key:config.cells[0].cellKey}}})
  instance.handleOperation({currentTarget:{dataset:{type:'merge'}}})
  assert.equal(events.at(-1).name,'operation')
  assert.equal(events.at(-1).detail.type,'merge')
  assert.deepEqual(Array.from(events.at(-1).detail.selectedKeys),[config.cells[0].cellKey])
  const background=editor('text-background-editor',{backgroundEnabled:true,backgroundWorkId:109})
  background.instance.handleSwitch({detail:{value:false}})
  assert.equal(background.events[0].detail.config.backgroundEnabled,false)
  assert.equal(background.instance.data.config.backgroundEnabled,true)
})

test('损坏文字配置返回错误，不因空区块或空参数抛异常', () => {
  assert.ok(text.validateMockStructuredTextConfig({blocks:[null]}))
  assert.ok(text.validateMockStructuredTextConfig({blocks:[{}]}))
  assert.ok(text.validateMockStructuredTextConfig({blocks:[]}))
  assert.ok(text.validateMockStructuredTextConfig({blocks:[{...text.createMockStructuredBlock('LIST','list'),items:['']}]}))
})
test('合并保留纯空格内容、拒绝超过段落上限并检查全部样式', () => {
  const source=grid.createMockTextGrid()
  source.cells[0].blocks[0].runs[0].text='  '
  const merged=grid.mergeMockGridCells(source,source.cells.slice(0,2).map(cell=>cell.cellKey))
  assert.equal(merged.cells[0].blocks[0].runs[0].text,'  ')
  for(let i=0;i<8;i++){const block=grid.createMockGridBlock();block.runs[0].text='段落';source.cells[0].blocks.push(block)}
  assert.throws(()=>grid.mergeMockGridCells(source,source.cells.slice(0,2).map(cell=>cell.cellKey)),/8/)
  for(const [field,value] of [['cellBorderWidthRpx',13],['horizontalMarginRpx',97],['cellPaddingRpx',49],['cellBorderColor','#123']]) assert.ok(grid.validateMockTextGrid({...grid.createMockTextGrid(),[field]:value}))
})
test('暗底色上的自动文字颜色和窄网格布局有明确保护', () => {
  const source=grid.createMockTextGrid()
  source.cellBackground='#000000'
  assert.match(grid.buildMockGridViewModel(source,'light').cells[0].blocks[0].runs[0].style,/color:#F8F9FA/)
  assert.throws(()=>grid.buildMockGridViewModel(source,'light',20),/宽度/)
})

test('编辑器清空行距恢复缺省语义，不把空输入写成零', () => {
  const config={blocks:[{...text.createMockStructuredBlock('TITLE','title'),content:'标题',lineHeight:2}]}
  const structured=editor('structured-text-editor',config)
  structured.instance.handleInput({currentTarget:{dataset:{index:0,field:'lineHeight'}},detail:{value:''}})
  assert.equal(structured.instance.data.draft.blocks[0].lineHeight,undefined)
  const source=grid.createMockTextGrid();source.cells[0].blocks[0].lineHeight=2
  const gridEditor=editor('text-grid-editor',source)
  gridEditor.instance.handleInput({currentTarget:{dataset:{cellIndex:0,blockIndex:0,field:'lineHeight'}},detail:{value:''}})
  assert.equal(gridEditor.instance.data.draft.cells[0].blocks[0].lineHeight,undefined)
})

function backgroundSection(background, wxApi) {
  let definition
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../components/mock/structured-text-section/structured-text-section.js'),'utf8'),{Component:value=>{definition=value},wx:wxApi})
  const events=[]
  const instance={data:{...definition.data,active:true,viewModel:{background,blocks:[{blockKey:'text',content:'文字始终保留'}]}},setData(value){Object.assign(this.data,value)},triggerEvent(name,detail){events.push({name,detail})},...definition.methods}
  if(definition.observers)definition.observers.viewModel.call(instance,instance.data.viewModel)
  return {definition,instance,events}
}

test('结构化文字视频背景以封面遮挡首帧并在失活和后台时主动暂停', () => {
  const pauses=[]
  let scopedInstance
  const wxApi={createVideoContext(id,scope){
    assert.equal(id,'mockStructuredTextBackgroundVideo')
    scopedInstance=scope
    return {pause(){pauses.push('pause')}}
  }}
  const {definition,instance}=backgroundSection({mediaUrl:'demo.mp4',coverUrl:'poster.jpg',isVideo:true},wxApi)
  assert.equal(instance.data.mediaReady,false)
  assert.equal(instance.data.videoPosterUrl,'poster.jpg')
  instance.handleVideoTimeUpdate({currentTarget:{dataset:{attempt:instance.data.mediaAttempt}},detail:{currentTime:0}})
  assert.equal(instance.data.mediaReady,false)
  instance.handleVideoTimeUpdate({currentTarget:{dataset:{attempt:instance.data.mediaAttempt}},detail:{currentTime:0.2}})
  assert.equal(instance.data.mediaReady,true)
  definition.observers.active.call(instance,false)
  assert.equal(scopedInstance,instance)
  assert.equal(pauses.length,1)
  definition.pageLifetimes.hide.call(instance)
  definition.lifetimes.detached.call(instance)
  assert.equal(pauses.length,3)

  const source=fs.readFileSync(path.join(__dirname,'../components/mock/structured-text-section/structured-text-section.wxml'),'utf8')
  assert.match(source,/id="mockStructuredTextBackgroundVideo"/)
  assert.match(source,/bindtimeupdate="handleVideoTimeUpdate"/)
  assert.match(source,/videoPosterUrl && \(!mediaReady \|\| mediaFailed\)/)
  assert.match(source,/class="background-poster"/)
})
test('图片、GIF、视频背景失败可重试且不改动文字和源地址', () => {
  for(const media of [{mediaUrl:'demo.jpg',isVideo:false},{mediaUrl:'demo.gif',isVideo:false},{mediaUrl:'demo.mp4',isVideo:true}]) {
    const {instance,events}=backgroundSection(media)
    const original=JSON.stringify(instance.data.viewModel)
    instance.handleMediaError({currentTarget:{dataset:{attempt:instance.data.mediaAttempt}}})
    assert.equal(instance.data.mediaFailed,true)
    assert.equal(events.at(-1).name,'backgrounderror')
    const failedAttempt=instance.data.mediaAttempt
    instance.handleRetryBackground()
    assert.equal(instance.data.mediaFailed,false)
    assert.ok(instance.data.mediaAttempt>failedAttempt)
    assert.equal(JSON.stringify(instance.data.viewModel),original)
  }
})
test('切换背景清除错误并拒绝旧资源迟到回调，隐藏视频不重新播放', () => {
  const {definition,instance}=backgroundSection({mediaUrl:'first.mp4',isVideo:true})
  const oldAttempt=instance.data.mediaAttempt
  instance.handleMediaError({currentTarget:{dataset:{attempt:oldAttempt}}})
  instance.data.viewModel={...instance.data.viewModel,background:{mediaUrl:'second.gif',isVideo:false}}
  definition.observers.viewModel.call(instance,instance.data.viewModel)
  assert.equal(instance.data.mediaFailed,false)
  instance.handleMediaError({currentTarget:{dataset:{attempt:oldAttempt}}})
  assert.equal(instance.data.mediaFailed,false)
  instance.handleMediaError({currentTarget:{dataset:{attempt:instance.data.mediaAttempt}}})
  const failedAttempt=instance.data.mediaAttempt
  instance.data.active=false
  instance.handleRetryBackground()
  assert.equal(instance.data.mediaAttempt,failedAttempt)
  assert.equal(instance.data.mediaFailed,true)
  instance.data.viewModel={...instance.data.viewModel,background:null}
  definition.observers.viewModel.call(instance,instance.data.viewModel)
  assert.equal(instance.data.mediaFailed,false)
})
test('背景失败只移除媒体节点，保留文字并显示真实重试按钮', () => {
  const source=fs.readFileSync(path.join(__dirname,'../components/mock/structured-text-section/structured-text-section.wxml'),'utf8')
  assert.match(source,/wx:if="{{!mediaFailed}}"/)
  assert.match(source,/wx:if="\{\{viewModel\.background\.isVideo\}\}"/)
  assert.match(source,/autoplay="\{\{active\}\}" loop muted/)
  assert.match(source,/bindtap="handleRetryBackground"/)
  assert.match(source,/backgroundErrorMessage/)
  assert.match(source,/class="content"/)
})

test('中文文字编辑选项保留原配置枚举和选择顺序', () => {
  const structured=editor('structured-text-editor',{blocks:[{...text.createMockStructuredBlock('TITLE','title'),content:'标题'}]})
  assert.deepEqual(Array.from(structured.instance.data.fontLabels),require('../utils/portfolio-text-typography').PORTFOLIO_TEXT_SELECTION_OPTIONS.map(font => font.languageLabel ? `${font.label}（${font.languageLabel}）` : font.label))
  assert.equal(structured.instance.data.optionLabels.BOLD,'粗体')
  structured.instance.handleChoice({currentTarget:{dataset:{index:0,field:'fontFamily'}},detail:{value:1}})
  assert.equal(structured.events.length,0)
  structured.instance.setData({fontAvailability:{SYSTEM:true,WECHAT_SANS_SS:true}})
  structured.instance.handleChoice({currentTarget:{dataset:{index:0,field:'fontFamily'}},detail:{value:1}})
  assert.equal(structured.events.at(-1).detail.config.blocks[0].fontFamily,'WECHAT_SANS_SS')
  const gridEditor=editor('text-grid-editor',grid.createMockTextGrid())
  assert.deepEqual(Array.from(gridEditor.instance.data.choiceLabels.verticalAlignment),['顶','中','底'])
  gridEditor.instance.handleChoice({currentTarget:{dataset:{cellIndex:0,field:'verticalAlignment'}},detail:{value:2}})
  assert.equal(gridEditor.events.at(-1).detail.config.cells[0].verticalAlignment,'BOTTOM')
  const background=editor('text-background-editor',{backgroundEnabled:true})
  assert.equal(background.instance.data.optionLabels.GRADIENT,'固定渐进遮罩')
  background.instance.handleTreatment({detail:{value:1}})
  assert.equal(background.events.at(-1).detail.config.backgroundTreatment,'DARK_MASK')
})
test('合并后清除已消失的选择键，后续合并只包含仍存在的单元格', () => {
  const source=grid.createMockTextGrid()
  const {instance}=editor('text-grid-editor',source)
  instance.handleSelect({currentTarget:{dataset:{key:source.cells[0].cellKey}}})
  instance.handleSelect({currentTarget:{dataset:{key:source.cells[1].cellKey}}})
  const merged=grid.mergeMockGridCells(source,instance.data.selectedKeys)
  let definition
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../components/mock/text-grid-editor/text-grid-editor.js'),'utf8'),{Component:value=>{definition=value}, require: require('node:module').createRequire(path.join(__dirname,'../components/mock/text-grid-editor/text-grid-editor.js'))})
  definition.observers.config.call(instance,merged)
  assert.deepEqual(Array.from(instance.data.selectedKeys),[source.cells[0].cellKey])
  assert.equal(instance.data.selectedMap[source.cells[1].cellKey],undefined)
  instance.handleSelect({currentTarget:{dataset:{key:source.cells[2].cellKey}}})
  instance.handleSelect({currentTarget:{dataset:{key:source.cells[3].cellKey}}})
  assert.equal(grid.mergeMockGridCells(merged,instance.data.selectedKeys).cells.length,1)
})
test('文字展示使用现有微信字体注册名', () => {
  for(const name of ['structured-text-section','text-grid']) {
    const style=fs.readFileSync(path.join(__dirname,`../components/mock/${name}/${name}.wxss`),'utf8')
    assert.match(style,/font-family:\s*["']?WeFolioWechatSansSS/)
  }
})

test('留白区块同时渲染高度和上下间距，零值不被替换', () => {
  for (const [height, spacing] of [[0, 0], [32, 128], [512, 128]]) {
    const block = {...text.createMockStructuredBlock('SPACER', 'spacer'), heightRpx:height, marginTopRpx:spacing, marginBottomRpx:spacing}
    const config = {blocks:[block]}
    assert.equal(text.validateMockStructuredTextConfig(config), '')
    const result = text.buildMockTextViewModel(config, [], {structured:true})
    assert.ok(result.blocks[0].style.includes(`height:${height}rpx;`))
    assert.ok(result.blocks[0].style.includes(`margin-top:${spacing}rpx;`))
    assert.ok(result.blocks[0].style.includes(`margin-bottom:${spacing}rpx;`))
  }
})

test('简单文字缺省行距沿用旧CSS，显式行距仍覆盖默认且不写入原配置', () => {
  const config = {content:'旧文字',fontSizeRpx:26}
  const result = text.buildMockTextViewModel(config)
  assert.equal(result.simpleText, true)
  assert.equal(config.lineHeight, undefined)
  assert.doesNotMatch(result.blocks[0].style, /line-height/)
  assert.match(text.buildMockTextViewModel({...config,lineHeight:2}).blocks[0].style, /line-height:2;/)
  const structured = text.buildMockTextViewModel({blocks:[{...text.createMockStructuredBlock('PARAGRAPH','p'),content:'段落'}]}, [], {structured:true})
  assert.equal(structured.simpleText, false)
  const source = fs.readFileSync(path.join(__dirname,'../components/mock/structured-text-section/structured-text-section.wxml'),'utf8')
  const css = fs.readFileSync(path.join(__dirname,'../components/mock/structured-text-section/structured-text-section.wxss'),'utf8')
  assert.match(source, /viewModel.simpleText\s*\?\s*'simple-text'/)
  assert.match(css, /\.simple-text\s+\.text-block\s*\{\s*line-height:\s*1\.75\s*;/)
})
