const clone=value=>JSON.parse(JSON.stringify(value||{}))
const CHOICES={fontFamily:['SYSTEM','WECHAT_SANS_SS'],fontWeight:['NORMAL','BOLD'],alignment:['LEFT','CENTER','RIGHT'],verticalAlignment:['TOP','CENTER','BOTTOM']}
const OPTION_LABELS={SYSTEM:'系统默认',WECHAT_SANS_SS:'微信字体',NORMAL:'常规 400',BOLD:'粗体 700',LEFT:'左',CENTER:'中',RIGHT:'右',TOP:'顶',BOTTOM:'底'}
const CHOICE_LABELS=Object.fromEntries(Object.entries(CHOICES).map(([field,values])=>[field,values.map(value=>OPTION_LABELS[value])]))
const OPTION_INDEXES=Object.fromEntries(Object.values(CHOICES).flatMap(values=>values.map((value,index)=>[value,index])))
const TEXT_FIELDS=['text','color','cellBackground','cellBorderColor']
const STEP_FIELDS={fontSizeRpx:{min:10,max:96},marginTopRpx:{min:0,max:128},marginBottomRpx:{min:0,max:128},gapRpx:{min:0,max:48},cellPaddingRpx:{min:0,max:48},cellRadiusRpx:{min:0,max:48},horizontalMarginRpx:{min:0,max:96},verticalMarginRpx:{min:0,max:96},cellBorderWidthRpx:{min:1,max:12},rowMinHeightsRpx:{min:80,max:600},lineHeight:{min:0.5,max:3}}
Component({
  properties:{config:{type:Object,value:{}},error:{type:String,value:''},undoCount:{type:Number,value:0},previewViewModel:{type:Object,value:{cells:[]}},fontAvailability:{type:Object,value:{SYSTEM:true,WECHAT_SANS_SS:false}}},
  data:{draft:{cells:[]},tab:'layout',focusedCellIndex:0,focusedBlockIndex:0,focusedRunIndex:0,focusedCell:null,focusedBlock:null,focusedRun:null,fontOptions:[],selectedKeys:[],selectedMap:{},choiceLabels:CHOICE_LABELS,optionLabels:OPTION_LABELS,optionIndexes:OPTION_INDEXES},
  observers:{config(value){
    const draft=clone(value),existingKeys=new Set((draft.cells||[]).map(cell=>cell.cellKey))
    // 合并、缩放和撤销后只保留仍存在的选择，避免隐藏旧键阻断后续编辑。
    const selectedKeys=(this.data.selectedKeys||[]).filter(key=>existingKeys.has(key))
    this.refreshFocus(draft,this.data.focusedCellIndex,this.data.focusedBlockIndex,this.data.focusedRunIndex,{selectedKeys,selectedMap:Object.fromEntries(selectedKeys.map(key=>[key,true]))})
  },fontAvailability(value){this.refreshFontOptions(value)}},
  methods:{
    refreshFontOptions(value={}){this.setData({fontOptions:CHOICES.fontFamily.map(font=>({value:font,label:OPTION_LABELS[font],available:font==='SYSTEM'||value[font]===true}))})},
    refreshFocus(draft,cellIndex=this.data.focusedCellIndex,blockIndex=this.data.focusedBlockIndex,runIndex=this.data.focusedRunIndex,extra={}){
      const cells=Array.isArray(draft.cells)?draft.cells:[],focusedCellIndex=cells.length?Math.max(0,Math.min(Number(cellIndex)||0,cells.length-1)):0
      const focusedCell=cells[focusedCellIndex]||null,blocks=focusedCell&&Array.isArray(focusedCell.blocks)?focusedCell.blocks:[]
      const focusedBlockIndex=blocks.length?Math.max(0,Math.min(Number(blockIndex)||0,blocks.length-1)):0,focusedBlock=blocks[focusedBlockIndex]||null
      const runs=focusedBlock&&Array.isArray(focusedBlock.runs)?focusedBlock.runs:[],focusedRunIndex=runs.length?Math.max(0,Math.min(Number(runIndex)||0,runs.length-1)):0
      this.setData({draft,focusedCellIndex,focusedBlockIndex,focusedRunIndex,focusedCell,focusedBlock,focusedRun:runs[focusedRunIndex]||null,...extra})
    },
    emitChange(draft){this.refreshFocus(draft);this.triggerEvent('configchange',{config:clone(draft)})},
    handleTab(event){const tab=event.currentTarget.dataset.value;if(!['layout','content','appearance'].includes(tab)||tab===this.data.tab)return;this.setData({tab});this.triggerEvent('tabchange',{tab})},
    handleFocusCell(event){this.refreshFocus(clone(this.data.draft),Number(event.currentTarget.dataset.index),0,0)},
    handleSelectBlock(event){this.refreshFocus(clone(this.data.draft),this.data.focusedCellIndex,Number(event.currentTarget.dataset.index),0)},
    handleSelectRun(event){this.refreshFocus(clone(this.data.draft),this.data.focusedCellIndex,this.data.focusedBlockIndex,Number(event.currentTarget.dataset.index))},
    handleFontTap(event){
      const value=event.currentTarget.dataset.value,option=this.data.fontOptions.find(item=>item.value===value)
      if(!option||!option.available||!this.data.focusedRun)return
      const draft=clone(this.data.draft);draft.cells[this.data.focusedCellIndex].blocks[this.data.focusedBlockIndex].runs[this.data.focusedRunIndex].fontFamily=value;this.emitChange(draft)
    },
    handleRunColor(event){if(!this.data.focusedRun)return;const draft=clone(this.data.draft);draft.cells[this.data.focusedCellIndex].blocks[this.data.focusedBlockIndex].runs[this.data.focusedRunIndex].color=event.detail.color;this.emitChange(draft)},
    handleGridColor(event){const field=event.currentTarget.dataset.field;if(!['cellBackground','cellBorderColor'].includes(field))return;this.emitChange({...clone(this.data.draft),[field]:event.detail.color})},
    handleStep(event){
      const {scope,field,arrayIndex}=event.currentTarget.dataset,range=STEP_FIELDS[field]
      if(!range||(field==='cellBorderWidthRpx'&&!this.data.draft.cellBorder))return
      const draft=clone(this.data.draft),delta=Number(event.currentTarget.dataset.delta)
      let target=draft,key=field
      if(scope==='run')target=draft.cells[this.data.focusedCellIndex].blocks[this.data.focusedBlockIndex].runs[this.data.focusedRunIndex]
      else if(scope==='block')target=draft.cells[this.data.focusedCellIndex].blocks[this.data.focusedBlockIndex]
      else if(scope==='row'){target=draft.rowMinHeightsRpx;key=Number(arrayIndex)}
      const fallback=field==='lineHeight'?1.5:range.min,current=Number(target[key])
      const next=Math.max(range.min,Math.min(range.max,(Number.isFinite(current)?current:fallback)+delta))
      target[key]=field==='lineHeight'?Number(next.toFixed(1)):next
      this.emitChange(draft)
    },
    handleBorderWidth(event){if(!this.data.draft.cellBorder)return;this.emitChange({...clone(this.data.draft),cellBorderWidthRpx:Number(event.detail.value)})},
    handleInput(event){
      const {cellIndex,blockIndex,runIndex,field,arrayIndex}=event.currentTarget.dataset,draft=clone(this.data.draft)
      let target=draft
      if(cellIndex!==undefined)target=target.cells[Number(cellIndex)]
      if(blockIndex!==undefined)target=target.blocks[Number(blockIndex)]
      if(runIndex!==undefined)target=target.runs[Number(runIndex)]
      const value=TEXT_FIELDS.includes(field)?event.detail.value:Number(event.detail.value)
      if(field==='lineHeight' && event.detail.value==='')delete target.lineHeight
      else if(arrayIndex!==undefined)target[field][Number(arrayIndex)]=value
      else target[field]=value
      this.emitChange(draft)
    },
    handleChoice(event){
      const {cellIndex,blockIndex,runIndex,field}=event.currentTarget.dataset,draft=clone(this.data.draft)
      let target=draft.cells[Number(cellIndex)]
      if(blockIndex!==undefined)target=target.blocks[Number(blockIndex)]
      if(runIndex!==undefined)target=target.runs[Number(runIndex)]
      target[field]=CHOICES[field][Number(event.detail.value)]
      this.emitChange(draft)
    },
    handleBorder(event){this.emitChange({...clone(this.data.draft),cellBorder:event.detail.value})},
    handlePreviewMeasure(event){this.triggerEvent('previewmeasure',event.detail)},
    handleSelect(event){
      const key=event.currentTarget.dataset.key,selectedKeys=this.data.selectedKeys.includes(key)?this.data.selectedKeys.filter(item=>item!==key):[...this.data.selectedKeys,key]
      this.setData({selectedKeys,selectedMap:Object.fromEntries(selectedKeys.map(item=>[item,true]))})
    },
    handleDimension(event){this.setData({[event.currentTarget.dataset.field]:Number(event.detail.value)})},
    handleOperation(event){this.triggerEvent('operation',{...event.currentTarget.dataset,selectedKeys:[...this.data.selectedKeys],cellKey:this.data.selectedKeys[0],rows:this.data.rows||this.data.draft.rows,columns:this.data.columns||this.data.draft.columns,config:clone(this.data.draft)})},
    handleConfirm(){this.triggerEvent('confirm',{config:clone(this.data.draft)})},
    handleCancel(){this.triggerEvent('cancel')}
  }
})
