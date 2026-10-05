<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import RunningTasksTray from '@/components/RunningTasksTray.vue'
import { ChatDotRound, Connection, Document, House, Menu, Search, Tickets, UploadFilled, User, SwitchButton } from '@element-plus/icons-vue'
import { notifyStore } from '@/stores/notifyStore'
import { useSpeech } from '@/composables/useSpeech'

const router = useRouter()
const route = useRoute()
const { stop: stopSpeech } = useSpeech()
const menuOpen = ref(false)
const searchQuery = ref('')
const clock = ref('')
let timer

const items = [
  { path: '/user/dashboard', label: '工作台', note: 'Workspace', icon: House },
  { path: '/user/search', label: '知识', note: 'Knowledge', icon: Search },
  { path: '/user/tasks', label: '任务', note: 'Operations', icon: Tickets },
  { path: '/user/ai-chat', label: 'AI 助手', note: 'Assistant', icon: ChatDotRound },
  { path: '/user/graph', label: '图谱', note: 'Graph', icon: Connection },
  { path: '/user/quiz', label: '问答', note: 'Practice', icon: Document },
  { path: '/user/case-upload', label: '经验', note: 'Cases', icon: UploadFilled },
]
const user = computed(() => { try { return JSON.parse(localStorage.getItem('userInfo') || '{}') } catch { return {} } })
const userName = computed(() => user.value.name || user.value.username || '现场用户')
const userInitial = computed(() => userName.value.slice(0, 1).toUpperCase())
function isActive(path) { return route.path === path || route.path.startsWith(`${path}/`) }
function navigate(path) { menuOpen.value = false; router.push(path) }
function logout() {
  stopSpeech()
  notifyStore.stop()
  localStorage.removeItem('userInfo')
  router.replace('/login')
}
function runSearch() {
  const query = searchQuery.value.trim()
  if (query) router.push({ path: '/user/search', query: { q: query } })
}
function tick() { clock.value = new Date().toLocaleTimeString('zh-CN', { hour12: false }) }
onMounted(() => { notifyStore.init(); tick(); timer = window.setInterval(tick, 1000) })
onUnmounted(() => { if (timer) window.clearInterval(timer) })
</script>

<template>
  <div class="vivo-user-shell" :class="{ 'is-menu-open': menuOpen }">
    <div class="vivo-user-sheen" aria-hidden="true"></div>
    <header class="vivo-user-header">
      <div class="vivo-user-header-inner">
        <button class="vivo-user-mobile-menu" type="button" @click="menuOpen = !menuOpen" aria-label="打开导航"><el-icon><Menu /></el-icon></button>
        <button class="vivo-user-brand" type="button" @click="navigate('/user/dashboard')">
          <span class="vivo-user-brand-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="currentColor">
              <path d="M22.7 19l-9.1-9.1c.9-2.3.4-5-1.5-6.9-2-2-5-2.4-7.4-1.3L9 6 6 9 1.6 4.7C.4 7.1.9 10.1 2.9 12.1c1.9 1.9 4.6 2.4 6.9 1.5l9.1 9.1c.4 1 .4 1.4 0l2.3-2.3c.5-.4.5-1.1.1-1.4z" />
            </svg>
          </span>
          <span class="vivo-user-brand-copy"><strong>智维</strong><small>MAINTAI / FIELD OS</small></span>
        </button>
        <nav class="vivo-user-nav" :class="{ open: menuOpen }">
          <button v-for="item in items" :key="item.path" type="button" class="vivo-user-nav-item" :class="{ active: isActive(item.path) }" @click="navigate(item.path)">
            <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span><small>{{ item.note }}</small>
          </button>
        </nav>
        <div class="vivo-user-header-actions">
          <form class="vivo-user-search" @submit.prevent="runSearch"><el-icon><Search /></el-icon><input v-model="searchQuery" aria-label="搜索知识" placeholder="搜索知识、任务…" /><button v-if="searchQuery" type="button" @click="searchQuery = ''">清除</button></form>
          <span class="vivo-user-online"><i></i>在线</span>
          <span class="vivo-user-time">{{ clock }}</span>
          <button class="vivo-user-account" type="button" @click="navigate('/user/profile')"><span>{{ userInitial }}</span><b>{{ userName }}</b></button>
          <button class="vivo-user-logout" type="button" title="退出登录" aria-label="退出登录" @click="logout"><el-icon><SwitchButton /></el-icon></button>
        </div>
      </div>
    </header>
    <main class="vivo-user-main">
      <router-view v-slot="{ Component }"><keep-alive include="UserAIChat"><component :is="Component" /></keep-alive></router-view>
    </main>
    <RunningTasksTray />
  </div>
</template>

<style scoped>
/* 结构样式集中在 user-vivo.css，业务逻辑和功能组件保持现有实现。 */
</style>
