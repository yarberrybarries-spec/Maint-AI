<script setup>
import { computed, onMounted, nextTick, ref, watch } from 'vue'
import { gsap } from 'gsap'
import { useRoute, useRouter } from 'vue-router'
import AdminNotify from './AdminNotify.vue'
import AdminUsers from './AdminUsers.vue'

const route = useRoute()
const router = useRouter()

const tabs = [
  {
    name: 'users',
    label: '用户管理',
    desc: '维护系统账号、角色与人员基础信息',
    component: AdminUsers,
  },
  {
    name: 'notify',
    label: '通知中心',
    desc: '查看任务生成、知识导入与系统消息',
    component: AdminNotify,
  },
]

const tabNames = tabs.map((tab) => tab.name)
const activeTab = ref(tabNames.includes(route.query.tab) ? route.query.tab : 'users')
const activeInfo = computed(() => tabs.find((tab) => tab.name === activeTab.value) || tabs[0])

watch(
  () => route.query.tab,
  (tab) => {
    if (tabNames.includes(tab) && tab !== activeTab.value) {
      activeTab.value = tab
    }
  },
)

watch(activeTab, (tab) => {
  if (route.query.tab === tab) return
  router.replace({
    path: '/admin/system',
    query: { ...route.query, tab },
  })
})

onMounted(() => {
  if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return
  nextTick(() => {
    gsap.timeline({ defaults: { ease: 'power3.out' } })
      .from('.center-header', { y: -14, opacity: 0, duration: 0.5 })
      .from('.center-tabs', { y: -8, opacity: 0, duration: 0.4 }, '-=0.28')
      .from('.center-content', { y: 18, opacity: 0, duration: 0.5 }, '-=0.2')
  })
})
</script>

<template>
  <section class="admin-center-page oppo-center">
    <header class="center-header oppo-center-hero"><div><span class="oppo-eyebrow">ADMIN / SYSTEM</span><h1>系统管理</h1><p>账号、通知与系统运行状态，保持简单、清晰、可控。</p></div><span class="center-mark">MAINT<span>AI</span></span></header>
    <el-tabs v-model="activeTab" class="center-tabs"><el-tab-pane v-for="tab in tabs" :key="tab.name" :label="tab.label" :name="tab.name" /></el-tabs>
    <main class="center-content"><component :is="activeInfo.component" /></main>
  </section>
</template>

<style scoped>
.admin-center-page {
  max-width: 1280px;
  margin: 0 auto;
}

.center-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.center-header h2 {
  color: var(--plaza-heading);
  font-size: 22px;
  font-weight: 800;
  line-height: 1.25;
}

.center-header p {
  margin-top: 6px;
  color: var(--plaza-text-muted);
  font-size: 14px;
}

.center-tabs {
  margin-bottom: 16px;
}
.center-tabs {
  display: none !important;
}
.center-content {
  margin-top: 0 !important;
}

.center-tabs :deep(.el-tabs__header) {
  margin: 0;
  border-bottom: 1px solid var(--plaza-border);
}

.center-tabs :deep(.el-tabs__item) {
  color: var(--plaza-text-muted);
  font-weight: 600;
}

.center-tabs :deep(.el-tabs__item.is-active) {
  color: var(--plaza-accent);
}

.center-tabs :deep(.el-tabs__active-bar) {
  background: var(--plaza-accent);
}

.center-content {
  min-width: 0;
}
</style>

<style scoped>
.oppo-center{max-width:1380px;margin:0 auto;padding:clamp(24px,4vw,56px);color:#191919}.oppo-center-hero{display:flex;justify-content:space-between;align-items:flex-end;gap:24px;margin-bottom:24px}.oppo-eyebrow{display:block;color:#a0a0a0;font-size:11px;letter-spacing:.16em;font-weight:700;margin-bottom:10px}.oppo-center h1{font-size:clamp(32px,4vw,54px);letter-spacing:-.05em;margin:0 0 10px}.oppo-center-hero p{color:#777;margin:0}.center-mark{font-weight:700;font-size:20px;letter-spacing:.08em}.center-mark span{color:#1f6ed4}.center-tabs{background:#fff;border:1px solid #ececeb;border-radius:16px;padding:0 18px;box-shadow:0 8px 25px #00000005}.center-content{margin-top:18px;border-radius:20px!important;padding:24px!important}.center-content-head{display:flex;justify-content:space-between;gap:20px;align-items:flex-end;border-bottom:1px solid #f0f0f0;padding-bottom:18px;margin-bottom:18px}.center-content-head h2{margin:0;font-size:25px}.center-content-head>span{color:#999;font-size:13px;max-width:560px}@media(max-width:700px){.oppo-center-hero,.center-content-head{display:block}.center-mark{display:none}.center-content-head>span{display:block;margin-top:8px}}
</style>
