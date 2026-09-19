// Mock 网格的配置与不可变编辑操作；不依赖正式作品集模块。
const {copy,integer,validLineHeight,mockTextStyle} = require('./mock-portfolio-text')
const MAX_HISTORY = 40
const COLOR = /^(AUTO|#[\da-fA-F]{6})$/
let serial = 0
const KEY_SESSION = Date.now().toString(36) + Math.random().toString(36).slice(2, 7)
const key = type => `mock_${type}_${KEY_SESSION}_${++serial}`
const order = (a,b) => a.row-b.row || a.column-b.column
const hasText = cell => cell.blocks.some(block=>block.runs.some(run=>run.text.length>0))
function createMockGridRun() { return {runKey:key('run'),text:'',fontFamily:'SYSTEM',fontSizeRpx:28,fontWeight:'NORMAL',color:'AUTO'} }
function createMockGridBlock() { return {blockKey:key('block'),alignment:'CENTER',marginTopRpx:0,marginBottomRpx:12,runs:[createMockGridRun()]} }
function createMockGridCell(row,column) {return {cellKey:key('cell'),row,column,rowSpan:1,columnSpan:1,verticalAlignment:'CENTER',blocks:[createMockGridBlock()]} }
function createMockTextGrid(rows=2,columns=2) {
  if(!integer(rows,1,8)||!integer(columns,1,5)) throw new Error('网格须为 1–8 行、1–5 列')
  return {rows,columns,columnWeights:Array(columns).fill(1),rowMinHeightsRpx:Array(rows).fill(180),gapRpx:16,cellPaddingRpx:24,cellRadiusRpx:24,cellBorder:false,cellBorderWidthRpx:1,cellBorderColor:'#D7DADD',cellBackground:'AUTO',horizontalMarginRpx:0,verticalMarginRpx:0,cells:Array.from({length:rows*columns},(_,index)=>createMockGridCell(Math.floor(index/columns),index%columns))}
}
function normalizeMockTextGrid(config={}) {
  return Object.keys(config).length ? {cellBorderWidthRpx:1,cellBorderColor:'#D7DADD',horizontalMarginRpx:0,verticalMarginRpx:0,...copy(config)} : createMockTextGrid()
}
function validateMockTextGrid(raw,{requireText=false}={}) {
  if(!raw || typeof raw!=='object') return '网格配置无效'
  const value=normalizeMockTextGrid(raw)
  if(!integer(value.rows,1,8)||!integer(value.columns,1,5)) return '网格须为 1–8 行、1–5 列'
  if(!Array.isArray(value.columnWeights)||value.columnWeights.length!==value.columns||value.columnWeights.some(item=>!integer(item,1,4))) return '列宽比例须为 1–4'
  if(!Array.isArray(value.rowMinHeightsRpx)||value.rowMinHeightsRpx.length!==value.rows||value.rowMinHeightsRpx.some(item=>!integer(item,80,600))) return '最小行高须为 80–600 rpx'
  if(['gapRpx','cellPaddingRpx','cellRadiusRpx'].some(field=>!integer(value[field],0,48))) return '格间距、内边距与圆角须为 0–48 rpx'
  if(['horizontalMarginRpx','verticalMarginRpx'].some(field=>!integer(value[field],0,96)) || !integer(value.cellBorderWidthRpx,1,12)) return '留白或边框宽度无效'
  if(typeof value.cellBorder!=='boolean'||!COLOR.test(value.cellBorderColor)||!COLOR.test(value.cellBackground)) return '网格颜色或边框无效'
  if(!Array.isArray(value.cells)||!value.cells.length) return '网格缺少单元格'
  const coverage=Array(value.rows*value.columns).fill(0),keys=new Set()
  let count=0
  const validKey=item=>typeof item==='string'&&item.length>0&&!keys.has(item)&&(keys.add(item),true)
  for(const cell of value.cells) {
    if(!cell||!validKey(cell.cellKey)||!integer(cell.row,0,value.rows-1)||!integer(cell.column,0,value.columns-1)||!integer(cell.rowSpan,1,value.rows-cell.row)||!integer(cell.columnSpan,1,value.columns-cell.column)) return '单元格位置或标识无效'
    if(!['TOP','CENTER','BOTTOM'].includes(cell.verticalAlignment)) return '单元格垂直对齐无效'
    for(let row=cell.row;row<cell.row+cell.rowSpan;row++) for(let column=cell.column;column<cell.column+cell.columnSpan;column++) coverage[row*value.columns+column]++
    if(!Array.isArray(cell.blocks)||cell.blocks.length<1||cell.blocks.length>8) return '每格须为 1–8 段文字'
    for(const block of cell.blocks) {
      if(!block||!validKey(block.blockKey)||!['LEFT','CENTER','RIGHT'].includes(block.alignment)||!integer(block.marginTopRpx,0,128)||!integer(block.marginBottomRpx,0,128)) return '段落样式无效'
      if(Object.prototype.hasOwnProperty.call(block,'lineHeight')&&!validLineHeight(block.lineHeight)) return '行间距须为 0.5–3.0，步长 0.1'
      if(!Array.isArray(block.runs)||block.runs.length<1||block.runs.length>8) return '每段须为 1–8 个文字片段'
      for(const run of block.runs) {
        if(!run||!validKey(run.runKey)||typeof run.text!=='string'||!['SYSTEM','WECHAT_SANS_SS'].includes(run.fontFamily)||!integer(run.fontSizeRpx,10,96)||!['NORMAL','BOLD'].includes(run.fontWeight)||!COLOR.test(run.color)) return '文字片段样式无效'
        count+=Array.from(run.text).length
      }
    }
  }
  if(coverage.some(item=>item!==1)) return '单元格必须完整覆盖网格，不能重叠或留下空洞'
  if(requireText && !value.cells.some(cell=>cell.blocks.some(block=>block.runs.some(run=>run.text.trim())))) return '请至少添加一处文字'
  return count>2000?'网格文字最多 2000 字':''
}
function requireValid(value) {const error=validateMockTextGrid(value);if(error) throw new Error(error);return value}
/** 合并仅接受完整矩形，文字段落按原始行列顺序保留。 */
function mergeMockGridCells(raw,selectedKeys) {
  const value=normalizeMockTextGrid(requireValid(raw))
  const selected=value.cells.filter(cell=>selectedKeys.includes(cell.cellKey)).sort(order)
  if(selected.length<2||new Set(selectedKeys).size!==selectedKeys.length||selected.length!==selectedKeys.length) throw new Error('请选择至少两个完整单元格')
  const row=Math.min(...selected.map(cell=>cell.row)),column=Math.min(...selected.map(cell=>cell.column))
  const rowSpan=Math.max(...selected.map(cell=>cell.row+cell.rowSpan))-row,columnSpan=Math.max(...selected.map(cell=>cell.column+cell.columnSpan))-column
  if(selected.reduce((sum,cell)=>sum+cell.rowSpan*cell.columnSpan,0)!==rowSpan*columnSpan) throw new Error('合并区域必须是连续完整矩形')
  const blocks=selected.flatMap(cell=>cell.blocks.filter(block=>block.runs.some(run=>run.text.length>0)))
  if(blocks.length>8) throw new Error('合并后超过 8 段，请先整理文字')
  const merged={...selected[0],row,column,rowSpan,columnSpan,blocks:blocks.length?blocks:[createMockGridBlock()]}
  return requireValid({...value,cells:[...value.cells.filter(cell=>!selectedKeys.includes(cell.cellKey)),merged].sort(order)})
}
function splitMockGridCell(raw,cellKey) {
  const value=normalizeMockTextGrid(requireValid(raw)),cell=value.cells.find(item=>item.cellKey===cellKey)
  if(!cell) throw new Error('请选择单元格')
  const cells=value.cells.filter(item=>item.cellKey!==cellKey)
  for(let row=cell.row;row<cell.row+cell.rowSpan;row++) for(let column=cell.column;column<cell.column+cell.columnSpan;column++) cells.push(row===cell.row&&column===cell.column?{...cell,rowSpan:1,columnSpan:1}:createMockGridCell(row,column))
  return requireValid({...value,cells:cells.sort(order)})
}
function resizeMockGrid(raw,rows,columns) {
  const value=normalizeMockTextGrid(requireValid(raw))
  if(!integer(rows,1,8)||!integer(columns,1,5)) throw new Error('网格须为 1–8 行、1–5 列')
  if(value.cells.some(cell=>(cell.row+cell.rowSpan>rows||cell.column+cell.columnSpan>columns)&&hasText(cell))) throw new Error('请先移走或清空范围外的文字')
  const cells=value.cells.filter(cell=>cell.row<rows&&cell.column<columns).map(cell=>({...cell,rowSpan:Math.min(cell.rowSpan,rows-cell.row),columnSpan:Math.min(cell.columnSpan,columns-cell.column)}))
  for(let row=0;row<rows;row++) for(let column=0;column<columns;column++) if(!cells.some(cell=>cell.row<=row&&row<cell.row+cell.rowSpan&&cell.column<=column&&column<cell.column+cell.columnSpan)) cells.push(createMockGridCell(row,column))
  return requireValid({...value,rows,columns,cells:cells.sort(order),columnWeights:Array.from({length:columns},(_,i)=>value.columnWeights[i]||1),rowMinHeightsRpx:Array.from({length:rows},(_,i)=>value.rowMinHeightsRpx[i]||180)})
}
function createMockGridHistory(initial) {
  let current=copy(requireValid(initial)),history=[]
  return {get:()=>copy(current),size:()=>history.length,apply(next){requireValid(next);history.push(current);history=history.slice(-MAX_HISTORY);current=copy(next);return copy(current)},undo(){if(history.length)current=history.pop();return copy(current)},clear(){history=[]}}
}
/** 宽度与测量高度统一以 rpx 输入，跨行格补足各行高度避免裁切。 */
function buildMockGridViewModel(raw,themeMode='light',widthRpx=686,measuredHeights={}) {
  const value=normalizeMockTextGrid(requireValid(raw)),gap=value.gapRpx
  const width=widthRpx-value.horizontalMarginRpx*2
  const sum=(list,start,count)=>list.slice(start,start+count).reduce((a,b)=>a+b,0)+gap*(count-1)
  if(!(width>0) || !Number.isFinite(width)) throw new Error('网格宽度无效')
  const weight=value.columnWeights.reduce((a,b)=>a+b,0),widths=value.columnWeights.map(item=>(width-gap*(value.columns-1))*item/weight),heights=value.rowMinHeightsRpx.slice()
  if(value.cells.some(cell=>sum(widths,cell.column,cell.columnSpan)<=2*(value.cellPaddingRpx+(value.cellBorder?value.cellBorderWidthRpx:0)))) throw new Error('文字可用宽度不足，请减小内边距或调整列宽')
  for(const cell of value.cells.slice().sort((a,b)=>a.rowSpan-b.rowSpan)) {
    const needed=Number(measuredHeights[cell.cellKey]||0)+value.cellPaddingRpx*2+(value.cellBorder?value.cellBorderWidthRpx*2:0)
    const extra=needed-sum(heights,cell.row,cell.rowSpan)
    if(extra>0)for(let row=cell.row;row<cell.row+cell.rowSpan;row++)heights[row]+=extra/cell.rowSpan
  }
  const background=value.cellBackground==='AUTO'?(themeMode==='dark'?'#202123':'#F5F6F7'):value.cellBackground
  const channels=background.slice(1).match(/../g).map(part=>parseInt(part,16))
  const textTheme=(channels[0]*299+channels[1]*587+channels[2]*114)/1000<128?'dark':'light'
  const border=value.cellBorderColor==='AUTO'?(themeMode==='dark'?'#45464A':'#D7DADD'):value.cellBorderColor
  return {height:sum(heights,0,value.rows),spacingStyle:`padding:${value.verticalMarginRpx}rpx ${value.horizontalMarginRpx}rpx;`,cells:value.cells.map(cell=>({...cell,style:`position:absolute;left:${cell.column?sum(widths,0,cell.column)+gap:0}rpx;top:${cell.row?sum(heights,0,cell.row)+gap:0}rpx;width:${sum(widths,cell.column,cell.columnSpan)}rpx;height:${sum(heights,cell.row,cell.rowSpan)}rpx;padding:${value.cellPaddingRpx}rpx;border-radius:${value.cellRadiusRpx}rpx;background:${background};border:${value.cellBorder?value.cellBorderWidthRpx:0}rpx solid ${border};justify-content:${{TOP:'flex-start',CENTER:'center',BOTTOM:'flex-end'}[cell.verticalAlignment]};`,blocks:cell.blocks.map(block=>({...block,style:`text-align:${block.alignment.toLowerCase()};padding-top:${block.marginTopRpx}rpx;padding-bottom:${block.marginBottomRpx}rpx;`,lineStyle:validLineHeight(block.lineHeight)?`font-size:${Math.max(...block.runs.map(run=>run.fontSizeRpx))}rpx;line-height:${block.lineHeight};`:'',runs:block.runs.map(run=>({...run,fontClass:run.fontFamily==='WECHAT_SANS_SS'?'font-wechat-sans-ss':'',style:mockTextStyle({...run,alignment:block.alignment,lineHeight:block.lineHeight},textTheme)}))}))}))}
}
module.exports={createMockGridRun,createMockGridBlock,createMockGridCell,createMockTextGrid,normalizeMockTextGrid,validateMockTextGrid,mergeMockGridCells,splitMockGridCell,resizeMockGrid,createMockGridHistory,buildMockGridViewModel}
