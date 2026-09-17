Component({
  properties:{viewModel:{type:Object,value:{cells:[],height:0}}},
  observers:{viewModel(){this.measureContent()}},
  lifetimes:{ready(){this.measureContent()}},
  methods:{
    measureContent(){
      if(!this.createSelectorQuery)return
      this.createSelectorQuery().select('.grid').boundingClientRect().selectAll('.cell-content').boundingClientRect().exec(results=>{
        if(!results||!results[0])return
        const cells=this.data.viewModel.cells||[],heights={}
        ;(results[1]||[]).forEach((rect,index)=>{if(cells[index])heights[cells[index].cellKey]=rect.height})
        const signature=JSON.stringify({width:results[0].width,heights})
        if(signature===this._measurement)return
        this._measurement=signature
        this.triggerEvent('measure',{widthPx:results[0].width,heightsPx:heights})
      })
    }
  }
})
