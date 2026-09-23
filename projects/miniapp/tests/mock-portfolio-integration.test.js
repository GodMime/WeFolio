const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.join(__dirname,'..')
const mock = require('../pages/mock/utils/mock-experience')
const { validateMockComponentConfig } = require('../pages/mock/utils/mock-portfolio-components')
function loadPage(name) {
  let definition
  global.Page=value=>{definition=value}
  const file=path.join(ROOT,`pages/mock/${name}/${name}.js`)
  delete require.cache[require.resolve(file)]
  require(file)
  delete global.Page
  return {...definition,data:JSON.parse(JSON.stringify(definition.data)),setData(patch,callback){Object.assign(this.data,patch);if(callback)callback()}}
}
function event(dataset={},value) {return {currentTarget:{dataset},detail:{value}}}
function runtime() {
  const storage=new Map(),calls=[],instances=[]
  const forbidden=()=>{throw new Error('不允许后端或身份调用')}
  const wxApi={request:forbidden,uploadFile:forbidden,login:forbidden,
    getStorageSync(key){assert.equal(key,mock.MOCK_PORTFOLIO_DRAFT_STORAGE_KEY);return storage.get(key)},
    setStorageSync(key,value){assert.equal(key,mock.MOCK_PORTFOLIO_DRAFT_STORAGE_KEY);storage.set(key,value)},
    removeStorageSync(key){assert.equal(key,mock.MOCK_PORTFOLIO_DRAFT_STORAGE_KEY);storage.delete(key)},
    showToast(value){calls.push(['toast',value.title])},navigateTo(value){assert.match(value.url,/^\/pages\/mock\//);calls.push(['navigate',value.url])},
    previewImage(value){calls.push(['image',value.current])},createVideoContext(){return {pause(){calls.push(['videoPause'])}}},
    setClipboardData(value){calls.push(['copy',value.data]);value.success()},getWindowInfo(){return {windowWidth:375}},
    createInnerAudioContext(){const callbacks={};const audio={play(){calls.push(['audioPlay']);callbacks.Play?.()},pause(){calls.push(['audioPause']);callbacks.Pause?.()},stop(){},destroy(){calls.push(['audioDestroy'])}};for(const event of ['Play','Pause','Ended','Error','Stop'])audio['on'+event]=cb=>{callbacks[event]=cb};instances.push(audio);return audio}
  }
  return {wxApi,storage,calls,instances}
}

test('背景音频选择器按开关和选择动作收起，关闭保留引用，移除清空引用',()=>{
  const rt=runtime();global.wx=rt.wxApi
  try {
    const page=loadPage('portfolio-standard-edit');page.onLoad()
    assert.equal(page.data.backgroundAudioPickerVisible,false)
    page.handleAudioSetting(event({field:'enabled'},true))
    page.handleChooseBackgroundAudio()
    assert.equal(page.data.backgroundAudioPickerVisible,true)
    page.handleAudioSetting(event({field:'workId',value:108}))
    assert.equal(page.data.backgroundAudioPickerVisible,false)
    assert.equal(page.data.audioSelectedWork.id,108)
    page.handleAudioAudition()
    assert.equal(page.data.audioPlaying,true)
    page.handleAudioSetting(event({field:'displayStyle',value:'SLEEVE'}))
    assert.equal(page.data.audioPlaying,true)
    page.handleChooseBackgroundAudio()
    page.handleAudioSetting(event({field:'enabled'},false))
    assert.equal(page.data.backgroundAudioPickerVisible,false)
    assert.equal(page.data.audioPlaying,false)
    assert.equal(page.data.draft.config.backgroundAudio.workId,108)
    page.handleAudioSetting(event({field:'enabled'},true))
    page.handleChooseBackgroundAudio()
    page.handleCloseBackgroundAudioPicker()
    assert.equal(page.data.backgroundAudioPickerVisible,false)
    page.handleChooseBackgroundAudio()
    page.handleAudioSetting(event({field:'remove'}))
    assert.equal(page.data.backgroundAudioPickerVisible,false)
    assert.equal(page.data.draft.config.backgroundAudio.workId,null)
    assert.equal(page.data.draft.config.backgroundAudio.enabled,false)
  } finally {delete global.wx}
})

test('页面间距只接收正式范围内整数，保留其他页面设置',()=>{
  const rt=runtime();global.wx=rt.wxApi
  try {
    const page=loadPage('portfolio-standard-edit');page.onLoad()
    const color=page.data.draft.config.style.backgroundColor
    page.handleSpacingChange(event({},48))
    for(const value of [-1,97,1.5,'bad']) page.handleSpacingChange(event({},value))
    assert.equal(page.data.draft.config.style.componentSpacingRpx,48)
    assert.equal(page.data.draft.config.style.backgroundColor,color)
    for(const value of [0,96]) {
      page.handleSpacingChange(event({},value))
      assert.equal(page.data.draft.config.style.componentSpacingRpx,value)
    }
  } finally {delete global.wx}
})

test('默认15类配置全部可确认并保持本地引用',()=>{
  const draft=mock.buildStandardPortfolio()
  const components=draft.config.components.concat(...draft.config.bottomNav.items.slice(1).map(x=>x.components))
  for(const component of components) {
    const result=validateMockComponentConfig(component.componentType,component.config,mock.MOCK_WORK_LIBRARY.works)
    assert.equal(result.valid,true,`${component.componentType}: ${result.message}`)
  }
})

test('组件编辑取消还原打开前草稿，新建取消不残留组件，确认后才保留',()=>{
  const rt=runtime();global.wx=rt.wxApi
  try {
    const page=loadPage('portfolio-standard-edit');page.onLoad()
    const before=JSON.stringify(page.data.draft)
    const text=page.data.activeComponents.find(item=>item.componentType==='TEXT_SECTION')
    page.handleComponentTap(event({key:text.componentKey}))
    page.handleTextSectionInput(event({},'取消后不保留'))
    page.handleCloseComponentEditSheet()
    assert.equal(JSON.stringify(page.data.draft),before)
    page.handleSelectComponent(event({type:'STRUCTURED_TEXT_SECTION'}))
    page.handleCloseComponentEditSheet()
    assert.equal(JSON.stringify(page.data.draft),before)
    page.handleComponentTap(event({key:text.componentKey}))
    page.handleTextSectionInput(event({},'确认后保留'))
    page.handleConfirmComponentEditSheet()
    page.handleCloseComponentEditSheet()
    assert.equal(mock.getMockMenuComponents(page.data.draft.config,page.data.activeMenuKey).find(item=>item.componentKey===text.componentKey).config.content,'确认后保留')
  } finally {delete global.wx}
})

test('已有个人资料时所有菜单都禁止再次添加',()=>{
  const rt=runtime();global.wx=rt.wxApi
  try {
    const page=loadPage('portfolio-standard-edit');page.onLoad()
    page.handleEditorMenuTap(event({key:page.data.draft.config.bottomNav.items[1].key}))
    const before=JSON.stringify(page.data.draft)
    page.handleSelectComponent(event({type:'PROFILE'}))
    assert.equal(JSON.stringify(page.data.draft),before)
    assert.equal(page.data.componentOptions.find(item=>item.componentType==='PROFILE').disabled,true)
  } finally {delete global.wx}
})

test('轮播选择序号在搜索后仍保留，作品标签先选组再选作品且保留当前组',()=>{
  const rt=runtime();global.wx=rt.wxApi
  try {
    const page=loadPage('portfolio-standard-edit');page.onLoad()
    page.handleComponentTap(event({key:'mock_carousel'}))
    assert.deepEqual(page.data.workOptions.slice(0,3).map(item=>item.selectionOrder),[1,2,3])
    page.handleWorkFilterInput(event({},page.data.workOptions[1].title))
    assert.equal(page.data.workOptions[0].selectionOrder,2)
    page.handleCloseComponentEditSheet()
    page.handleSelectComponent(event({type:'WORK_GRID'}))
    page.handleDisplayGroupSelect(event({key:'tag_201'}))
    assert.equal(page.data.activeDisplayGroupKey,'tag_201')
    page.handleDisplayGroupToggle(event({key:'tag_201'}))
    assert.equal(page.data.componentConfig.groups.some(group=>group.groupKey==='tag_201'),true)
    const work=page.data.workOptions[0]
    page.handleWorkToggle(event({id:work.id}))
    assert.equal(page.data.activeDisplayGroupKey,'tag_201')
    assert.equal(page.data.selectedWorkIds.includes(work.id),false)
    page.handleConfigInput(event({field:'showTitle'},false))
    assert.equal(page.data.activeDisplayGroupKey,'tag_201')
    assert.equal(page.data.selectedWorkIds.includes(work.id),false)
  } finally {delete global.wx}
})

test('个人标签可本地增删且取消整体还原，二维码来源读取全局资料',()=>{
  const rt=runtime();global.wx=rt.wxApi
  try {
    const page=loadPage('portfolio-standard-edit');page.onLoad()
    page.handleComponentTap(event({key:'mock_profile'}))
    const count=page.data.profileForm.tags.length
    page.handleOpenProfileTagDialog()
    page.handleProfileNewTagInput(event({},'自然纪实'))
    page.handleSelectProfileTagColor(event({color:'#2d5f9a'}))
    page.handleAddProfileTag()
    assert.equal(page.data.profileForm.tags.length,count+1)
    assert.equal(page.data.profileForm.tags.at(-1).color,'#2d5f9a')
    page.handleOpenProfileTagDialog()
    page.handleProfileNewTagInput(event({},'自然纪实'))
    page.handleAddProfileTag()
    assert.match(page.data.profileTagErrorText,/重复/)
    page.handleCloseComponentEditSheet()
    page.handleComponentTap(event({key:'mock_profile'}))
    assert.equal(page.data.profileForm.tags.length,count)
    page.handleProfileInput(event({field:'wechatQrUrl'},'https://example.test/qr.png'))
    page.handleConfirmComponentEditSheet()
    page.handleEditorMenuTap(event({key:page.data.draft.config.bottomNav.items[1].key}))
    page.handleSelectComponent(event({type:'QR_CONTACT'}))
    assert.equal(page.data.profileQrUrl,'https://example.test/qr.png')
  } finally {delete global.wx}
})

test('二维码切换来源清空旧自定义图片，重新选图前不能确认',()=>{
  const rt=runtime();global.wx=rt.wxApi
  try {
    const page=loadPage('portfolio-standard-edit');page.onLoad()
    page.handleSelectComponent(event({type:'QR_CONTACT'}))
    page.handleQrWork(event({id:101}))
    assert.ok(page.data.componentConfig.qrUrl)
    page.handleConfigChoice(event({field:'qrUrlSource',value:'PROFILE'}))
    page.handleConfigChoice(event({field:'qrUrlSource',value:'CUSTOM'}))
    assert.equal(page.data.componentConfig.qrUrl,'')
    page.handleConfirmComponentEditSheet()
    assert.equal(page.data.componentEditSheetVisible,true)
    assert.match(page.data.complexError,/二维码图片/)
    page.handleQrWork(event({id:102}))
    page.handleConfirmComponentEditSheet()
    assert.equal(page.data.componentEditSheetVisible,false)
  } finally {delete global.wx}
})

test('取消编辑后迟到的字段和开关事件不改写草稿',()=>{
  const rt=runtime();global.wx=rt.wxApi
  try {
    const page=loadPage('portfolio-standard-edit');page.onLoad()
    page.handleComponentTap(event({key:'mock_profile'}))
    page.handleCloseComponentEditSheet()
    const before=JSON.stringify(page.data.draft)
    page.handleProfileVisibility(event({field:'profession'},false))
    page.handleProfileInput(event({field:'displayName'},'迟到姓名'))
    page.handleConfigInput(event({field:'layout'},'HORIZONTAL'))
    assert.equal(JSON.stringify(page.data.draft),before)
  } finally {delete global.wx}
})

test('结构化文字背景分区每次打开重置，网格效果随编辑和撤销同步',()=>{
  const rt=runtime();global.wx=rt.wxApi
  try {
    const page=loadPage('portfolio-standard-edit');page.onLoad()
    page.handleSelectComponent(event({type:'STRUCTURED_TEXT_SECTION'}))
    assert.equal(page.data.structuredTextTab,'content')
    page.handleStructuredTextTabChange({detail:{tab:'background'}})
    assert.equal(page.data.structuredTextTab,'background')
    page.handleCloseComponentEditSheet()
    page.handleSelectComponent(event({type:'TEXT_GRID'}))
    assert.ok(page.data.gridPreviewViewModel.cells.length>0)
    const old=page.data.complexConfig.cells[0].blocks[0].runs[0].text
    const config=JSON.parse(JSON.stringify(page.data.complexConfig))
    config.cells[0].blocks[0].runs[0].text='预览同步'
    page.handleGridChange({detail:{config}})
    assert.equal(page.data.gridPreviewViewModel.cells[0].blocks[0].runs[0].text,'预览同步')
    page.handleGridOperation({detail:{type:'undo'}})
    assert.equal(page.data.gridPreviewViewModel.cells[0].blocks[0].runs[0].text,old)
    page.handleGridPreviewMeasure({detail:{widthPx:200,heightsPx:{[config.cells[0].cellKey]:160}}})
    assert.match(page.data.gridPreviewViewModel.cells[0].style,/width:192rpx/)
    assert.match(page.data.gridPreviewViewModel.cells[0].style,/height:368rpx/)
    page.handleCloseComponentEditSheet()
    page.handleSelectComponent(event({type:'STRUCTURED_TEXT_SECTION'}))
    assert.equal(page.data.structuredTextTab,'content')
  } finally {delete global.wx}
})

test('新组件临时编辑取消无污染，错误输入不关闭，确认预览仅保存mock缓存',()=>{
  const rt=runtime();global.wx=rt.wxApi
  try {
    const page=loadPage('portfolio-standard-edit');page.onLoad()
    const before=JSON.stringify(page.data.draft)
    page.handleSelectComponent(event({type:'CONTACT_INFO'}))
    page.handleComplexChange({detail:{config:{...page.data.complexConfig,contactPhone:'改过'}}})
    page.handleCloseComponentEditSheet()
    assert.equal(JSON.stringify(page.data.draft),before)
    page.handleSelectComponent(event({type:'CONTACT_INFO'}))
    page.handleComplexChange({detail:{config:{...page.data.complexConfig,contactPhone:'\n非法'}}})
    page.handleConfirmComponentEditSheet()
    assert.equal(page.data.componentEditSheetVisible,true)
    assert.match(page.data.complexError,/换行/)
    page.handleComplexChange({detail:{config:{...page.data.complexConfig,contactPhone:'示例电话'}}})
    page.handleConfirmComponentEditSheet()
    assert.equal(page.data.componentEditSheetVisible,false)
    page.handlePreview()
    assert.equal(rt.storage.size,1)
    page.handleSaveDraft();page.handlePublish()
    assert.equal(rt.calls.filter(x=>x[1] === '请去“我的”页面注册登录').length,2)
  } finally {delete global.wx}
})

test('文字背景可确认关闭，网格非法输入阻断确认且可撤销',()=>{
  const rt=runtime();global.wx=rt.wxApi
  try {
    const page=loadPage('portfolio-standard-edit');page.onLoad()
    page.handleSelectComponent(event({type:'TEXT_GRID'}))
    const initial=JSON.parse(JSON.stringify(page.data.complexConfig))
    page.handleGridChange({detail:{config:{...initial,cellBorderColor:'#12'}}})
    page.handleConfirmComponentEditSheet()
    assert.equal(page.data.componentEditSheetVisible,true)
    assert.match(page.data.complexError,/颜色/)
    page.handleGridOperation({detail:{type:'undo'}})
    assert.equal(page.data.complexConfig.cellBorderColor,initial.cellBorderColor)
    page.handleConfirmComponentEditSheet()
    assert.equal(page.data.componentEditSheetVisible,false)
    page.handleSelectComponent(event({type:'TEXT_SECTION'}))
    page.handleTextBackgroundChange({detail:{config:{...page.data.componentConfig,backgroundEnabled:false,backgroundWorkId:109}}})
    page.handleConfirmComponentEditSheet()
    assert.equal(page.data.componentConfig.backgroundWorkId,undefined)
  } finally {delete global.wx}
})

test('旧文字非法临时值不写草稿，合法修正仍即时生效',()=>{
  const rt=runtime();global.wx=rt.wxApi
  try {
    const page=loadPage('portfolio-standard-edit');page.onLoad()
    const component=page.data.activeComponents.find(item=>item.componentType==='TEXT_SECTION')
    page.handleComponentTap(event({key:component.componentKey}))
    page.handleConfigInput(event({field:'content'},'即时保存的合法文字'))
    let saved=mock.getMockMenuComponents(page.data.draft.config,page.data.activeMenuKey).find(item=>item.componentKey===component.componentKey).config
    assert.equal(saved.content,'即时保存的合法文字')

    page.handleConfigInput(event({field:'lineHeight',numeric:true},'1.55'))
    assert.equal(page.data.componentConfig.lineHeight,1.55)
    assert.match(page.data.complexError,/行间距/)
    saved=mock.getMockMenuComponents(page.data.draft.config,page.data.activeMenuKey).find(item=>item.componentKey===component.componentKey).config
    assert.equal(saved.lineHeight,undefined)
    page.handleConfirmComponentEditSheet()
    assert.equal(page.data.componentEditSheetVisible,true)
    page.handleCloseComponentEditSheet()
    page.handleComponentTap(event({key:component.componentKey}))
    assert.equal(page.data.componentConfig.lineHeight,undefined)

    page.handleConfigInput(event({field:'color'},'#12'))
    assert.match(page.data.complexError,/颜色/)
    page.handleConfigInput(event({field:'color'},'#123456'))
    saved=mock.getMockMenuComponents(page.data.draft.config,page.data.activeMenuKey).find(item=>item.componentKey===component.componentKey).config
    assert.equal(saved.color,'#123456')
    assert.equal(page.data.complexError,'')
    page.handleTextBackgroundChange({detail:{config:{...page.data.componentConfig,backgroundEnabled:true,backgroundWorkId:108}}})
    assert.match(page.data.complexError,/背景/)
    saved=mock.getMockMenuComponents(page.data.draft.config,page.data.activeMenuKey).find(item=>item.componentKey===component.componentKey).config
    assert.equal(saved.color,'#123456')
    assert.equal(saved.backgroundWorkId,undefined)
    page.handleTextBackgroundChange({detail:{config:{...page.data.componentConfig,backgroundEnabled:false}}})
    saved=mock.getMockMenuComponents(page.data.draft.config,page.data.activeMenuKey).find(item=>item.componentKey===component.componentKey).config
    assert.equal(saved.color,'#123456')
    assert.equal(page.data.complexError,'')
    page.handleConfirmComponentEditSheet()
    page.handlePreview()
    const cached=rt.storage.get(mock.MOCK_PORTFOLIO_DRAFT_STORAGE_KEY)
    const cachedText=mock.getMockMenuComponents(cached.config,page.data.activeMenuKey).find(item=>item.componentKey===component.componentKey).config
    assert.equal(cachedText.lineHeight,undefined)
    assert.equal(cachedText.color,'#123456')
  } finally {delete global.wx}
})

test('网格非法临时态首次撤销不消耗合法历史，历史上限为40',()=>{
  const rt=runtime();global.wx=rt.wxApi
  try {
    const page=loadPage('portfolio-standard-edit');page.onLoad()
    page.handleSelectComponent(event({type:'TEXT_GRID'}))
    const initial=JSON.parse(JSON.stringify(page.data.complexConfig))
    const valid=JSON.parse(JSON.stringify(initial));valid.cells[0].blocks[0].runs[0].text='最后合法文字'
    page.handleGridChange({detail:{config:valid}})
    assert.equal(page.data.gridUndoCount,1)
    page.handleGridChange({detail:{config:{...valid,cellBorderColor:'#12'}}})
    page.handleGridChange({detail:{config:{...valid,cellBorderColor:'#123'}}})
    assert.equal(page.data.gridUndoCount,1)
    page.handleGridOperation({detail:{type:'undo'}})
    assert.equal(page.data.complexConfig.cells[0].blocks[0].runs[0].text,'最后合法文字')
    assert.equal(page.data.complexConfig.cellBorderColor,valid.cellBorderColor)
    assert.equal(page.data.gridUndoCount,1)
    page.handleGridOperation({detail:{type:'undo'}})
    assert.equal(page.data.complexConfig.cells[0].blocks[0].runs[0].text,initial.cells[0].blocks[0].runs[0].text)
    assert.equal(page.data.gridUndoCount,0)

    page.handleGridChange({detail:{config:{...initial,cellBorderColor:'#12'}}})
    page.handleGridOperation({detail:{type:'undo'}})
    assert.deepEqual(page.data.complexConfig,initial)
    assert.equal(page.data.gridUndoCount,0)
    page.handleGridChange({detail:{config:{...initial,cellBorderColor:'#123'}}})
    const corrected=JSON.parse(JSON.stringify(initial));corrected.cells[0].blocks[0].runs[0].text='修正后文字'
    page.handleGridChange({detail:{config:{...corrected,cellBorderColor:'#123456'}}})
    page.handleGridOperation({detail:{type:'undo'}})
    assert.deepEqual(page.data.complexConfig,initial)

    for(let index=0;index<45;index++) {
      const next=JSON.parse(JSON.stringify(page.data.complexConfig))
      next.cells[0].blocks[0].runs[0].text=`步骤${index}`
      page.handleGridChange({detail:{config:next}})
    }
    assert.equal(page.data.gridUndoCount,40)
    page.handleGridChange({detail:{config:{...page.data.complexConfig,cellBorderColor:'#1'}}})
    assert.equal(page.data.gridUndoCount,40)
    page.handleGridOperation({detail:{type:'undo'}})
    assert.equal(page.data.complexConfig.cells[0].blocks[0].runs[0].text,'步骤44')
    assert.equal(page.data.gridUndoCount,40)
    page.handleGridOperation({detail:{type:'undo'}})
    assert.equal(page.data.complexConfig.cells[0].blocks[0].runs[0].text,'步骤43')
    assert.equal(page.data.gridUndoCount,39)
  } finally {delete global.wx}
})

test('选材项使用真实媒体类型、标签与视频封面状态',()=>{
  const rt=runtime();global.wx=rt.wxApi
  try {
    const page=loadPage('portfolio-standard-edit');page.onLoad()
    for(const type of ['WORK_GRID','WORK_LIST','SINGLE_WORK']) {
      const component=page.data.activeComponents.concat(...page.data.draft.config.bottomNav.items.map(item=>item.components||[])).find(item=>item.componentType===type)
      const menu=page.data.draft.config.bottomNav.items.find(item=>(item.components||[]).some(child=>child.componentKey===component.componentKey))
      if(menu) page.handleEditorMenuTap(event({key:menu.key}))
      page.handleComponentTap(event({key:component.componentKey}))
      const video=page.data.workOptions.find(item=>item.id===110)
      assert.equal(video.mediaTypeLabel,'视频')
      assert.equal(video.hasCover,false)
      assert.equal(video.showPlayIndicator,true)
      if(type==='SINGLE_WORK') {
        const animation=page.data.workOptions.find(item=>item.id===109)
        assert.equal(animation.mediaTypeLabel,'动图')
        assert.equal(animation.tagText,'')
      }
    }
    const markup=fs.readFileSync(path.join(ROOT,'pages/mock/portfolio-standard-edit/portfolio-standard-edit.wxml'),'utf8')
    assert.match(markup,/暂无视频封面/)
    assert.match(markup,/component-work-video-indicator/)
  } finally {delete global.wx}
})

test('工作台作品数与作品库和全部标签同源',()=>{
  assert.equal(mock.MOCK_DASHBOARD.metrics.workCount,11)
  assert.equal(mock.MOCK_DASHBOARD.metrics.workCount,mock.MOCK_WORK_LIBRARY.total)
  assert.equal(mock.MOCK_WORK_LIBRARY.filterTags[0].count,mock.MOCK_WORK_LIBRARY.total)
})

test('音频配置关闭保留选择、移除清选择并与预览视频互斥',async()=>{
  const rt=runtime();global.wx=rt.wxApi
  try {
    const edit=loadPage('portfolio-standard-edit');edit.onLoad()
    edit.handleAudioSetting(event({field:'enabled'},true))
    edit.handlePreview()
    const preview=loadPage('portfolio-standard-preview');preview.onLoad({});preview.onShow()
    assert.equal(preview.data.audioPlaying,false)
    for(let turn=0;turn<12;turn++)await Promise.resolve()
    assert.equal(preview.data.audioPlaying,true)
    const menu=preview.data.portfolio.bottomNav.items[1].key
    preview.handlePortfolioMenuChange({detail:{menuKey:menu}})
    assert.equal(preview.data.audioPlaying,false)
    const carousel=preview.data.portfolio.activeComponents.find(x=>x.componentType==='VIDEO_CAROUSEL')
    preview.handleToggleAudio()
    preview.handleWorkTap({detail:{componentKey:carousel.componentKey,workId:110,mediaUrl:'https://wrong.invalid'}})
    assert.equal(preview.data.audioPlaying,false)
    assert.equal(preview.data.videoPreviewVisible,true)
    assert.equal(preview.data.videoPreview.src,mock.MOCK_WORK_LIBRARY.works.find(w=>w.id===110).mediaUrl)
    preview.handleToggleAudio();assert.equal(preview.data.videoPreviewVisible,false)
    preview.onHide();assert.equal(preview.data.audioPlaying,false)
    preview.onUnload()
    edit.handleAudioSetting(event({field:'enabled'},false));assert.equal(edit.data.draft.config.backgroundAudio.workId,108)
    edit.handleAudioSetting(event({field:'remove'}));assert.equal(edit.data.draft.config.backgroundAudio.workId,null)
    edit.onUnload()
  } finally {delete global.wx}
})

test('内部目标只读，不覆盖源草稿；未知目标失败',()=>{
  const rt=runtime();global.wx=rt.wxApi
  try {
    mock.saveMockPortfolioDraft(mock.buildStandardPortfolio())
    const original=JSON.stringify([...rt.storage])
    const preview=loadPage('portfolio-standard-preview');preview.onLoad({demoTarget:'9002'});preview.onShow()
    assert.equal(preview.data.portfolio.title,'演示联系作品集')
    assert.equal(JSON.stringify([...rt.storage]),original)
    preview.onUnload()
    const unknown=loadPage('portfolio-standard-preview');unknown.onLoad({demoTarget:'123'});unknown.onShow()
    assert.equal(unknown.data.errorMessage,'演示作品集暂不可用');unknown.onUnload()
  } finally {delete global.wx}
})

test('损坏与未来草稿保持原存储，背景音频不保存运行状态',()=>{
  const rt=runtime();global.wx=rt.wxApi
  try {
    for(const config of [{bad:true},{config:{components:[],editorSchemaRevision:99}}]) {
      rt.storage.set(mock.MOCK_PORTFOLIO_DRAFT_STORAGE_KEY,config)
      const draft=mock.getMockPortfolioDraft()
      assert.match(draft.draftWarning,/草稿/)
      assert.equal(rt.storage.get(mock.MOCK_PORTFOLIO_DRAFT_STORAGE_KEY),config)
    }
    const config=mock.normalizeMockPortfolioConfig({components:[],backgroundAudio:{enabled:true,workId:108,displayStyle:'DISC',mediaUrl:'bad',playing:true}})
    assert.deepEqual(Object.keys(config.backgroundAudio).sort(),['displayStyle','enabled','workId'])
  } finally {delete global.wx}
})

function filesIn(dir){return fs.readdirSync(dir,{withFileTypes:true}).flatMap(entry=>entry.isDirectory()?filesIn(path.join(dir,entry.name)):[path.join(dir,entry.name)])}
test('递归检查mock依赖与组件注册，无后端能力、跨分包或新业务依赖',()=>{
  const files=[...filesIn(path.join(ROOT,'pages/mock')),...filesIn(path.join(ROOT,'components/mock'))]
  for(const file of files.filter(file=>/\.(js|json|wxml|wxss)$/.test(file))) {
    const source=fs.readFileSync(file,'utf8')
    assert.doesNotMatch(source,/wx\.(?:request|uploadFile|login)\s*\(|utils\/(?:request|session|visitor-session)|\/api\/|wefolio_token/,file)
    if(file.endsWith('.js')) for(const match of source.matchAll(/require\(['"]([^'"]+)['"]\)/g)) {
      const resolved=path.resolve(path.dirname(file),match[1])
      const allowed=resolved.includes('/pages/mock/')||resolved.includes('/components/mock/')||resolved===path.join(ROOT,'utils/portfolio-text-typography')||resolved===path.join(ROOT,'utils/navigation-bar-layout')
      assert.equal(allowed,true,`${file}: ${match[1]}`)
    }
    if(file.endsWith('.json')) for(const target of Object.values(JSON.parse(source).usingComponents||{})) {
      const resolved=target.startsWith('/')?path.join(ROOT,target):path.resolve(path.dirname(file),target)
      for(const ext of ['js','json','wxml','wxss'])assert.equal(fs.existsSync(`${resolved}.${ext}`),true,`${resolved}.${ext}`)
    }
  }
})

test('重置草稿释放正在试听的资源',()=>{
 const rt=runtime();global.wx=rt.wxApi
 try {
  const edit=loadPage('portfolio-standard-edit');edit.onLoad();edit.handleAudioAudition()
  assert.equal(edit.data.audioPlaying,true)
  edit.handleResetDraft()
  assert.equal(edit.data.audioPlaying,false)
  assert.equal(rt.calls.some(call=>call[0]==='audioDestroy'),true)
  edit.onUnload()
 } finally {delete global.wx}
})

test('从内部演示返回保留原菜单，不重新自动播放音频',()=>{
 const rt=runtime();global.wx=rt.wxApi
 try {
  const preview=loadPage('portfolio-standard-preview');preview.onLoad({});preview.onShow()
  const menuKey=preview.data.portfolio.bottomNav.items[1].key
  preview.handlePortfolioMenuChange({detail:{menuKey}})
  const link=preview.data.portfolio.activeComponents.find(x=>x.componentType==='HYPERLINK')
  preview.handleHyperlink({detail:{componentKey:link.componentKey}})
  preview.onHide();preview.onShow()
  assert.equal(preview.data.portfolio.activeMenuKey,menuKey)
  assert.equal(preview.data.audioPlaying,false)
  preview.onUnload()
 } finally {delete global.wx}
})

test('编辑和预览注册平台内置字体，无需正式字体模块或网络',()=>{
 const rt=runtime();const fonts=[]
 rt.wxApi.loadBuiltInFontFace=options=>{fonts.push(options);options.success()}
 global.wx=rt.wxApi
 try {
  const edit=loadPage('portfolio-standard-edit');edit.onLoad()
  const preview=loadPage('portfolio-standard-preview');preview.onLoad({})
  assert.equal(fonts.length,2)
  for(const font of fonts){assert.equal(font.family,'WeFolioWechatSansSS');assert.equal(font.source,'WeChatSansSS');assert.equal(font.global,true)}
  edit.onUnload();preview.onUnload()
 } finally {delete global.wx}
})
