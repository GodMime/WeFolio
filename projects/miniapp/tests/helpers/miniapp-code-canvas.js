// 微信 Canvas / 图片接口的边界替身，保留实际渲染器与生命周期逻辑。
function canvasRuntime(overrides = {}) {
  const calls = []
  const context = { measureText(value) { return { width: Array.from(value).length * parseFloat(this.font.match(/(\d+)px/)[1]) } } }
  for (const method of ['clearRect', 'fillRect', 'drawImage', 'save', 'restore', 'beginPath', 'arc', 'clip', 'fillText']) {
    context[method] = (...args) => calls.push([method, ...args])
  }
  const canvas = {
    getContext: () => context,
    createImage() {
      calls.push(['createImage'])
      return { set src(value) { this.width = value.includes('avatar') || value.includes('icon') ? 512 : 800; this.height = this.width; queueMicrotask(() => this.onload()) } }
    }
  }
  const wxApi = Object.assign({
    createSelectorQuery() { calls.push(['query']); return { in() { return this }, select() { return this }, fields() { return this }, exec(callback) { callback([{ node: canvas }]) } } },
    getImageInfo(o) { calls.push(['info', o.src]); const size = o.src.includes('avatar') || o.src.includes('icon') ? 512 : 800; o.success({ width: size, height: size }) },
    canvasToTempFilePath(o) { calls.push(['export', o]); o.success({ tempFilePath: '/tmp/rendered.png' }) }
  }, overrides)
  return { canvas, context, wxApi, calls }
}
module.exports = { canvasRuntime }
