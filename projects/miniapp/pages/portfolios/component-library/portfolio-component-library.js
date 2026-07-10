const { request } = require('../../../utils/request')
const { COMPONENT_NAMES, COMPONENT_TYPES } = require('../../../utils/portfolios')

const COMPONENT_LIBRARY_API_URL = '/api/mine/portfolios/component-library'

Page({
  data: {
    components: Object.keys(COMPONENT_TYPES).map((key) => ({
      componentType: COMPONENT_TYPES[key],
      name: COMPONENT_NAMES[COMPONENT_TYPES[key]],
      description: '标准个人作品集可选组件'
    }))
  },

  onLoad() {
    request({ url: COMPONENT_LIBRARY_API_URL })
      .then((response) => {
        if (response && Array.isArray(response.components)) {
          this.setData({ components: response.components })
        }
      })
      .catch(() => {})
  }
})
