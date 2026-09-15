import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { reveal } from './directives/reveal'
import { setupAuroraPowerSave } from './utils/motion'
import './styles/theme.css'
import './styles/app.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.directive('reveal', reveal)
setupAuroraPowerSave() // 页面隐藏时暂停全站 CSS 动画（省电，见 theme.css .anim-paused）
app.mount('#app')
