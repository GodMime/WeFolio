const {
  CONTACT_FORM_DISPLAY_MODES
} = require('../../utils/portfolios')
const { noop } = require('../../utils/noop')

const DEFAULT_CONTACT_FORM_TITLE = '预留联系信息'
const DEFAULT_SUBMIT_TEXT = '提交'
const VIEW_MODE_MODAL = 'modal'

Component({
  properties: {
    themeMode: {
      type: String,
      value: 'light'
    },
    contactComponent: {
      type: Object,
      value: {}
    },
    contactForm: {
      type: Object,
      value: {}
    },
    submitText: {
      type: String,
      value: DEFAULT_SUBMIT_TEXT
    },
    viewMode: {
      type: String,
      value: ''
    },
    modalVisible: {
      type: Boolean,
      value: false
    }
  },

  data: {
    defaultTitle: DEFAULT_CONTACT_FORM_TITLE,
    inlineMode: CONTACT_FORM_DISPLAY_MODES.INLINE_FORM,
    modalMode: VIEW_MODE_MODAL
  },

  methods: {
    handleInput(event) {
      this.triggerEvent('contactinput', {
        field: event.currentTarget.dataset.field || '',
        value: event.detail.value
      })
    },

    handleDateChange(event) {
      this.triggerEvent('contactinput', {
        field: event.currentTarget.dataset.field || '',
        value: event.detail.value
      })
    },

    handleSubmit() {
      this.triggerEvent('submitcontact')
    },

    handleOpenModal(event) {
      this.triggerEvent('openmodal', {
        componentKey: event.currentTarget.dataset.componentKey || ''
      })
    },

    handleCloseModal() {
      this.triggerEvent('closemodal')
    },

    noop
  }
})
