const { normalizeTextGrid, validateTextGrid, layoutTextGrid, layoutsEqual, presentTextGrid, gridSpacingStyle } = require('../../utils/portfolio-text-grid')
const { loadPortfolioFonts } = require('../../utils/portfolio-component-platform')
Component({
  properties: { config: { type: Object, value: {} }, themeMode: { type: String, value: 'light' } },
  data: { cells: [], measuringCells: [], spacingStyle: '', height: 0, natural: false, error: '' },
  observers: { 'config, themeMode': function () { this.scheduleLayout() } },
  lifetimes: {
    attached() { this._alive = true; this.scheduleLayout(); Promise.resolve(loadPortfolioFonts()).then(() => { if (this._alive) this.scheduleLayout() }) },
    detached() { this._alive = false; this._layoutTask = (this._layoutTask || 0) + 1 }
  },
  pageLifetimes: { resize() { this.scheduleLayout() }, show() { this.scheduleLayout() } },
  methods: {
    scheduleLayout() {
      const task = this._layoutTask = (this._layoutTask || 0) + 1
      if (!this._alive) return
      const grid = normalizeTextGrid(this.properties.config)
      const error = validateTextGrid(grid)
      if (error) { this.setData({ error, cells: [], measuringCells: [] }); return }
      // 先渲染外侧留白，再读取内部宽度，避免首次布局或调整留白时重复扣减。
      this.setData({ spacingStyle: gridSpacingStyle(grid) }, () => this.measureWidth(task, grid))
    },
    measureWidth(task, grid) {
      if (!this._alive || task !== this._layoutTask) return
      this.createSelectorQuery().select('.text-grid-root').boundingClientRect(rect => {
        if (!this._alive || task !== this._layoutTask) return
        const info = typeof wx.getWindowInfo === 'function' ? wx.getWindowInfo() : wx.getSystemInfoSync()
        const scale = info.windowWidth / 750
        if (rect && rect.width > 0) this.triggerEvent('layoutwidth', { widthPx: rect.width, rpxScale: scale })
        let layout
        try { layout = layoutTextGrid(grid, rect && rect.width, scale) }
        catch (error) { this.setData({ natural: true, error: error.message, cells: this.naturalCells(grid), measuringCells: [] }); return }
        const cells = presentTextGrid(grid, layout, this.properties.themeMode)
        this.setData({ measuringCells: cells, error: '', ...(!this._lastLayout ? { cells, height: layout.height, natural: false } : {}) },
          () => this.measureFrame(task, grid, layout, scale, 0))
      }).exec()
    },
    naturalCells(grid) {
      const layout = { cells: grid.cells.slice().sort((a, b) => a.row - b.row || a.column - b.column).map(cell => ({ ...cell, left: 0, top: 0, width: 0, height: 0 })) }
      return presentTextGrid(grid, layout, this.properties.themeMode).map((cell, index, cells) => {
        // 测量失败后按阅读顺序自然展开，仍尊重原跨行格的最小高度与格间距。
        const minHeight = grid.rowMinHeightsRpx.slice(cell.row, cell.row + cell.rowSpan).reduce((sum, height) => sum + height, 0) + grid.gapRpx * (cell.rowSpan - 1)
        return { ...cell, style: `${cell.style}min-height:${minHeight}rpx;margin-bottom:${index < cells.length - 1 ? grid.gapRpx : 0}rpx;` }
      })
    },
    measureFrame(task, grid, baseline, scale, round) {
      const next = typeof wx.nextTick === 'function' ? wx.nextTick : callback => setTimeout(callback, 0)
      next(() => {
        if (!this._alive || task !== this._layoutTask) return
        this.createSelectorQuery().selectAll('.text-grid-measure-content').boundingClientRect(rects => {
          if (!this._alive || task !== this._layoutTask) return
          if (!Array.isArray(rects) || rects.length !== baseline.cells.length || rects.some(rect => !rect || !Number.isFinite(rect.height) || rect.height <= 0)) {
            if (round === 0) { this.measureFrame(task, grid, baseline, scale, 1); return }
            this.setData({ cells: this.naturalCells(grid), natural: true, measuringCells: [] }); return
          }
          const heights = {}; baseline.cells.forEach((cell, index) => { heights[cell.cellKey] = rects[index].height })
          const layout = layoutTextGrid(grid, baseline.width, scale, heights)
          const same = layoutsEqual(this._lastLayout, layout) && this._lastGrid === JSON.stringify(grid) && this._lastTheme === this.properties.themeMode
          this._lastGrid = JSON.stringify(grid); this._lastTheme = this.properties.themeMode; this._lastLayout = layout
          if (!same || this.data.natural) this.setData({ cells: presentTextGrid(grid, layout, this.properties.themeMode), height: layout.height, natural: false })
          this.setData({ measuringCells: [] })
        }).exec()
      })
    }
  }
})
