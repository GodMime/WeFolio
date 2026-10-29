const { presentTimeline, layoutTimeline, buildTimelineTailDashes } = require('./timeline-config')
const PROBE_RETRIES = 1
const MIN_BODY_RPX = 240
const MAX_BODY_RPX = 560
const MIN_DATE_COLUMN_RPX = 120
const CUSTOM_DATE_COLUMN_RATIO = 0.36
Component({
  properties: { config: { type: Object, value: {} }, ownerType: { type: String, value: 'USER' }, themeMode: { type: String, value: '' }, backgroundColor: { type: String, value: '#FFFFFF' }, fontContext: { type: Object, value: {} } },
  data: { configView: {}, nodes: [], probes: [], theme: 'light', kicker: '', hint: '', headingStyle: '', frameStyle: '', viewportStyle: '', horizontal: true, compact: false, natural: true, overflow: false },
  observers: { 'config, ownerType, themeMode, backgroundColor, fontContext': function () { this.scheduleLayout() } },
  lifetimes: {
    attached() { this._alive = true; this.scheduleLayout() },
    detached() { this._alive = false; this._sequence = (this._sequence || 0) + 1; this.resolveLayout() }
  },
  pageLifetimes: { resize() { this.scheduleLayout() }, show() { this.scheduleLayout() } },
  methods: {
    resolveLayout(sequence) { if (sequence !== undefined && sequence !== this._sequence) return; (this._waiters || []).splice(0).forEach(resolve => resolve()) },
    waitForFontLayout() { if (!this._alive) return Promise.resolve(); return new Promise(resolve => { (this._waiters || (this._waiters = [])).push(resolve); this.scheduleLayout() }) },
    current(sequence) { return this._alive && sequence === this._sequence },
    presentation() { return presentTimeline(this.properties.config, this.properties.ownerType, this.properties.themeMode, this.properties.backgroundColor, this.properties.fontContext) },
    useNaturalFontLayout() {
      this._sequence = (this._sequence || 0) + 1
      const view = this.presentation()
      return new Promise(resolve => this.setData({ configView: view.config, theme: view.theme, headingStyle: view.headingStyle, kicker: view.kicker, hint: view.hint,
        nodes: view.nodes, probes: [], horizontal: view.config.direction !== 'VERTICAL', compact: true, natural: true, frameStyle: '', viewportStyle: '', overflow: false }, () => { this.resolveLayout(); resolve() }))
    },
    scheduleLayout() {
      const sequence = this._sequence = (this._sequence || 0) + 1
      if (!this._alive) return
      const view = this.presentation()
      this.setData({ configView: view.config, theme: view.theme, headingStyle: view.headingStyle, kicker: view.kicker, hint: view.hint,
        horizontal: view.config.direction !== 'VERTICAL', nodes: view.nodes, compact: true, natural: true, probes: [], frameStyle: '', viewportStyle: '', overflow: false }, () => {
        if (!this.current(sequence)) return
        if (!view.nodes.length || typeof this.createSelectorQuery !== 'function') { this.resolveLayout(sequence); return }
        this.createSelectorQuery().select('.timeline-scroll').boundingClientRect(rect => {
          if (!this.current(sequence)) return
          const api = typeof wx === 'undefined' ? {} : wx
          const info = api.getWindowInfo ? api.getWindowInfo() : api.getSystemInfoSync ? api.getSystemInfoSync() : {}
          const scale = Number(info.windowWidth) / 750
          if (!rect || !(rect.width > 0) || !(scale > 0)) { this.resolveLayout(sequence); return }
          // 先测自然单行日期与正文，再按实际可读宽度测完整换行高度。
          this.setData({ probes: view.nodes.map(node => ({ ...node, probeStyle: 'width:max-content;max-width:none;' })) }, () => this.measure(sequence, view, rect.width, scale, 0, 0))
        }).exec()
      })
    },
    measure(sequence, view, width, scale, phase, retry) {
      const next = typeof wx !== 'undefined' && wx.nextTick ? wx.nextTick : callback => Promise.resolve().then(callback)
      next(() => {
        if (!this.current(sequence)) return
        const query = this.createSelectorQuery()
        query.selectAll('.timeline-probe-date').boundingClientRect()
        query.selectAll('.timeline-probe-body').boundingClientRect()
        query.selectAll('.timeline-probe-node').boundingClientRect()
        query.exec(results => {
          if (!this.current(sequence)) return
          const [dates, bodies, frames] = results || []
          if (![dates, bodies, frames].every(items => Array.isArray(items) && items.length === view.nodes.length && items.every(item => item && Number.isFinite(item.height) && Number.isFinite(item.width)))) {
            if (retry < PROBE_RETRIES) return this.measure(sequence, view, width, scale, phase, retry + 1)
            this.setData({ probes: [], natural: true }, () => this.resolveLayout(sequence)); return
          }
          if (phase === 0) {
            // 自定义标签限制日期列宽并完整换行，普通日期仍保留原有宽度与紧凑回退。
            const customColumnLimit = Math.max(MIN_DATE_COLUMN_RPX * scale, Math.min(width * CUSTOM_DATE_COLUMN_RATIO, width - 56 * scale - MIN_BODY_RPX * scale))
            const column = Math.max(...dates.map((item, index) => view.nodes[index].customDate ? Math.min(item.width, customColumnLimit) : item.width))
            const compact = view.config.variant === 'DATE_COLUMN' && width - column - 56 * scale < MIN_BODY_RPX * scale
            const horizontal = view.config.direction === 'HORIZONTAL'
            const probes = view.nodes.map((node, index) => {
              const bodyWidth = horizontal ? Math.max(MIN_BODY_RPX * scale, Math.min(MAX_BODY_RPX * scale, bodies[index].width / 2))
                : Math.max(1, width - (view.config.variant === 'DATE_COLUMN' && !compact ? column + 56 * scale : 40 * scale) - (view.config.variant === 'MILESTONE_CARD' ? 40 * scale : 0))
              return { ...node, bodyStyle: `width:${bodyWidth}px;`, probeStyle: horizontal ? `width:${Math.max(bodyWidth, dates[index].width)}px;` : `width:${width}px;`, dateColumnStyle: `width:${column}px;` }
            })
            this._naturalDates = dates
            this.setData({ probes, compact }, () => this.measure(sequence, view, width, scale, 1, 0)); return
          }
          const horizontal = view.config.direction === 'HORIZONTAL'
          const measurements = view.nodes.map((node, index) => ({ dateWidth: this._naturalDates[index].width, dateHeight: dates[index].height,
            dateFirstLine: parseFloat((node.dateStyle.match(/font-size:([\d.]+)/) || [0, 24])[1]) * 1.4 * scale,
            bodyWidth: bodies[index].width, bodyHeight: bodies[index].height, height: frames[index].height }))
          const layout = layoutTimeline(view.config, width, scale, measurements)
          if (!layout) { this.setData({ probes: [], natural: true }, () => this.resolveLayout(sequence)); return }
          const nodes = view.nodes.map((node, index) => ({ ...node, bodyStyle: this.data.probes[index].bodyStyle, dateColumnStyle: this.data.probes[index].dateColumnStyle,
            style: `left:${layout.nodes[index].left}px;top:${layout.nodes[index].top}px;width:${layout.nodes[index].width}px;`,
            dateBoxStyle: horizontal ? `height:${Math.max(...measurements.map(item => item.dateHeight))}px;` : '',
            dotStyle: `top:${layout.nodes[index].dotOffset}px;${!horizontal && view.config.variant === 'DATE_COLUMN' && !this.data.compact ? 'left:' + (parseFloat(this.data.probes[index].dateColumnStyle.slice(6)) + 12 * scale) + 'px;' : ''}`,
            lineStyle: horizontal ? `top:${layout.nodes[index].dotOffset}px;width:${layout.nodes[index].lineLength}px;` : `top:${layout.nodes[index].dotOffset}px;height:${layout.nodes[index].lineLength}px;${view.config.variant === 'DATE_COLUMN' && !this.data.compact ? 'left:' + (parseFloat(this.data.probes[index].dateColumnStyle.slice(6)) + 18 * scale) + 'px;' : ''}`, last: layout.nodes[index].last }))
          const last = nodes[nodes.length - 1]
          if (last) {
            // 复用末节点轴线的基线和日期分栏偏移，仅把零长度连接线替换为未来延伸长度。
            const tailLength = layout.nodes[layout.nodes.length - 1].tailLength
            last.tailStyle = last.lineStyle.replace(horizontal ? /width:[\d.]+px/ : /height:[\d.]+px/, `${horizontal ? 'width' : 'height'}:${tailLength}px`)
            last.tailDashes = buildTimelineTailDashes(tailLength / scale, horizontal, view.theme)
          }
          // 真机滚动视口不能依赖内部绝对定位画布撑高；视口只同步高度，宽度仍由页面约束。
          this.setData({ nodes, probes: [], natural: false, overflow: layout.overflow, frameStyle: `width:${layout.width}px;height:${layout.height}px;`,
            viewportStyle: `height:${layout.height}px;` }, () => this.resolveLayout(sequence))
        })
      })
    }
  }
})
