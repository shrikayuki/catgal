import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import './styles/workspace.css'
import router from './router'
import './styles/tokens.css'
import './styles/reset.css'
import './styles/global.css'
import './styles/theme.css'
import { initializeTheme } from './services/theme'

initializeTheme()
createApp(App).use(createPinia()).use(router).mount('#app')
