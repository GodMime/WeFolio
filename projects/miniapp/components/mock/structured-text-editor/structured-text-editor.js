const { PORTFOLIO_TEXT_SELECTION_OPTIONS, applyPortfolioFontSelection, buildRemoteFontStyle } = require('../../../utils/portfolio-text-typography')
const FONT_FAMILIES = PORTFOLIO_TEXT_SELECTION_OPTIONS.map(item => item.value)
const FONT_WEIGHTS = ['NORMAL', 'BOLD']
const ALIGNMENTS = ['LEFT', 'CENTER', 'RIGHT']
const BLOCK_TYPES = ['EYEBROW', 'TITLE', 'PARAGRAPH', 'LIST', 'HINT', 'SPACER']
const OPTION_LABELS = {SYSTEM:'系统默认',WECHAT_SANS_SS:'微信字体',NORMAL:'常规',BOLD:'粗体',LEFT:'左对齐',CENTER:'居中',RIGHT:'右对齐',EYEBROW:'眉题',TITLE:'标题',PARAGRAPH:'正文',LIST:'列表',HINT:'提示',SPACER:'留白'}
PORTFOLIO_TEXT_SELECTION_OPTIONS.forEach(item => { OPTION_LABELS[item.value] = item.label + (item.languageLabel ? '（' + item.languageLabel + '）' : '') })
const OPTION_INDEXES = Object.fromEntries([FONT_FAMILIES,FONT_WEIGHTS,ALIGNMENTS,BLOCK_TYPES].flatMap(values=>values.map((value,index)=>[value,index])))
const NUMBERS = ['fontSizeRpx','lineHeight','marginTopRpx','marginBottomRpx','heightRpx']
const clone = value => JSON.parse(JSON.stringify(value || {}))
const summarizeBlock = block => block.type === 'LIST' ? (block.items || []).join(' / ') : block.type === 'SPACER' ? `${block.heightRpx} rpx 留白` : block.content || '空区块'
Component({
  properties:{fontContext:{type:Object,value:{}},fontChoices:{type:Array,value:[]},config:{type:Object,value:{}},error:{type:String,value:''},fontAvailability:{type:Object,value:{SYSTEM:true,WECHAT_SANS_SS:false}}},
  data:{draft:{blocks:[]},tab:'content',selectedBlockIndex:0,selectedBlock:null,blockSummaries:[],fontOptions:[],fontsExpanded:false,fontLabels:FONT_FAMILIES.map(value=>OPTION_LABELS[value]),weightLabels:FONT_WEIGHTS.map(value=>OPTION_LABELS[value]),alignmentLabels:ALIGNMENTS.map(value=>OPTION_LABELS[value]),blockLabels:BLOCK_TYPES.map(value=>OPTION_LABELS[value]),optionLabels:OPTION_LABELS,optionIndexes:OPTION_INDEXES},
  observers:{config(value){this.refreshDerived(clone(value))},fontAvailability(value){this.refreshFontOptions(value)},fontChoices(){this.refreshFontOptions(this.data.fontAvailability)},fontContext(){this.refreshFontOptions(this.data.fontAvailability)}},
  methods:{
    refreshFontOptions(value={}){
      const selected=this.data.selectedBlock||{},current=selected.fontId||selected.fontFamily
      const source=this.data.fontChoices&&this.data.fontChoices.length?this.data.fontChoices.slice():PORTFOLIO_TEXT_SELECTION_OPTIONS.map(item=>({...item,available:item.value==='SYSTEM'||value[item.value]===true}))
      if(selected.fontId && !source.some(item=>item.value===current))source.push({value:current,label:current,remote:true,available:false,hint:'字体标识不可用，当前使用系统字体'})
      this.setData({currentFontStyle:buildRemoteFontStyle(selected,this.data.fontContext),currentFontRepairable:Boolean(selected.fontId && source.some(item=>item.value===selected.fontId && item.repairable)),fontOptions:source.filter(item=>this.data.fontsExpanded||!item.remote||item.value===current).map(item=>{
        if(!item.remote || item.value!==current || !item.available)return {...item}
        const loadState=item.loadStates && item.loadStates[selected.fontWeight==='BOLD'?700:400] || item.loadState
        const hint=!item.versionValid?'字体版本不可用，当前使用系统字体':loadState==='failed'?'字体加载失败，当前使用系统字体':loadState==='loading'?'字体加载中，当前使用系统字体':''
        return {...item,loadState,hint}
      })})
    },
    handleMoreFonts(){this.setData({fontsExpanded:!this.data.fontsExpanded});this.refreshFontOptions(this.data.fontAvailability)},
    handleRepairFont(event){this.triggerEvent('fontrepair',{fontId:event.currentTarget.dataset.value})},
    handleSampleError(event){const value=event.currentTarget.dataset.value;this.setData({fontOptions:this.data.fontOptions.map(item=>item.value===value?{...item,sampleUrl:''}:item)})},
    refreshDerived(draft,index=this.data.selectedBlockIndex){
      const blocks=Array.isArray(draft.blocks)?draft.blocks:[],selectedBlockIndex=blocks.length?Math.max(0,Math.min(Number(index)||0,blocks.length-1)):0
      this.setData({draft,selectedBlockIndex,selectedBlock:blocks[selectedBlockIndex]||null,blockSummaries:blocks.map(block=>({blockKey:block.blockKey,label:OPTION_LABELS[block.type]||block.type,summary:summarizeBlock(block)}))});this.refreshFontOptions(this.data.fontAvailability)
    },
    emitChange(draft){this.refreshDerived(draft);this.triggerEvent('configchange',{config:clone(draft)})},
    handleTab(event){const tab=event.currentTarget.dataset.value;if(!['content','background'].includes(tab)||tab===this.data.tab)return;this.setData({tab});this.triggerEvent('tabchange',{tab})},
    handleSelectBlock(event){this.refreshDerived(clone(this.data.draft),Number(event.currentTarget.dataset.index))},
    handleFontTap(event){
      const value=event.currentTarget.dataset.value,option=this.data.fontOptions.find(item=>item.value===value)
      if(!option||!option.available||!this.data.selectedBlock)return
      this.triggerEvent('fontselect',{fontId:option.remote?value:null,previousFontId:this.data.selectedBlock.fontId});const draft=clone(this.data.draft);Object.assign(draft.blocks[this.data.selectedBlockIndex],applyPortfolioFontSelection(value));this.emitChange(draft)
    },
    handleBlockColor(event){
      if(!this.data.selectedBlock)return
      const draft=clone(this.data.draft);draft.blocks[this.data.selectedBlockIndex].color=event.detail.color;this.emitChange(draft)
    },
    handleInput(event){
      const {index,field,item}=event.currentTarget.dataset, draft=clone(this.data.draft),block=draft.blocks[Number(index)]
      if(!block)return
      if(field==='lineHeight' && event.detail.value==='')delete block.lineHeight
      else if(field==='items')block.items[Number(item)]=event.detail.value
      else block[field]=NUMBERS.includes(field)?Number(event.detail.value):event.detail.value
      this.emitChange(draft)
    },
    handleChoice(event){
      const {index,field}=event.currentTarget.dataset,choices={fontFamily:FONT_FAMILIES,fontWeight:FONT_WEIGHTS,alignment:ALIGNMENTS}
      const draft=clone(this.data.draft)
      if(field==='fontFamily'){this.handleSelectBlock({currentTarget:{dataset:{index}}});this.handleFontTap({currentTarget:{dataset:{value:choices[field][Number(event.detail.value)]}}});return}else draft.blocks[Number(index)][field]=choices[field][Number(event.detail.value)]
      this.emitChange(draft)
    },
    handleAdd(event){this.triggerEvent('operation',{type:'addBlock',blockType:BLOCK_TYPES[Number(event.detail.value)],config:clone(this.data.draft)})},
    handleOperation(event){this.triggerEvent('operation',{...event.currentTarget.dataset,config:clone(this.data.draft)})},
    handleConfirm(){this.triggerEvent('confirm',{config:clone(this.data.draft)})},
    handleCancel(){this.triggerEvent('cancel')}
  }
})
