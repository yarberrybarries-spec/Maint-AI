<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { gsap } from 'gsap'
import {
  User,
  Warning,
  CircleCheck,
  List,
  Collection,
  ChatDotRound,
  Share,
  Setting,
  Files,
  Promotion,
  ArrowRight,
} from '@element-plus/icons-vue'
import { getAdminOverview, getRecentActivities } from '@/api/stat'

const router = useRouter()

const adminName = computed(() => {
  try {
    return JSON.parse(localStorage.getItem('userInfo') || '{}').name || '管理员'
  } catch {
    return '管理员'
  }
})

/* —— 顶部指标（数据来自 /weixiu/stat/admin-overview，初始为 0，加载后填充） —— */
const stats = reactive([
  { key: 'users', label: '用户总数', value: 0, display: 0, decimals: 0, suffix: '', icon: User, tone: 'accent', spark: 'M0,18 L8,14 L16,16 L24,9 L32,12 L40,6 L48,10 L56,4 L64,8 L72,3', to: '/admin/system?tab=users' },
  { key: 'review', label: '待审核案例', value: 0, display: 0, decimals: 0, suffix: '', icon: Warning, tone: 'info', spark: 'M0,16 L8,10 L16,17 L24,8 L32,15 L40,7 L48,14 L56,6 L64,13 L72,9', to: '/admin/tasks' },
  { key: 'devices', label: '设备总数', value: 0, display: 0, decimals: 0, suffix: '', icon: CircleCheck, tone: 'success', spark: 'M0,14 L8,12 L16,13 L24,9 L32,11 L40,7 L48,9 L56,5 L64,7 L72,4', to: '/admin/knowledge-center?tab=graph' },
])

/* —— 中央控制台周围的管理节点 —— */
const nodes = [
  { label: '任务管理', sub: 'TASKS', icon: List, to: '/admin/tasks', side: 'l', slot: 0 },
  { label: '知识中心', sub: 'KNOWLEDGE', icon: Collection, to: '/admin/knowledge-center', side: 'l', slot: 1 },
  { label: 'AI 助手', sub: 'ASSISTANT', icon: ChatDotRound, to: '/admin/ai-chat', side: 'l', slot: 2 },
  { label: '知识图谱', sub: 'GRAPH', icon: Share, to: '/admin/knowledge-center?tab=graph', side: 'r', slot: 0 },
  { label: '系统管理', sub: 'SYSTEM', icon: Setting, to: '/admin/system', side: 'r', slot: 1 },
  { label: '案例审核', sub: 'REVIEW', icon: Files, to: '/admin/tasks', side: 'r', slot: 2 },
]

/* —— 任务流转（按后端真实状态计数，count 初始 0，加载后填充） —— */
const taskFlow = reactive([
  { status: 'CREATED', label: '已创建', count: 0, tone: 'accent' },
  { status: 'GENERATING', label: '生成中', count: 0, tone: 'info' },
  { status: 'GENERATED', label: '待执行', count: 0, tone: 'warning' },
  { status: 'EXECUTING', label: '执行中', count: 0, tone: 'gold' },
  { status: 'CLOSED', label: '已完成', count: 0, sub: '已闭环', tone: 'success' },
])

/* —— 最近动态 —— 来自 /weixiu/stat/recent-activities，加载后填充；无数据显示空状态 */
const recentActivities = reactive([])

/** 绝对时间 → 相对时间文案（如“5 分钟前”） */
function relativeTime(iso) {
  if (!iso) return ''
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return ''
  const diff = Math.max(0, Date.now() - t)
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hr = Math.floor(min / 60)
  if (hr < 24) return `${hr} 小时前`
  const day = Math.floor(hr / 24)
  if (day < 30) return `${day} 天前`
  return new Date(t).toLocaleDateString('zh-CN')
}

/* —— 管理助手示例 —— */
const assistantChips = [
  '本月新增多少知识条目？',
  '帮我整理待审核案例',
  '统计高频检修分类',
]

/* —— 检修任务状态分布（饼图，clicks 复用为“任务数”，加载后填充） —— */
const taskStatusOrder = [
  { status: 'CREATED', name: '已创建' },
  { status: 'GENERATING', name: '生成中' },
  { status: 'GENERATED', name: '待执行' },
  { status: 'EXECUTING', name: '执行中' },
  { status: 'CLOSED', name: '已完成' },
]
const categoryClicksRaw = reactive(taskStatusOrder.map((s) => ({ name: s.name, clicks: 0 })))
const chartColors = ['#1f6ed4', '#82a9dd', '#d79631', '#d79631', '#ef8c72', '#a9c5e8']

const pieData = computed(() => {
  const items = categoryClicksRaw.map((item, i) => ({ name: item.name, clicks: item.clicks, color: chartColors[i % chartColors.length] }))
  const total = items.reduce((sum, item) => sum + item.clicks, 0)
  // 无任务时给一个占位灰环，避免除零导致 NaN 路径
  if (total === 0) {
    return items.map((item, i) => ({ ...item, angle: i === 0 ? 360 : 0, startAngle: 0, percent: '0.0', color: 'var(--plaza-border)' }))
  }
  let currentAngle = 0
  return items.map((item) => {
    const angle = (item.clicks / total) * 360
    const startAngle = currentAngle
    currentAngle += angle
    return { ...item, angle, startAngle, percent: ((item.clicks / total) * 100).toFixed(1) }
  })
})

function polarToCartesian(cx, cy, r, angle) {
  const rad = (angle - 90) * Math.PI / 180
  return { x: cx + r * Math.cos(rad), y: cy + r * Math.sin(rad) }
}
function describeArc(cx, cy, r, startAngle, endAngle) {
  const start = polarToCartesian(cx, cy, r, endAngle)
  const end = polarToCartesian(cx, cy, r, startAngle)
  const largeArcFlag = endAngle - startAngle <= 180 ? 0 : 1
  return ['M', cx, cy, 'L', start.x, start.y, 'A', r, r, 0, largeArcFlag, 0, end.x, end.y, 'Z'].join(' ')
}
const piePaths = computed(() =>
  pieData.value.map((item) => ({
    path: describeArc(110, 110, 100, item.startAngle, item.startAngle + item.angle),
    color: item.color,
    percent: item.percent,
  })),
)
const hoveredSlice = ref(null)

/* —— 管理控制台检索表单 —— */
const target = ref('知识条目')
const keyword = ref('')
const range = ref('全部知识库')

function go(to) {
  router.push(to)
}
function startSearch() {
  router.push({ path: '/admin/knowledge-center', query: { tab: 'knowledge', ...(keyword.value.trim() ? { q: keyword.value.trim() } : {}) } })
}
const assistantInput = ref('')
function fillAssistant(q) {
  assistantInput.value = q
}
function askAssistant() {
  const text = assistantInput.value.trim()
  if (!text) return
  router.push({ path: '/admin/ai-chat', query: { q: text } })
}

/* ============ 3D 全息控制台 ============ */
const rootRef = ref(null)
const platformRef = ref(null)
const coreRef = ref(null)

const motion = { spin: 0, tilt: 60, vel: 0.18, dragging: false }
const AUTO = 0.18
let reduce = false
let tickerFn = null
let ctx = null

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v))

let lastX = 0, downY = 0, baseTilt = 60
function onPointerDown(e) {
  motion.dragging = true
  lastX = e.clientX
  downY = e.clientY
  baseTilt = motion.tilt
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
}
function onPointerMove(e) {
  const dx = e.clientX - lastX
  lastX = e.clientX
  const step = dx * 0.45
  motion.spin += step
  motion.vel = step
  motion.tilt = clamp(baseTilt - (e.clientY - downY) * 0.22, 42, 74)
}
function onPointerUp() {
  motion.dragging = false
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
}

/* —— 拉取真实概览统计 —— */
async function loadOverview() {
  try {
    const res = await getAdminOverview()
    if (!res || String(res.code) !== '200' || !res.data) return
    const d = res.data
    const map = { users: Number(d.userTotal) || 0, review: Number(d.pendingCaseTotal) || 0, devices: Number(d.deviceTotal) || 0 }
    stats.forEach((s, i) => {
      s.value = map[s.key] ?? 0
      s.display = 0
      // 0 → 真实值 count-up；overwrite 清除残留补间，避免与入场动画竞争
      gsap.to(s, { display: s.value, duration: 1.2, delay: 0.2 + i * 0.1, ease: 'power2.out', overwrite: true })
    })
    const dist = d.taskStatusDist || {}
    taskFlow.forEach((t) => { t.count = Number(dist[t.status]) || 0 })
    // 饼图：按状态顺序填充任务数
    taskStatusOrder.forEach((s, i) => { categoryClicksRaw[i].clicks = Number(dist[s.status]) || 0 })
  } catch (e) {
    // 失败保持 0 占位，不展示编造数据
  }
}

/* —— 拉取最近操作动态 —— */
async function loadActivities() {
  try {
    const res = await getRecentActivities(8)
    if (!res || String(res.code) !== '200' || !Array.isArray(res.data)) return
    recentActivities.splice(0, recentActivities.length, ...res.data)
  } catch (e) {
    // 失败保持空状态
  }
}

onMounted(() => {
  reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  if (reduce) { motion.vel = 0 }

  loadOverview()
  loadActivities()

  ctx = gsap.context(() => {
    const tl = gsap.timeline({ defaults: { ease: 'power3.out' } })
    tl.from('.hero-kicker', { y: -12, autoAlpha: 0, duration: 0.4 })
      .from('.dash-stat', { x: -18, autoAlpha: 0, duration: 0.5, stagger: 0.1 }, '-=0.2')
      .from('.hero', { y: 26, autoAlpha: 0, duration: 0.6 }, '-=0.3')
      .from('.holo', { scale: 0.7, autoAlpha: 0, duration: 0.7, ease: 'back.out(1.5)' }, '-=0.4')
      .from('.diag-node', { autoAlpha: 0, scale: 0.8, duration: 0.45, stagger: 0.08 }, '-=0.4')
      .from('.secondary-column > .card', { x: 26, autoAlpha: 0, duration: 0.55, stagger: 0.12 }, '-=0.5')
      .from('.flow-col', { y: 20, autoAlpha: 0, duration: 0.45, stagger: 0.08 }, '-=0.4')

    // 指标数字滚动由 loadOverview 在拿到真实值后触发（0→真实值），
    // 此处不再基于初始值 0 起滚，避免两个动画竞争导致数字回落到 0。

    gsap.to('.flow-line', { strokeDashoffset: -200, duration: 6, repeat: -1, ease: 'none' })

    if (!reduce) {
      gsap.to(coreRef.value, { y: -10, duration: 3, yoyo: true, repeat: -1, ease: 'sine.inOut' })
    }

    tickerFn = () => {
      if (!motion.dragging) {
        motion.vel += (AUTO - motion.vel) * 0.04
        motion.spin += motion.vel
      }
      if (platformRef.value) gsap.set(platformRef.value, { rotationX: motion.tilt, rotationZ: motion.spin })
      if (coreRef.value) gsap.set(coreRef.value, { rotationY: clamp((motion.vel - AUTO) * 9, -18, 18) })
    }
    gsap.ticker.add(tickerFn)
  }, rootRef.value)
})

onBeforeUnmount(() => {
  if (tickerFn) gsap.ticker.remove(tickerFn)
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  ctx && ctx.revert()
})
</script>
<template>
  <div ref="rootRef" class="vivo-admin-dashboard">
    <section class="vivo-dash-welcome">
      <div>
        <span class="vivo-eyebrow">MAINTAI / ADMIN WORKSPACE</span>
        <h2>让每一次管理动作，<em>更快落地。</em></h2>
        <p>欢迎回来，{{ adminName }}。从数据、知识到任务，在一个清晰的工作台完成决策。</p>
      </div>
      <div class="vivo-dash-actions"><router-link to="/admin/knowledge-center" class="vivo-light-action">浏览知识 <el-icon><ArrowRight /></el-icon></router-link><router-link to="/admin/tasks" class="vivo-red-action">进入任务中心 <el-icon><ArrowRight /></el-icon></router-link></div>
    </section>

    <section class="vivo-stat-grid" aria-label="核心数据">
      <button v-for="(s, index) in stats" :key="s.key" class="vivo-stat-card" @click="go(s.to)">
        <span class="vivo-stat-index">0{{ index + 1 }}</span><span class="vivo-stat-label">{{ s.label }}</span>
        <strong>{{ s.decimals ? s.display.toFixed(1) : Math.round(s.display) }}<small>{{ s.suffix }}</small></strong>
        <span class="vivo-stat-bottom"><el-icon><component :is="s.icon" /></el-icon><span>实时同步</span><el-icon><ArrowRight /></el-icon></span>
      </button>
    </section>

    <section class="vivo-command-card">
      <div class="vivo-command-copy"><span class="vivo-eyebrow">COMMAND CENTER</span><h3>从一个关键词开始</h3><p>快速定位知识、案例和设备信息，把复杂问题变成下一步动作。</p>
        <div class="vivo-command-form"><label>搜索范围<input v-model="target" aria-label="检索范围" /></label><label class="wide">关键词<textarea v-model="keyword" maxlength="300" placeholder="输入知识条目、案例或用户关键词…" /></label><button type="button" @click="startSearch">开始检索 <el-icon><ArrowRight /></el-icon></button></div>
      </div>
      <div class="vivo-command-visual"><div class="vivo-visual-orbit orbit-one"></div><div class="vivo-visual-orbit orbit-two"></div><div class="vivo-visual-core"><span>MAINT</span></div><span class="vivo-visual-caption">FIELD INTELLIGENCE<br><b>管理能力实时连接</b></span></div>
    </section>

    <div class="vivo-dash-columns">
      <section class="vivo-dash-panel vivo-flow-panel"><header><div><span class="vivo-eyebrow">TASK FLOW</span><h3>任务进度</h3></div><router-link to="/admin/tasks">查看全部 <el-icon><ArrowRight /></el-icon></router-link></header><div class="vivo-flow-list"><router-link v-for="item in taskFlow" :key="item.status" to="/admin/tasks" class="vivo-flow-row"><span class="vivo-flow-line"></span><span class="vivo-flow-number">{{ item.count }}</span><span class="vivo-flow-name">{{ item.label }}</span><span class="vivo-flow-sub">{{ item.sub || '当前数量' }}</span><el-icon><ArrowRight /></el-icon></router-link></div></section>
      <section class="vivo-dash-panel vivo-assistant-panel"><header><div><span class="vivo-eyebrow">AI SUPPORT</span><h3>管理助手</h3></div><span class="vivo-ai-badge"><i></i>AI 在线</span></header><p>把重复工作交给助手，试着问问数据、审核或知识整理。</p><div class="vivo-chip-list"><button v-for="chip in assistantChips" :key="chip" type="button" @click="fillAssistant(chip)">{{ chip }}</button></div><div class="vivo-assistant-input"><input v-model="assistantInput" placeholder="输入你的问题…" @keyup.enter="askAssistant" /><button type="button" @click="askAssistant"><el-icon><Promotion /></el-icon></button></div></section>
    </div>

    <div class="vivo-dash-columns lower">
      <section class="vivo-dash-panel vivo-log-panel"><header><div><span class="vivo-eyebrow">LIVE LOG</span><h3>最近动态</h3></div><router-link to="/admin/system?tab=notify">全部 <el-icon><ArrowRight /></el-icon></router-link></header><div v-if="recentActivities.length" class="vivo-log-list"><div v-for="(item, index) in recentActivities" :key="index" class="vivo-log-row"><i></i><div><b>{{ item.user }}</b><span>{{ item.action }}</span></div><time>{{ relativeTime(item.time) }}</time></div></div><p v-else class="vivo-empty">暂无操作动态</p></section>
      <section class="vivo-dash-panel vivo-distribution-panel"><header><div><span class="vivo-eyebrow">TASK STATUS</span><h3>任务状态分布</h3></div><router-link to="/admin/tasks">详情 <el-icon><ArrowRight /></el-icon></router-link></header><div class="vivo-distribution"><div class="vivo-ring"><span>{{ categoryClicksRaw.reduce((sum, item) => sum + item.clicks, 0) }}</span><small>全部任务</small></div><div class="vivo-legend"><div v-for="(item, index) in pieData" :key="index"><i :style="{ background: item.color }"></i><span>{{ item.name }}</span><b>{{ item.percent }}%</b></div></div></div></section>
    </div>
  </div>
</template>

<style scoped>
.vivo-admin-dashboard{max-width:1400px;margin:0 auto}
</style>
