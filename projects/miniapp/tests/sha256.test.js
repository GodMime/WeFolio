const assert = require('node:assert/strict')
const test = require('node:test')
const vm = require('node:vm')

const { calculateFileSha256, sha256ArrayBuffer, sha256Text } = require('../utils/sha256')

test('sha256Text matches standard vectors', () => {
  assert.equal(sha256Text(''), 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
  assert.equal(sha256Text('abc'), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
})

test('sha256ArrayBuffer hashes byte arrays', () => {
  const buffer = Uint8Array.from([0, 1, 2, 3]).buffer

  assert.equal(sha256ArrayBuffer(buffer), '054edec1d0211f624fed0cbca9d4f9400b0e491c43742af2c5b0abebf0c990d8')
})

test('sha256ArrayBuffer accepts ArrayBuffer from WebView bridge realms', () => {
  const buffer = vm.runInNewContext('Uint8Array.from([0, 1, 2, 3]).buffer')

  assert.equal(buffer instanceof ArrayBuffer, false)
  assert.equal(sha256ArrayBuffer(buffer), '054edec1d0211f624fed0cbca9d4f9400b0e491c43742af2c5b0abebf0c990d8')
})

test('calculateFileSha256 prefers native getFileInfo sha256 digest', async () => {
  const calls = []
  const wxApi = {
    getFileInfo(options) {
      calls.push(options)
      options.success({
        digest: 'BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD'
      })
    },
    getFileSystemManager() {
      return {
        readFile() {
          throw new Error('不应读取整个文件')
        }
      }
    }
  }

  const digest = await calculateFileSha256('wxfile://tmp/video.mp4', { wxApi })

  assert.equal(digest, 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
  assert.equal(calls.length, 1)
  assert.equal(calls[0].filePath, 'wxfile://tmp/video.mp4')
  assert.equal(calls[0].digestAlgorithm, 'sha256')
})

test('calculateFileSha256 falls back to JS hashing when native digest is unavailable', async () => {
  const readCalls = []
  const wxApi = {
    getFileSystemManager() {
      return {
        readFile(options) {
          readCalls.push(options)
          options.success({
            data: Uint8Array.from([0, 1, 2, 3]).buffer
          })
        }
      }
    }
  }

  const digest = await calculateFileSha256('wxfile://tmp/photo.jpg', { wxApi })

  assert.equal(digest, '054edec1d0211f624fed0cbca9d4f9400b0e491c43742af2c5b0abebf0c990d8')
  assert.equal(readCalls.length, 1)
  assert.equal(readCalls[0].filePath, 'wxfile://tmp/photo.jpg')
})

test('calculateFileSha256 falls back to JS hashing when native digest fails', async () => {
  const calls = []
  const wxApi = {
    getFileInfo(options) {
      calls.push(options)
      options.fail({
        errMsg: 'getFileInfo:fail invalid digestAlgorithm'
      })
    },
    getFileSystemManager() {
      return {
        readFile(options) {
          options.success({
            data: Uint8Array.from([0, 1, 2, 3]).buffer
          })
        }
      }
    }
  }

  const digest = await calculateFileSha256('wxfile://tmp/photo.jpg', { wxApi })

  assert.equal(digest, '054edec1d0211f624fed0cbca9d4f9400b0e491c43742af2c5b0abebf0c990d8')
  assert.equal(calls.length, 1)
  assert.equal(calls[0].digestAlgorithm, 'sha256')
})
