<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import RunningTasksTray from '@/components/RunningTasksTray.vue'
import { House, Setting, SwitchButton, ChatDotRound, List, Collection, User, Menu, Search } from '@element-plus/icons-vue'
import { notifyStore } from '@/stores/notifyStore'
import { useSpeech } from '@/composables/useSpeech'

const router = useRouter()
const route = useRoute()
const { stop: stopSpeech } = useSpeech()
const menuOpen = ref(false)
const searchQuery = ref('')
const clock = ref('')
const openSubmenu = ref('')
let clockTimer

const menuItems = [
  { path: '/admin/dashboard', name: '总览', note: 'Workspace', icon: House },
  {
    path: '/admin/tasks', name: '任务', note: 'Operations', icon: List,
    children: [
      { label: '任务列表', note: '全部任务', query: { tab: 'list' } },
      { label: '沉淀审核', note: '规程沉淀', query: { tab: 'review' } },
      { label: '任务证据候选', note: '候选审核', query: { tab: 'evidence-review' } },
      { label: '过期判定', note: '到期复核', query: { tab: 'expiration-review' } },
    ],
  },
  {
    path: '/admin/knowledge-center', name: '知识中心', note: 'Knowledge', icon: Collection,
    children: [
      { label: '知识库管理', note: '维修手册', query: { tab: 'knowledge' } },
      { label: '知识图谱', note: '结构关系', query: { tab: 'graph' } },
      { label: '标准规程', note: '作业规范', query: { tab: 'procedures' } },
      { label: '回答纠错', note: '反馈审核', query: { tab: 'answer-feedback' } },
      { label: '诊断规则', note: '规则发布', query: { tab: 'domain-rules' } },
      { label: '案例审核', note: '经验沉淀', query: { tab: 'case-review' } },
    ],
  },
  { path: '/admin/ai-chat', name: 'AI 助手', note: 'Assistant', icon: ChatDotRound },
  {
    path: '/admin/system', name: '系统', note: 'System', icon: Setting,
    children: [
      { label: '用户管理', note: '账号与权限', query: { tab: 'users' } },
      { label: '通知中心', note: '运行消息', query: { tab: 'notify' } },
    ],
  },
  { path: '/admin/profile', name: '我的', note: 'Account', icon: User },
]
const user = computed(() => {
  try { return JSON.parse(localStorage.getItem('userInfo') || '{}') } catch { return {} }
})
const userName = computed(() => user.value.name || user.value.username || '管理员')
const userInitial = computed(() => userName.value.slice(0, 1).toUpperCase())
function isActive(path) { return route.path === path || route.path.startsWith(`${path}/`) }
function navigate(path, query) {
  menuOpen.value = false
  openSubmenu.value = ''
  router.push(query ? { path, query } : path)
}
function handleMenuClick(item) {
  if (item.children?.length) {
    openSubmenu.value = openSubmenu.value === item.name ? '' : item.name
    return
  }
  navigate(item.path)
}
function showSubmenu(item) {
  if (item.children?.length) openSubmenu.value = item.name
}
function closeSubmenu() {
  openSubmenu.value = ''
}
function isChildActive(item, child) {
  if (!isActive(item.path)) return false
  const activeTab = route.query.tab || (item.path === '/admin/tasks' ? 'list' : '')
  return String(activeTab) === String(child.query?.tab || '')
}
function goToLogin() {
  stopSpeech()
  notifyStore.stop()
  localStorage.removeItem('userInfo')
  router.replace('/login')
}
function runSearch() {
  const query = searchQuery.value.trim()
  if (query) router.push({ path: '/admin/knowledge-center', query: { tab: 'knowledge', q: query } })
}
function tick() { clock.value = new Date().toLocaleTimeString('zh-CN', { hour12: false }) }
onMounted(() => { notifyStore.init(); tick(); clockTimer = window.setInterval(tick, 1000) })
onUnmounted(() => { if (clockTimer) window.clearInterval(clockTimer) })
</script>

<template>
  <div class="vivo-admin-shell" :class="{ 'is-menu-open': menuOpen }">
    <div class="vivo-admin-sheen" aria-hidden="true"></div>
    <header class="vivo-admin-header">
      <div class="vivo-admin-header-inner">
        <button class="vivo-admin-mobile-menu" type="button" @click="menuOpen = !menuOpen" aria-label="打开导航"><el-icon><Menu /></el-icon></button>
        <button class="vivo-admin-brand" type="button" @click="navigate('/admin/dashboard')">
          <span class="vivo-admin-brand-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="currentColor">
              <path d="M22.7 19l-9.1-9.1c.9-2.3.4-5-1.5-6.9-2-2-5-2.4-7.4-1.3L9 6 6 9 1.6 4.7C.4 7.1.9 10.1 2.9 12.1c1.9 1.9 4.6 2.4 6.9 1.5l9.1 9.1c.4.4 1 .4 1.4 0l2.3-2.3c.5-.4.5-1.1.1-1.4z" />
            </svg>
          </span>
          <span class="vivo-admin-brand-copy"><strong>智维</strong><small>MAINTAI / ADMIN</small></span>
        </button>
        <nav class="vivo-admin-nav" :class="{ open: menuOpen }">
          <div v-for="item in menuItems" :key="item.path" class="vivo-admin-nav-group" @mouseenter="showSubmenu(item)" @mouseleave="closeSubmenu">
            <button type="button" class="vivo-admin-nav-item" :class="{ active: isActive(item.path) }" :aria-expanded="item.children ? openSubmenu === item.name : undefined" @click="handleMenuClick(item)">
              <el-icon><component :is="item.icon" /></el-icon><span>{{ item.name }}</span><small>{{ item.note }}</small>
            </button>
            <div v-if="item.children && openSubmenu === item.name" class="vivo-admin-submenu" :class="{ 'is-wide': item.name === '知识中心' }" role="menu">
              <button v-for="child in item.children" :key="child.label" type="button" class="vivo-admin-submenu-item" :class="{ active: isChildActive(item, child) }" role="menuitem" @click.stop="navigate(item.path, child.query)">
                <span><b>{{ child.label }}</b><small>{{ child.note }}</small></span>
              </button>
            </div>
          </div>
        </nav>
        <div class="vivo-admin-header-actions">
          <form class="vivo-admin-search" @submit.prevent="runSearch"><el-icon><Search /></el-icon><input v-model="searchQuery" aria-label="搜索知识" placeholder="搜索知识、任务…" /><button v-if="searchQuery" type="button" @click="searchQuery = ''">清除</button></form>
          <span class="vivo-admin-online"><i></i>在线</span>
          <span class="vivo-admin-time">{{ clock }}</span>
          <button class="vivo-admin-user" type="button" @click="navigate('/admin/profile')"><span>{{ userInitial }}</span><b>{{ userName }}</b></button>
          <button class="vivo-admin-logout" type="button" title="退出登录" aria-label="退出登录" @click="goToLogin"><el-icon><SwitchButton /></el-icon></button>
        </div>
      </div>
    </header>
    <main class="vivo-admin-main">
      <router-view v-slot="{ Component }"><keep-alive include="AdminAIChat"><component :is="Component" /></keep-alive></router-view>
    </main>
    <RunningTasksTray />
  </div>
</template>

<style scoped>
/* 结构样式集中在 admin-vivo.css，业务逻辑和功能组件保持现有实现。 */
</style>
