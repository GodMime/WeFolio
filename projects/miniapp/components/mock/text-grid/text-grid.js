Component({
  properties:{viewModel:{type:Object,value:{cells:[],height:0}}},
  data:{natural:false},
  observers:{viewModel(){this.measureContent()}},
  lifetimes:{ready(){this.measureContent()},detached(){this._disposed=true;this.resolveFontLayout()}},
  methods:{
    resolveFontLayout(){(this._fontWaiters || []).splice(0).forEach(resolve=>resolve())},
    waitForFontLayout(){
      return new Promise(resolve=>{
        (this._fontWaiters || (this._fontWaiters=[])).push(resolve)
        this.measureContent()
      })
    },
    useNaturalFontLayout(){
      return new Promise(resolve=>this.setData({natural:true},()=>{this.resolveFontLayout();resolve()}))
    },
    measureContent(){
      if(this._disposed || this.data.natural){this.resolveFontLayout();return}
      if(!this.createSelectorQuery){this.resolveFontLayout();return}
      this.createSelectorQuery().select('.grid').boundingClientRect().selectAll('.cell-content').boundingClientRect().exec(results=>{
        if(this._disposed || this.data.natural)return
        if(!results||!results[0]){this.resolveFontLayout();return}
        const cells=this.data.viewModel.cells||[],heights={}
        ;(results[1]||[]).forEach((rect,index)=>{if(cells[index])heights[cells[index].cellKey]=rect.height})
        const signature=JSON.stringify({width:results[0].width,heights})
        if(signature===this._measurement){this.resolveFontLayout();return}
        this._measurement=signature
        this.triggerEvent('measure',{widthPx:results[0].width,heightsPx:heights})
      })
    }
  }
})
