const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const {
  THUMB_MAX_BYTES,
  WORK_THUMBNAIL_COMPRESS_ATTEMPTS,
  buildWorkThumbnailCropFrame,
  buildWorkThumbnailCropState,
  buildWorkThumbnailRatioOptions,
  moveWorkThumbnailCropState,
  prepareWorkThumbnailUploadFile,
  zoomWorkThumbnailCropState
} = require('../pages/works/utils/work-thumbnail-crop')

test('builds fixed thumbnail ratio options with original image ratio first', () => {
  const options = buildWorkThumbnailRatioOptions({
    width: 4032,
    height: 3024
  })

  assert.deepEqual(options.map((item) => item.key), ['original', '1:1', '4:3', '3:4', '16:9', '9:16'])
  assert.deepEqual(options[0], {
    key: 'original',
    label: '原图比例',
    width: 4,
    height: 3
  })
})

test('keeps thumbnail crop shared media helpers outside upload workflow module', () => {
  const source = fs.readFileSync(path.join(__dirname, '../pages/works/utils/work-thumbnail-crop.js'), 'utf8')

  assert.doesNotMatch(source, /require\(['"]\.\/work-upload['"]\)/)
})

test('builds thumbnail crop state by covering the selected ratio frame', () => {
  const state = buildWorkThumbnailCropState({
    path: 'wxfile://tmp/photo.jpg',
    width: 1600,
    height: 900
  }, {
    key: '1:1',
    width: 1,
    height: 1
  }, {
    cropBoxWidth: 320
  })

  assert.equal(state.cropBoxWidth, 320)
  assert.equal(state.cropBoxHeight, 320)
  assert.equal(state.displayHeight, 320)
  assert.equal(state.displayWidth, 568.889)
  assert.equal(state.offsetX, -124.444)
  assert.equal(state.offsetY, 0)
  assert.equal(state.minOffsetX, -248.889)
  assert.equal(state.maxOffsetX, 0)
})

test('moves thumbnail crop state without exposing blank space', () => {
  const state = buildWorkThumbnailCropState({
    path: 'wxfile://tmp/photo.jpg',
    width: 1600,
    height: 900
  }, {
    key: '1:1',
    width: 1,
    height: 1
  }, {
    cropBoxWidth: 320
  })

  assert.equal(moveWorkThumbnailCropState(state, { deltaX: 1000 }).offsetX, 0)
  assert.equal(moveWorkThumbnailCropState(state, { deltaX: -1000 }).offsetX, -248.889)
  assert.equal(moveWorkThumbnailCropState(state, { deltaY: 1000 }).offsetY, 0)
})

test('calculates thumbnail crop frame from displayed offsets', () => {
  const state = buildWorkThumbnailCropState({
    path: 'wxfile://tmp/photo.jpg',
    width: 1600,
    height: 900
  }, {
    key: '1:1',
    width: 1,
    height: 1
  }, {
    cropBoxWidth: 320
  })
  const moved = moveWorkThumbnailCropState(state, { deltaX: -35 })
  const frame = buildWorkThumbnailCropFrame(moved, { maxSide: 960 })

  assert.deepEqual(frame, {
    sx: 448,
    sy: 0,
    sWidth: 900,
    sHeight: 900,
    destWidth: 960,
    destHeight: 960
  })
})

test('zooms thumbnail crop state around the crop center without exposing blank space', () => {
  const state = buildWorkThumbnailCropState({
    path: 'wxfile://tmp/photo.jpg',
    width: 1600,
    height: 900
  }, {
    key: '1:1',
    width: 1,
    height: 1
  }, {
    cropBoxWidth: 320
  })

  const zoomed = zoomWorkThumbnailCropState(state, {
    scaleRatio: 1.5,
    anchorX: 160,
    anchorY: 160
  })

  assert.equal(Math.round(zoomed.scale * 1000) / 1000, 0.533)
  assert.equal(zoomed.displayWidth, 853.333)
  assert.equal(zoomed.displayHeight, 480)
  assert.ok(zoomed.offsetX <= zoomed.maxOffsetX)
  assert.ok(zoomed.offsetX >= zoomed.minOffsetX)
  assert.ok(zoomWorkThumbnailCropState(zoomed, { scaleRatio: 0.01 }).scale <= state.scale + 0.001)
})

test('returns thumbnail upload file directly when it is already under 100KB', async () => {
  const wxApi = {
    getFileSystemManager() {
      return {
        statSync(filePath) {
          assert.equal(filePath, 'wxfile://tmp/thumb.jpg')
          return { size: THUMB_MAX_BYTES }
        }
      }
    }
  }

  const result = await prepareWorkThumbnailUploadFile('wxfile://tmp/thumb.jpg', { wxApi })

  assert.deepEqual(result, {
    filePath: 'wxfile://tmp/thumb.jpg',
    fileSize: THUMB_MAX_BYTES,
    mimeType: 'image/jpeg'
  })
})

test('compresses thumbnail upload file until it is under 100KB', async () => {
  const compressCalls = []
  const sizes = {
    'wxfile://tmp/source.jpg': THUMB_MAX_BYTES + 1,
    'wxfile://tmp/compressed-1.jpg': THUMB_MAX_BYTES + 1,
    'wxfile://tmp/compressed-2.jpg': THUMB_MAX_BYTES - 512
  }
  const wxApi = {
    getFileSystemManager() {
      return {
        statSync(filePath) {
          return { size: sizes[filePath] || 0 }
        }
      }
    },
    compressImage(options) {
      compressCalls.push(options)
      options.success({
        tempFilePath: compressCalls.length === 1
          ? 'wxfile://tmp/compressed-1.jpg'
          : 'wxfile://tmp/compressed-2.jpg'
      })
    }
  }

  const result = await prepareWorkThumbnailUploadFile('wxfile://tmp/source.jpg', { wxApi })

  assert.equal(compressCalls.length, 2)
  assert.equal(compressCalls[0].quality, WORK_THUMBNAIL_COMPRESS_ATTEMPTS[0].quality)
  assert.equal(compressCalls[1].compressedWidth, WORK_THUMBNAIL_COMPRESS_ATTEMPTS[1].maxSide)
  assert.equal(Object.prototype.hasOwnProperty.call(compressCalls[0], 'compressedHeight'), false)
  assert.equal(Object.prototype.hasOwnProperty.call(compressCalls[1], 'compressedHeight'), false)
  assert.deepEqual(result, {
    filePath: 'wxfile://tmp/compressed-2.jpg',
    fileSize: THUMB_MAX_BYTES - 512,
    mimeType: 'image/jpeg'
  })
})

test('throws clear message when compressed thumbnail still exceeds 100KB', async () => {
  const wxApi = {
    getFileSystemManager() {
      return {
        statSync() {
          return { size: THUMB_MAX_BYTES + 1 }
        }
      }
    },
    compressImage(options) {
      options.success({ tempFilePath: `wxfile://tmp/${options.quality}.jpg` })
    }
  }

  await assert.rejects(
    () => prepareWorkThumbnailUploadFile('wxfile://tmp/source.jpg', { wxApi }),
    /缩略图不能超过 100KB/
  )
})
