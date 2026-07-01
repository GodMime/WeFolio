const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const rootDir = path.join(__dirname, '..')

function read(relativePath) {
  return fs.readFileSync(path.join(rootDir, relativePath), 'utf8')
}

test('video frame probe page is registered and exposes decoder workflow', () => {
  const appJson = JSON.parse(read('app.json'))
  const probeJs = read('pages/video-frame-probe/video-frame-probe.js')
  const probeWxml = read('pages/video-frame-probe/video-frame-probe.wxml')
  const probeWxss = read('pages/video-frame-probe/video-frame-probe.wxss')

  assert.ok(appJson.pages.includes('pages/video-frame-probe/video-frame-probe'))
  assert.match(probeWxml, /视频帧探针/)
  assert.match(probeWxml, /bindtap="handleChooseVideo"/)
  assert.match(probeWxml, /bindtap="handleExtractFrame"/)
  assert.match(probeWxml, /<canvas[^>]*id="frameProbeCanvas"[^>]*type="2d"[^>]*class="frame-probe-canvas"/)
  assert.doesNotMatch(probeWxml, /canvas-id="frameProbeCanvas"/)
  assert.match(probeWxml, /src="\{\{coverPath\}\}"/)
  assert.match(probeJs, /wx\.createVideoDecoder/)
  assert.match(probeJs, /decodeVideoFrameAtTime/)
  assert.match(probeJs, /source:\s*this\.data\.videoPath/)
  assert.match(probeJs, /timeMs:\s*this\.data\.targetTimeMs/)
  assert.match(probeJs, /durationMs:\s*this\.data\.durationMs/)
  assert.doesNotMatch(probeJs, /abortAudio/)
  assert.match(probeJs, /writeRgbaFrameToCanvas/)
  assert.doesNotMatch(probeJs, /wx\.canvasPutImageData/)
  assert.match(probeWxss, /\.probe-page/)
})
