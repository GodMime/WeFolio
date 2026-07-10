const assert = require('node:assert/strict')
const test = require('node:test')

const { writeRgbaFrameToCanvas } = require('../pages/works/utils/frame-canvas')

test('writes RGBA frame through Skyline Canvas 2D node and exports temp file', async () => {
  const setDataPatches = []
  const putImageDataCalls = []
  const canvas = {
    width: 0,
    height: 0,
    getContext(type) {
      assert.equal(type, '2d')
      return {
        createImageData(width, height) {
          return {
            width,
            height,
            data: new Uint8ClampedArray(width * height * 4)
          }
        },
        putImageData(imageData, x, y) {
          putImageDataCalls.push({
            width: imageData.width,
            height: imageData.height,
            bytes: Array.from(imageData.data),
            x,
            y
          })
        }
      }
    }
  }
  const page = {
    setData(patch, callback) {
      setDataPatches.push(patch)
      if (callback) {
        callback()
      }
    }
  }
  const wxApi = {
    createSelectorQuery() {
      return {
        in(target) {
          assert.equal(target, page)
          return this
        },
        select(selector) {
          assert.equal(selector, '#coverCanvas')
          return this
        },
        fields(options) {
          assert.deepEqual(options, { node: true, size: true })
          return this
        },
        exec(callback) {
          callback([{ node: canvas }])
        }
      }
    },
    canvasToTempFilePath(options) {
      assert.equal(options.canvas, canvas)
      assert.equal(options.canvasId, undefined)
      assert.equal(options.width, 1)
      assert.equal(options.height, 1)
      assert.equal(options.fileType, 'jpg')
      assert.equal(options.quality, 0.92)
      options.success({ tempFilePath: 'wxfile://tmp/frame.jpg' })
    }
  }

  const tempFilePath = await writeRgbaFrameToCanvas({
    page,
    wxApi,
    canvasId: 'coverCanvas',
    frame: {
      width: 1,
      height: 1,
      data: new Uint8ClampedArray([1, 2, 3, 4])
    },
    canvasWidthDataKey: 'canvasWidth',
    canvasHeightDataKey: 'canvasHeight',
    fileType: 'jpg',
    quality: 0.92
  })

  assert.equal(tempFilePath, 'wxfile://tmp/frame.jpg')
  assert.deepEqual(setDataPatches, [{ canvasWidth: 1, canvasHeight: 1 }])
  assert.equal(canvas.width, 1)
  assert.equal(canvas.height, 1)
  assert.deepEqual(putImageDataCalls, [
    {
      width: 1,
      height: 1,
      bytes: [1, 2, 3, 4],
      x: 0,
      y: 0
    }
  ])
})

test('rejects frame data that is not RGBA byte length', async () => {
  await assert.rejects(
    writeRgbaFrameToCanvas({
      page: { setData() {} },
      wxApi: {},
      canvasId: 'coverCanvas',
      frame: {
        width: 2,
        height: 2,
        data: new Uint8ClampedArray([1, 2, 3])
      },
      canvasWidthDataKey: 'canvasWidth',
      canvasHeightDataKey: 'canvasHeight',
      fileType: 'jpg',
      quality: 0.92
    }),
    /当前机型返回的帧格式暂不支持/
  )
})
