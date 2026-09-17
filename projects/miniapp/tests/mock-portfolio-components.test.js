const test = require('node:test')
const assert = require('node:assert/strict')
test('自定义二维码没有选择图片时不能确认，个人资料来源允许尚无二维码',()=>{
 const {validateMockComponentConfig:check}=require('../pages/mock/utils/mock-portfolio-components')
 assert.equal(check('QR_CONTACT',{qrUrlSource:'CUSTOM',qrUrl:''}).valid,false)
 assert.equal(check('QR_CONTACT',{qrUrlSource:'PROFILE',qrUrl:''}).valid,true)
 assert.equal(check('QR_CONTACT',{qrUrlSource:'CUSTOM',qrUrl:'https://example.test/qr.jpg'}).valid,true)
})
test('视频轮播严格3至8个不同视频且保持合法样式', () => {
 const {createMockComponentConfig,validateMockComponentConfig:check} = require('../pages/mock/utils/mock-portfolio-components')
 const works = Array.from({length:9},(_,i)=>({id:i+1,mediaType:'VIDEO'})).concat({id:100,mediaType:'AUDIO'})
 const config = createMockComponentConfig('VIDEO_CAROUSEL')
 for (const ids of [[1,2], [1,2,100], [1,1,2],works.slice(0,9).map(w=>w.id)]) assert.equal(check('VIDEO_CAROUSEL',{...config,workIds:ids},works).valid,false)
 assert.equal(check('VIDEO_CAROUSEL',{...config,workIds:works.slice(0,8).map(w=>w.id)},works).valid,true)
 assert.equal(check('VIDEO_CAROUSEL',{...config,workIds:[1,2,3],displayStyle:'OTHER'},works).valid,false)
})
test('网格尺寸范围合法但可用文字宽度不足时拒绝确认',()=>{
 const { createMockTextGrid } = require('../pages/mock/utils/mock-portfolio-text-grid')
 const { validateMockComponentConfig } = require('../pages/mock/utils/mock-portfolio-components')
 const grid={...createMockTextGrid(2,5),cellPaddingRpx:48,horizontalMarginRpx:96,gapRpx:48}
 grid.cells[0].blocks[0].runs[0].text='宽度校验'
 const result=validateMockComponentConfig('TEXT_GRID',grid,[])
 assert.equal(result.valid,false)
 assert.match(result.message,/宽度/)
})
