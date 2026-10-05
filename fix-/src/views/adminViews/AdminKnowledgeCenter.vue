<script setup>
import { computed, onMounted, nextTick, ref, watch } from 'vue'
import { gsap } from 'gsap'
import { useRoute, useRouter } from 'vue-router'
import AdminKnowledge from './AdminKnowledge.vue'
import AdminKnowledgeGraph from './AdminKnowledgeGraph.vue'
import AdminProcedures from './AdminProcedures.vue'
import AdminDomainRules from './AdminDomainRules.vue'
import AdminAnswerFeedback from './AdminAnswerFeedback.vue'
import CaseReviewPanel from '@/components/case/CaseReviewPanel.vue'

const route = useRoute()
const router = useRouter()

const tabs = [
  {
    name: 'knowledge',
    label: '知识库管理',
    desc: '维护维修手册、知识条目与解析状态',
    component: AdminKnowledge,
  },
  {
    name: 'graph',
    label: '知识图谱',
    desc: '管理设备、故障与解决方案之间的结构化关系',
    component: AdminKnowledgeGraph,
  },
  {
    name: 'procedures',
    label: '标准规程',
    desc: '沉淀检修任务，形成可复用的标准作业规程',
    component: AdminProcedures,
  },
  {
    name: 'answer-feedback',
    label: '回答纠错',
    desc: '审核用户上报的 AI 回答，人工修订后转为可发布的诊断规则草稿',
    component: AdminAnswerFeedback,
  },
  {
    name: 'domain-rules',
    label: '诊断规则',
    desc: '管理确定性诊断规则，支撑规则引擎前置命中与专家审核发布',
    component: AdminDomainRules,
  },
  {
    name: 'case-review',
    label: '案例审核',
    desc: '审核工人沉淀的检修案例，修正 AI 草稿后入库复用',
    component: CaseReviewPanel,
  },
]

const tabNames = tabs.map((tab) => tab.name)
const activeTab = ref(tabNames.includes(route.query.tab) ? route.query.tab : 'knowledge')
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
    path: '/admin/knowledge-center',
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
    <header class="center-header oppo-center-hero"><div><span class="oppo-eyebrow">ADMIN / KNOWLEDGE</span><h1>知识中心</h1><p>让知识、规程与诊断规则在同一个清晰的工作台里流动。</p></div><span class="center-mark">MAINT<span>AI</span></span></header>
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
