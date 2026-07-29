Component({
  properties: {
    navigation: {
      type: Object,
      value: { enabled: false, items: [] }
    },
    activeMenuKey: {
      type: String,
      value: ''
    },
    themeMode: {
      type: String,
      value: 'light'
    }
  },

  methods: {
    handleMenuTap(event) {
      const menuKey = event.currentTarget.dataset.key || ''
      if (!menuKey || menuKey === this.data.activeMenuKey) {
        return
      }
      this.triggerEvent('change', { menuKey })
    }
  }
})
