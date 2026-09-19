const TREATMENTS = ['ORIGINAL','DARK_MASK','GRADIENT']
const ALIGNMENTS = ['TOP','CENTER','BOTTOM']
const OPTION_LABELS = {ORIGINAL:'原图直出',DARK_MASK:'全局暗色遮罩',GRADIENT:'固定渐进遮罩',TOP:'置顶',CENTER:'居中',BOTTOM:'下沉'}
const OPTION_INDEXES = Object.fromEntries([TREATMENTS,ALIGNMENTS].flatMap(values=>values.map((value,index)=>[value,index])))
const clone = value => JSON.parse(JSON.stringify(value || {}))
const filterWorks = (works,keyword='') => {
  const value=String(keyword || '').trim().toLowerCase()
  return (Array.isArray(works)?works:[]).filter(work=>!value || String(work.title || '').toLowerCase().includes(value))
}
Component({
  properties:{config:{type:Object,value:{}},works:{type:Array,value:[]},structured:{type:Boolean,value:false}},
  data:{draft:{},pickerOpen:false,keyword:'',displayWorks:[],treatmentLabels:TREATMENTS.map(value=>OPTION_LABELS[value]),alignmentLabels:ALIGNMENTS.map(value=>OPTION_LABELS[value]),optionLabels:OPTION_LABELS,optionIndexes:OPTION_INDEXES},
  observers:{
    config(value){this.setData({draft:clone(value),pickerOpen:false,keyword:'',displayWorks:filterWorks(this.data.works)})},
    works(value){this.setData({displayWorks:filterWorks(value,this.data.keyword)})}
  },
  methods:{
    change(patch){const draft={...clone(this.data.draft),...patch};this.setData({draft});this.triggerEvent('configchange',{config:draft})},
    handleSwitch(event){const backgroundEnabled=event.detail.value;this.setData({pickerOpen:backgroundEnabled?this.data.pickerOpen:false,keyword:backgroundEnabled?this.data.keyword:'',displayWorks:filterWorks(this.data.works)});this.change({backgroundEnabled})},
    handleTogglePicker(){if(!this.data.draft.backgroundEnabled)return;if(this.data.pickerOpen){this.handleClosePicker();return}this.setData({pickerOpen:true,keyword:'',displayWorks:filterWorks(this.data.works)})},
    handleClosePicker(){this.setData({pickerOpen:false})},
    handleKeyword(event){const keyword=event.detail.value || '';this.setData({keyword,displayWorks:filterWorks(this.data.works,keyword)})},
    handleSearch(){this.setData({displayWorks:filterWorks(this.data.works,this.data.keyword)})},
    handleWork(event){this.change({backgroundWorkId:event.currentTarget.dataset.id});this.setData({pickerOpen:false})},
    handleTreatment(event){this.change({backgroundTreatment:TREATMENTS[Number(event.detail.value)]})},
    handleAlignment(event){this.change({verticalAlignment:ALIGNMENTS[Number(event.detail.value)]})},
    handleConfirm(){const config=clone(this.data.draft);if(!config.backgroundEnabled)delete config.backgroundWorkId;this.triggerEvent('confirm',{config})},
    handleCancel(){this.triggerEvent('cancel')}
  }
})
