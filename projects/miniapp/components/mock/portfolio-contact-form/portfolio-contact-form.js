/**
 * 事件接口参考正式组件 components/portfolio-contact-form/portfolio-contact-form.js。
 * 本组件只回传体验版本地表单事件，不得包含真实提交能力。
 */
const DEFAULT_CONTACT_FORM_TITLE = '预留联系信息'
const DEFAULT_SUBMIT_TEXT = '提交'
const DISPLAY_MODE_INLINE_FORM = 'INLINE_FORM'
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
    inlineMode: DISPLAY_MODE_INLINE_FORM,
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

    noop() {}
  }
})
