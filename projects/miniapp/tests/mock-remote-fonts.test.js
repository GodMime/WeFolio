const test = require('node:test')
const assert = require('node:assert/strict')
const { createMockFontSession } = require('../pages/mock/utils/mock-portfolio-fonts')
const catalog = [{ fontId:'ALLURA',fontVersion:'v1',physicalWeights:{400:400,700:400},assets:{400:{url:'https://example.test/allura.woff',sha256:'a'.repeat(64)}} }]
const config = {fonts:{ALLURA:{fontVersion:'v1'}},components:[{config:{content:'Timeless Moments',fontId:'ALLURA'}}]}

function threeFontFixture() {
  const ids = ['ALLURA', 'CORMORANT_GARAMOND', 'ZCOOL_XIAOWEI']
  return { catalog: ids.map((fontId, index) => ({ ...catalog[0], fontId,
    assets: { 400: { url: `https://example.test/${fontId}.woff`, sha256: String(index + 1).repeat(64) } } })),
    config: { fonts: Object.fromEntries(ids.map(id => [id, { fontVersion: 'v1' }])),
      components: ids.map(fontId => ({ config: { fontId } })) } }
}

test('Mock 多字体逐一注册，多次 load 共用队列且每个资源只调用一次', async () => {
  const sample = threeFontFixture(), calls = []
  const session = createMockFontSession({ catalog: sample.catalog, wxApi: { loadFontFace(options) { calls.push(options) } } })
  try {
    const first = session.load(sample.config), repeated = session.load(sample.config)
    assert.equal(calls.length, 1)
    calls[0].success(); assert.equal(calls.length, 2)
    calls[1].success(); assert.equal(calls.length, 3)
    calls[2].success()
    await Promise.all([first, repeated])
    assert.equal(Object.keys(session.context(sample.config.fonts).families).length, 3)
    await session.load(sample.config)
    assert.equal(calls.length, 3)
  } finally { session.destroy() }
})

test('Mock 超时释放队列，排队期间不计超时且迟到成功不干扰下一款', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const sample = threeFontFixture(), calls = []
  const session = createMockFontSession({ catalog: sample.catalog, wxApi: { loadFontFace(options) { calls.push(options) } } })
  try {
    const loading = session.load(sample.config)
    assert.equal(calls.length, 1)
    t.mock.timers.tick(10001)
    assert.equal(calls.length, 2)
    calls[0].success(); assert.equal(calls.length, 2)
    calls[1].fail(); assert.equal(calls.length, 3)
    calls[2].success()
    await loading
    assert.deepEqual(Object.keys(session.context(sample.config.fonts).families), ['ZCOOL_XIAOWEI:v1:400'])
  } finally { session.destroy() }
})

test('Mock 卸载同时结束排队和在途等待，不再注册后续字体', async () => {
  const sample = threeFontFixture(), calls = []
  const session = createMockFontSession({ catalog: sample.catalog, wxApi: { loadFontFace(options) { calls.push(options) } } })
  const loading = session.load(sample.config)
  session.destroy()
  calls[0].success()
  await loading
  assert.equal(calls.length, 1)
  assert.deepEqual(session.context(sample.config.fonts).families, {})
})

test('Mock 基础库新旧版本使用对应字体作用域，保持页面隔离和字重', async () => {
  for (const [version, modern] of [['3.7.8', false], ['3.7.9', true], ['3.17.3', true], ['', false]]) {
    const calls = []
    const session = createMockFontSession({ catalog, wxApi: {
      getAppBaseInfo: () => ({ SDKVersion: version }),
      loadFontFace(options) { calls.push(options); options.success() }
    } })
    try {
      await session.load(config)
      assert.equal(calls.length, 1)
      assert.equal(Object.hasOwn(calls[0], 'scopes'), !modern, version)
      if (!modern) assert.deepEqual(calls[0].scopes, ['webview', 'skyline'])
      assert.equal(calls[0].global, false)
      assert.equal(calls[0].desc.weight, '400')
      assert.ok(session.context(config.fonts).families['ALLURA:v1:400'])
    } finally { session.destroy() }
  }
})

function loadPage(name) {
  let definition
  const file=require.resolve(`../pages/mock/${name}/${name}`)
  const previous=global.Page
  global.Page=value=>{definition=value}
  delete require.cache[file]
  try {require(file)} finally {if(previous)global.Page=previous;else delete global.Page}
  return {...definition,data:JSON.parse(JSON.stringify(definition.data)),setData(patch,callback){Object.assign(this.data,patch);if(callback)callback()}}
}

test('Mock 页面首次选择、确认、取消、网格撤销与预览使用相同根版本', async () => {
  const version='gf-809e4d8b8d7e-r1'
  const localCatalog=[{...catalog[0],fontVersion:version}]
  const storage=new Map(),calls=[]
  const oldWx=global.wx
  global.wx={getStorageSync:key=>storage.get(key),setStorageSync:(key,value)=>storage.set(key,value),loadFontFace(options){calls.push(options);options.success()},showToast(){},navigateTo(){}}
  try {
    for(const type of ['TEXT_SECTION','STRUCTURED_TEXT_SECTION','TEXT_GRID']) {
      const page=loadPage('portfolio-standard-edit')
      page.onLoad()
      page.mockFontSession=createMockFontSession({catalog:localCatalog,wxApi:wx,onChange:()=>page.refreshMockFonts(false)})
      page.handleSelectComponent({currentTarget:{dataset:{type}}})
      page.handleMoreMockFonts()
      assert.equal(page.data.textSectionFontOptions.find(item=>item.value==='ALLURA').available,true)
      if(type==='TEXT_SECTION')page.handleTextSectionFontTap({currentTarget:{dataset:{value:'ALLURA'}}})
      else {
        page.handleMockFontSelect({detail:{fontId:'ALLURA'}})
        const candidate=JSON.parse(JSON.stringify(page.data.complexConfig))
        if(type==='STRUCTURED_TEXT_SECTION') {candidate.blocks[0].fontId='ALLURA';candidate.blocks[0].content='Timeless Moments';page.handleComplexChange({detail:{config:candidate}})}
        else {candidate.cells[0].blocks[0].runs[0].fontId='ALLURA';candidate.cells[0].blocks[0].runs[0].text='Timeless Moments';page.handleGridChange({detail:{config:candidate}})}
      }
      await Promise.resolve()
      assert.equal(page.mockCandidateVersions.ALLURA.fontVersion,version)
      assert.equal(page.data.draft.config.fonts,undefined)
      const key=page.data.selectedComponentKey
      page.handleConfirmComponentEditSheet()
      assert.equal(page.data.componentEditSheetVisible,false)
      assert.equal(page.data.draft.config.fonts.ALLURA.fontVersion,version)
      page.handleComponentTap({currentTarget:{dataset:{key}}})
      page.mockCandidateVersions.ALLURA.fontVersion='cancelled'
      page.handleCloseComponentEditSheet()
      assert.equal(page.data.draft.config.fonts.ALLURA.fontVersion,version)
      page.handlePreview()
      const preview=loadPage('portfolio-standard-preview')
      preview.mockFontSession=createMockFontSession({catalog:localCatalog,wxApi:wx,onChange:()=>preview.refreshMockFontRender()})
      preview.refreshPreview()
      assert.equal(preview.mockFontConfig.fonts.ALLURA.fontVersion,version)
      const component=preview.data.portfolio.components.find(item=>item.componentKey===key)
      const style=type==='TEXT_GRID'?component.viewModel.cells[0].blocks[0].runs[0].style:component.viewModel.blocks[0].style
      assert.match(style,/WF_MOCK_/)
      page.onUnload();preview.mockFontSession.destroy()
      storage.clear()
    }
    assert.equal(calls.length,6)
    calls.forEach(call => { assert.deepEqual(call.scopes, ['webview', 'skyline']); assert.equal(call.global, false) })
  } finally {global.wx=oldWx}
})

test('Mock 首次未加载可选，加载失败仍可选且同页同资源不再加载', async () => {
  let count = 0
  const session = createMockFontSession({catalog,wxApi:{loadFontFace(options){count++;options.fail()}}})
  assert.equal(session.options({expanded:true}).find(item=>item.value==='ALLURA').selectable,true)
  assert.equal(session.options({expanded:true}).find(item=>item.value==='ALLURA').loadState,'idle')
  await session.load(config)
  const failed = session.options({expanded:true}).find(item=>item.value==='ALLURA')
  assert.equal(failed.loadState,'failed')
  assert.equal(failed.available,true)
  assert.deepEqual(session.context(config.fonts).families,{})
  await session.load(config)
  assert.equal(count,1)
})

test('Mock 网格撤销同时恢复根版本，保留原有历史 API', () => {
  const grid = require('../pages/mock/utils/mock-portfolio-text-grid')
  const first = grid.createMockTextGrid()
  first.cells[0].blocks[0].runs[0].fontId = 'ALLURA'
  const history = grid.createMockGridHistory(first,{ALLURA:{fontVersion:'old'}})
  const removed = JSON.parse(JSON.stringify(first))
  removed.cells[0].blocks[0].runs[0].fontId = null
  history.apply(removed,{})
  assert.deepEqual(history.getFonts(),{})
  assert.equal(history.undo().cells[0].blocks[0].runs[0].fontId,'ALLURA')
  assert.deepEqual(history.getFonts(),{ALLURA:{fontVersion:'old'}})
})

test('Mock 成功加载后仅相同固定版本使用字形，卸载迟到结果不通知', async () => {
  const callbacks = []
  let changed = 0
  const session = createMockFontSession({catalog,wxApi:{loadFontFace(options){callbacks.push(options)}},onChange(){changed++}})
  const pending = session.load(config)
  assert.equal(session.options({expanded:true}).find(item=>item.value==='ALLURA').loadState,'loading')
  callbacks[0].success()
  await pending
  assert.ok(session.context(config.fonts).families['ALLURA:v1:400'])
  await session.load({...config,fonts:{ALLURA:{fontVersion:'unknown'}}})
  assert.equal(callbacks.length,1)
  assert.equal(changed,2)
  const late = createMockFontSession({catalog,wxApi:{loadFontFace(options){callbacks.push(options)}},onChange(){changed++}})
  const latePending = late.load(config)
  const beforeDestroy=changed
  late.destroy()
  callbacks[1].success()
  await latePending
  assert.equal(changed,beforeDestroy)
})

test('Mock 无效清单与不支持设备禁止新增选择，默认仅旧字体和当前项', () => {
  const unsupported = createMockFontSession({catalog,wxApi:{}})
  assert.equal(unsupported.options({expanded:true}).find(item=>item.value==='ALLURA').available,false)
  const invalid = createMockFontSession({catalog:[{...catalog[0],assets:{400:{url:'',sha256:'a'.repeat(64)}}}],wxApi:{loadFontFace(){}}})
  assert.equal(invalid.options({expanded:true}).find(item=>item.value==='ALLURA').available,false)
  assert.deepEqual(unsupported.options({selected:'ALLURA'}).map(item=>item.value),['SYSTEM','WECHAT_SANS_SS','ALLURA'])
  const unknown=unsupported.options({selected:'UNKNOWN_FONT'}).find(item=>item.value==='UNKNOWN_FONT')
  assert.equal(unknown.available,false)
})

test('Mock 根未知版本阻止另一节点新增选择，但保留当前选择的显式修复能力', () => {
  const session=createMockFontSession({catalog,wxApi:{loadFontFace(){}}})
  for(const version of [{fontVersion:'unknown-v'},{},null]) {
    const versions={ALLURA:version}
    const other=session.options({expanded:true,selected:'SYSTEM',versions}).find(item=>item.value==='ALLURA')
    assert.equal(other.selectable,false)
    assert.equal(other.available,false)
    const saved=session.options({selected:'ALLURA',versions}).find(item=>item.value==='ALLURA')
    assert.equal(saved.repairable,true)
    assert.equal(saved.value,'ALLURA')
    const repaired=session.versions({fontId:'ALLURA'},versions,{repair:'ALLURA'})
    assert.equal(session.options({expanded:true,versions:repaired}).find(item=>item.value==='ALLURA').selectable,true)
  }
  assert.equal(session.options({expanded:true}).find(item=>item.value==='ALLURA').selectable,true)
})

test('Mock 页面不会向系统文字写入根未知版本字体，已有选择可直接修复', () => {
  const previousWx=global.wx
  global.wx={getStorageSync(){},loadFontFace(options){options.success()},showToast(){}}
  try {
    const page=loadPage('portfolio-standard-edit')
    page.onLoad()
    page.mockFontSession=createMockFontSession({catalog,wxApi:wx,onChange:()=>page.refreshMockFonts(false)})
    page.handleSelectComponent({currentTarget:{dataset:{type:'TEXT_SECTION'}}})
    page.mockCandidateVersions={ALLURA:{fontVersion:'unknown-v'}}
    page.handleMoreMockFonts()
    page.handleTextSectionFontTap({currentTarget:{dataset:{value:'ALLURA'}}})
    assert.equal(page.data.componentConfig.fontId,undefined)
    assert.equal(page.mockCandidateVersions.ALLURA.fontVersion,'unknown-v')
    page.setData({componentConfig:{...page.data.componentConfig,fontId:'ALLURA'},textSectionForm:{...page.data.textSectionForm,fontId:'ALLURA'}})
    page.refreshMockFonts()
    assert.equal(page.data.mockFontRepairable,true)
    page.handleMockFontRepair({detail:{fontId:'ALLURA'}})
    assert.equal(page.mockCandidateVersions.ALLURA.fontVersion,'v1')
    page.onUnload()
  } finally {global.wx=previousWx}
})

test('Mock 结构化和网格已存未知版本保留选中，并发出显式修复事件', () => {
  const text=require('../pages/mock/utils/mock-portfolio-text')
  const grid=require('../pages/mock/utils/mock-portfolio-text-grid')
  const session=createMockFontSession({catalog,wxApi:{loadFontFace(){}}})
  const fontChoices=session.options({expanded:true,versions:{ALLURA:{fontVersion:'unknown-v'}}})
  for(const name of ['structured-text-editor','text-grid-editor']) {
    const file=require.resolve(`../components/mock/${name}/${name}`)
    const previous=global.Component
    let definition
    global.Component=value=>{definition=value}
    delete require.cache[file]
    try {require(file)} finally {if(previous)global.Component=previous;else delete global.Component}
    const config=name==='structured-text-editor'?{blocks:[{...text.createMockStructuredBlock(),fontId:'ALLURA',content:'Saved'}]}:grid.createMockTextGrid()
    if(name==='text-grid-editor')config.cells[0].blocks[0].runs[0].fontId='ALLURA'
    const events=[]
    const instance={...definition.methods,data:{...JSON.parse(JSON.stringify(definition.data)),fontChoices,fontContext:{}},
      setData(patch){Object.assign(this.data,patch)},triggerEvent(name,detail){events.push({name,detail})}}
    definition.observers.config.call(instance,config)
    assert.equal(instance.data.currentFontRepairable,true)
    assert.equal(instance.data.fontOptions.find(item=>item.value==='ALLURA').available,false)
    instance.handleFontTap({currentTarget:{dataset:{value:'ALLURA'}}})
    assert.equal(events.length,0)
    instance.handleRepairFont({currentTarget:{dataset:{value:'ALLURA'}}})
    assert.deepEqual(events,[{name:'fontrepair',detail:{fontId:'ALLURA'}}])
    const selected=name==='structured-text-editor'?instance.data.selectedBlock:instance.data.focusedRun
    assert.equal(selected.fontId,'ALLURA')
  }
})

test('Mock 六字体粗细请求只加载九个物理资源，失败后重开不重发', async () => {
  const ids=['CORMORANT_GARAMOND','MANROPE','ALLURA','SOURCE_HAN_SERIF_SC','ZCOOL_XIAOWEI','LXGW_WENKAI']
  const catalog=ids.map((fontId,index)=>({fontId,fontVersion:'fixed',physicalWeights:{400:400,700:[2,4,5].includes(index)?400:700},
    assets:Object.fromEntries(([2,4,5].includes(index)?[400]:[400,700]).map(weight=>[weight,{url:`https://example.test/${fontId}-${weight}.woff`,sha256:String(index+1).repeat(63)+(weight===400?'a':'b')}]))}))
  const calls=[]
  const session=createMockFontSession({catalog,wxApi:{loadFontFace(options){calls.push(options);options.fail()}}})
  const config={fonts:Object.fromEntries(ids.map(id=>[id,{fontVersion:'fixed'}])),components:ids.flatMap(fontId=>['NORMAL','BOLD'].map(fontWeight=>({config:{fontId,fontWeight}})))}
  await session.load(config)
  assert.equal(calls.length,9)
  session.options({expanded:false,selected:'ALLURA'})
  session.options({expanded:true})
  await session.load(config)
  assert.equal(calls.length,9)
  assert.equal(session.options({expanded:true}).filter(item=>item.remote&&item.available).length,6)
})

test('Mock 已存未知版本仅显式修复，取消与网格撤销恢复原版本', () => {
  const oldWx=global.wx
  let count=0
  global.wx={getStorageSync(){},showToast(){},loadFontFace(options){count++;options.success()}}
  try {
    const page=loadPage('portfolio-standard-edit')
    page.onLoad()
    page.mockFontSession=createMockFontSession({catalog,wxApi:wx,onChange:()=>page.refreshMockFonts(false)})
    page.handleSelectComponent({currentTarget:{dataset:{type:'TEXT_GRID'}}})
    const original=JSON.parse(JSON.stringify(page.data.complexConfig))
    original.cells[0].blocks[0].runs[0].text='Timeless Moments'
    original.cells[0].blocks[0].runs[0].fontId='ALLURA'
    page.setData({complexConfig:original})
    page.mockCandidateVersions={ALLURA:{fontVersion:'unknown'}}
    page.refreshMockFonts()
    assert.equal(count,0)
    page.handleMockFontSelect({detail:{fontId:'ALLURA',previousFontId:'ALLURA'}})
    assert.equal(page.mockCandidateVersions.ALLURA.fontVersion,'unknown')
    page.handleMockFontRepair({detail:{fontId:'ALLURA'}})
    assert.equal(page.mockCandidateVersions.ALLURA.fontVersion,'v1')
    assert.equal(count,1)
    page.handleGridOperation({detail:{type:'undo'}})
    assert.equal(page.mockCandidateVersions.ALLURA.fontVersion,'unknown')
    const cleared=JSON.parse(JSON.stringify(original))
    cleared.cells[0].blocks[0].runs[0].fontId=null
    page.handleGridChange({detail:{config:cleared}})
    page.handleGridOperation({detail:{type:'undo'}})
    assert.equal(page.data.complexConfig.cells[0].blocks[0].runs[0].fontId,'ALLURA')
    assert.equal(page.mockCandidateVersions.ALLURA.fontVersion,'unknown')
    page.handleCloseComponentEditSheet()
    assert.equal(page.data.draft.config.fonts,undefined)
    page.onUnload()
  } finally {global.wx=oldWx}
})

test('Mock 三类渲染与跨菜单预览使用页面字体上下文', () => {
  const mock = require('../pages/mock/utils/mock-experience')
  const text = require('../pages/mock/utils/mock-portfolio-text')
  const grid = require('../pages/mock/utils/mock-portfolio-text-grid')
  const version = 'gf-809e4d8b8d7e-r1'
  const context = {versions:{ALLURA:{fontVersion:version}},families:{[`ALLURA:${version}:400`]:'WF_MOCK_sample'}}
  const plain = text.buildMockTextViewModel({content:'Timeless Moments',fontId:'ALLURA'},[],{fontContext:context})
  assert.match(plain.blocks[0].style,/WF_MOCK_sample/)
  const structured = text.buildMockTextViewModel({blocks:[{...text.createMockStructuredBlock(),content:'Timeless Moments',fontId:'ALLURA'}]},[],{structured:true,fontContext:context})
  assert.match(structured.blocks[0].style,/WF_MOCK_sample/)
  const cfg = grid.createMockTextGrid()
  cfg.cells[0].blocks[0].runs[0].fontId = 'ALLURA'
  assert.match(grid.buildMockGridViewModel(cfg,'light',686,{},context).cells[0].blocks[0].runs[0].style,/WF_MOCK_sample/)
  const portfolio = JSON.parse(JSON.stringify(mock.MOCK_STANDARD_PORTFOLIO.config))
  portfolio.components = [{componentType:'TEXT_SECTION',componentKey:'test',config:{content:'Timeless Moments',fontId:'ALLURA'}}]
  assert.match(mock.buildMockPortfolioRenderData(portfolio,context).components[0].viewModel.blocks[0].style,/WF_MOCK_sample/)
})

test('Mock 字体迟到回调不重置预览菜单和作品分组', () => {
  const mock=require('../pages/mock/utils/mock-experience')
  const page=loadPage('portfolio-standard-preview')
  page.mockFontConfig=JSON.parse(JSON.stringify(mock.MOCK_STANDARD_PORTFOLIO.config))
  page.mockFontConfig.bottomNav.items[1].components.find(item=>item.componentType==='WORK_GRID').config.groups.push({groupKey:'second',name:'第二组',workIds:[101]})
  page.mockFontSession=createMockFontSession({catalog,wxApi:{}})
  page.setData({portfolio:mock.buildMockPortfolioRenderData(page.mockFontConfig)})
  const target=page.data.portfolio.bottomNav.items.find(item=>(item.components||[]).some(component=>component.groups && component.groups.length>1))
  assert.ok(target)
  page.setData({portfolio:mock.switchMockPortfolioMenu(page.data.portfolio,target.key)})
  const component=page.data.portfolio.activeComponents.find(item=>item.groups && item.groups.length>1)
  const groupKey=component.groups[1].groupKey
  page.handleDisplayTagTap({detail:{componentKey:component.componentKey,groupKey}})
  page.refreshMockFontRender()
  assert.equal(page.data.portfolio.activeMenuKey,target.key)
  assert.equal(page.data.portfolio.activeComponents.find(item=>item.componentKey===component.componentKey).activeGroup.groupKey,groupKey)
})
