<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { gsap } from 'gsap'
import {
  Monitor,
  Bell,
  CircleCheck,
  Search,
  Tickets,
  ChatDotRound,
  Share,
  Reading,
  UploadFilled,
  Promotion,
  ArrowRight,
} from '@element-plus/icons-vue'
import { getUserOverview } from '@/api/stat'

const router = useRouter()

const userName = computed(() => {
  try {
    return JSON.parse(localStorage.getItem('userInfo') || '{}').name || '工程师'
  } catch {
    return '工程师'
  }
})

/* —— 顶部指标（数据来自 /weixiu/stat/user-overview，初始为 0，加载后填充） —— */
const stats = reactive([
  { key: 'dev', label: '设备总数', value: 0, display: 0, decimals: 0, suffix: '', icon: Monitor, tone: 'accent', spark: 'M0,18 L8,14 L16,16 L24,9 L32,12 L40,6 L48,10 L56,4 L64,8 L72,3', to: '/user/tasks' },
  { key: 'todo', label: '我的待办', value: 0, display: 0, decimals: 0, suffix: '', icon: Bell, tone: 'info', spark: 'M0,16 L8,10 L16,17 L24,8 L32,15 L40,7 L48,14 L56,6 L64,13 L72,9', to: '/user/tasks' },
  { key: 'rate', label: '任务完成率', value: 0, display: 0, decimals: 1, suffix: '%', icon: CircleCheck, tone: 'success', spark: 'M0,14 L8,12 L16,13 L24,9 L32,11 L40,7 L48,9 L56,5 L64,7 L72,4', to: '/user/tasks' },
])

/* —— 中央检修台周围的功能节点 —— */
const nodes = [
  { label: '智能检索', sub: 'RETRIEVAL', icon: Search, to: '/user/search', side: 'l', slot: 0 },
  { label: '检修任务', sub: 'TASKS', icon: Tickets, to: '/user/tasks', side: 'l', slot: 1 },
  { label: 'AI 对话', sub: 'ASSISTANT', icon: ChatDotRound, to: '/user/ai-chat', side: 'l', slot: 2 },
  { label: '知识图谱', sub: 'GRAPH', icon: Share, to: '/user/graph', side: 'r', slot: 0 },
  { label: '知识问答', sub: 'QUIZ', icon: Reading, to: '/user/quiz', side: 'r', slot: 1 },
  { label: '经验上传', sub: 'CASES', icon: UploadFilled, to: '/user/case-upload', side: 'r', slot: 2 },
]

/* —— 检修任务流转（按后端真实状态计数，count 初始 0，加载后填充） —— */
const taskFlow = reactive([
  { status: 'CREATED', label: '已创建', count: 0, tone: 'accent' },
  { status: 'GENERATING', label: '生成中', count: 0, tone: 'info' },
  { status: 'GENERATED', label: '待执行', count: 0, tone: 'warning' },
  { status: 'EXECUTING', label: '执行中', count: 0, tone: 'gold' },
  { status: 'CLOSED', label: '已完成', count: 0, sub: '已闭环', tone: 'success' },
])

/* —— 知识问答示例 —— */
const quizSamples = [
  '电机振动大是什么原因？',
  '如何处理轴承过热？',
  '推荐相关检修案例',
]

/* —— AI 检修表单 —— */
const target = ref('电机 - MTR-2301')
const problem = ref('')
const range = ref('近 7 天运行数据')

function go(to) {
  router.push(to)
}
function startDiagnose() {
  router.push({ path: '/user/search', query: problem.value.trim() ? { q: problem.value.trim() } : {} })
}
const assistantInput = ref('')
// 点击示例问题：仅填充到输入框，不直接发送
function fillAssistant(q) {
  assistantInput.value = q
}
// 点击发送：携带问题跳转 AI 对话页并真正发送
function askAssistant() {
  const text = assistantInput.value.trim()
  if (!text) return
  router.push({ path: '/user/ai-chat', query: { q: text } })
}

/* ============ 3D 全息检修台 ============ */
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
    const res = await getUserOverview()
    if (!res || String(res.code) !== '200' || !res.data) return
    const d = res.data
    const map = { dev: Number(d.deviceTotal) || 0, todo: Number(d.myOpenTasks) || 0, rate: Number(d.completionRate) || 0 }
    stats.forEach((s, i) => {
      s.value = map[s.key] ?? 0
      s.display = 0
      // 0 → 真实值 count-up；overwrite 清除该对象上的残留补间，避免与入场动画竞争
      gsap.to(s, { display: s.value, duration: 1.2, delay: 0.2 + i * 0.1, ease: 'power2.out', overwrite: true })
    })
    const flow = d.taskFlow || {}
    taskFlow.forEach((t) => {
      t.count = Number(flow[t.status]) || 0
    })
  } catch (e) {
    // 网络/业务失败时保持 0 占位，不展示编造数据（request.js 已统一提示）
  }
}

onMounted(() => {
  reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  if (reduce) { motion.vel = 0 }

  loadOverview()

  ctx = gsap.context(() => {
    // 入场
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

    // 连接线数据流
    gsap.to('.flow-line', { strokeDashoffset: -200, duration: 6, repeat: -1, ease: 'none' })

    // 检修台漂浮
    if (!reduce) {
      gsap.to(coreRef.value, { y: -10, duration: 3, yoyo: true, repeat: -1, ease: 'sine.inOut' })
    }

    // 平台旋转（每帧驱动，支持拖拽）
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
  <div ref="rootRef" class="neo-dashboard">
    <section class="dash-intro">
      <div>
        <span class="dash-kicker">FIELD OPERATIONS / {{ new Date().toLocaleDateString('zh-CN') }}</span>
        <h1>把复杂检修，<em>变得更清楚。</em></h1>
        <p>欢迎回来，{{ userName }}。今天从一个明确的下一步开始。</p>
      </div>
      <div class="dash-actions"><button class="dash-button dash-button--ghost" @click="go('/user/search')"><el-icon><Search /></el-icon>查知识</button><button class="dash-button" @click="go('/user/case-upload')"><el-icon><UploadFilled /></el-icon>沉淀经验</button></div>
    </section>

    <section class="metric-row">
      <button v-for="stat in stats" :key="stat.key" class="metric-card" @click="go(stat.to)">
        <span class="metric-top"><span>{{ stat.label }}</span><el-icon><component :is="stat.icon" /></el-icon></span>
        <strong>{{ Number(stat.display).toFixed(stat.decimals) }}{{ stat.suffix }}</strong><small>实时同步 · 查看详情 <el-icon><ArrowRight /></el-icon></small>
      </button>
    </section>

    <section class="hero-panel">
      <div class="hero-copy"><span class="dash-kicker">SMART MAINTENANCE DESK</span><h2>现场问题，从这里开始解决。</h2><p>输入设备、现象或故障关键词，系统会帮你从手册与历史经验里找到可执行答案。</p><div class="hero-search"><el-icon><Search /></el-icon><input v-model="problem" placeholder="例如：电机启动后振动异常" @keyup.enter="startDiagnose"><button @click="startDiagnose">开始检索 <el-icon><ArrowRight /></el-icon></button></div><div class="hero-links"><span>推荐入口</span><button v-for="node in nodes.slice(0,3)" :key="node.to" @click="go(node.to)">{{ node.label }} <el-icon><ArrowRight /></el-icon></button></div></div>
      <div class="hero-visual"><div class="visual-orbit orbit-a"></div><div class="visual-orbit orbit-b"></div><div class="visual-core"><span>AI</span><small>MAINT</small></div><div class="visual-caption"><b>24 / 7</b><span>现场协作在线</span></div></div>
    </section>

    <div class="dash-columns">
      <section class="dash-card flow-card"><div class="card-heading"><div><span class="dash-kicker">TASK FLOW</span><h3>任务流转</h3></div><button class="card-link" @click="go('/user/tasks')">查看全部 <el-icon><ArrowRight /></el-icon></button></div><div class="flow-list"><button v-for="item in taskFlow" :key="item.status" class="flow-item" @click="go('/user/tasks')"><span class="flow-dot" :class="item.tone"></span><span class="flow-label">{{ item.label }}</span><strong>{{ item.count }}</strong><el-icon><ArrowRight /></el-icon></button></div></section>
      <section class="dash-card assistant-card"><div class="card-heading"><div><span class="dash-kicker">AI ASSISTANT</span><h3>问我一个现场问题</h3></div><span class="online-mark"><i></i>在线</span></div><p>无需组织复杂描述，输入你看到的现象即可。</p><div class="assistant-input"><input v-model="assistantInput" placeholder="例如：轴承温度持续升高" @keyup.enter="askAssistant"><button @click="askAssistant"><el-icon><ArrowRight /></el-icon></button></div><div class="assistant-chips"><button v-for="q in quizSamples" :key="q" @click="fillAssistant(q)">{{ q }}</button></div></section>
    </div>

    <section class="quick-strip"><div><span class="dash-kicker">QUICK ACCESS</span><h3>常用工具</h3></div><div class="quick-grid"><button v-for="node in nodes.slice(3)" :key="node.to" @click="go(node.to)"><span class="quick-icon"><el-icon><component :is="node.icon" /></el-icon></span><span><b>{{ node.label }}</b><small>{{ node.sub }}</small></span><el-icon><ArrowRight /></el-icon></button></div></section>
  </div>
</template>

<style scoped>
.hero-panel,
.hero-search,
.visual-caption,
.assistant-input,
.assistant-chips button { border-color: #dbe7f5 !important; }
.visual-orbit { border-color: #c9daf2 !important; }
.assistant-chips button { color: #1f6ed4 !important; }
.dash-button--ghost:hover { border-color: #9fc1ea !important; }
.flow-dot.info,
.flow-dot.warning,
.flow-dot.gold { background: #1f6ed4 !important; }
.neo-dashboard{min-height:100%;padding:46px clamp(22px,5vw,72px) 70px;background:#f7f7f5;color:#1b1b1b}.dash-intro{display:flex;align-items:flex-end;justify-content:space-between;gap:28px;max-width:1320px;margin:0 auto 30px}.dash-kicker{display:block;margin-bottom:11px;color:#a6a6a4;font:700 10px/1.2 ui-monospace,SFMono-Regular,monospace;letter-spacing:.15em}.dash-intro h1{margin:0;font-size:clamp(34px,5vw,65px);line-height:1.08;letter-spacing:-.06em;font-weight:700}.dash-intro h1 em{color:#1f6ed4;font-style:normal}.dash-intro p{margin:15px 0 0;color:#858585;font-size:14px}.dash-actions{display:flex;gap:9px}.dash-button{display:inline-flex;align-items:center;gap:7px;padding:12px 17px;border:1px solid #1f6ed4;border-radius:99px;color:#fff;background:#1f6ed4;cursor:pointer;font-size:12px;font-weight:650;transition:.2s}.dash-button:hover{background:#1554ad;transform:translateY(-2px)}.dash-button--ghost{color:#555;border-color:#e5e5e3;background:#fff}.dash-button--ghost:hover{color:#1f6ed4;border-color:#f0aaa7;background:#fff}.metric-row{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;max-width:1320px;margin:0 auto 14px}.metric-card{padding:20px 22px;border:1px solid #e8e8e6;border-radius:18px;background:#fff;text-align:left;cursor:pointer;box-shadow:0 9px 26px #00000005;transition:.2s}.metric-card:hover{border-color:#9fc1ea;transform:translateY(-3px);box-shadow:0 17px 34px #0000000b}.metric-top{display:flex;justify-content:space-between;align-items:center;color:#969696;font-size:12px}.metric-top .el-icon{color:#1f6ed4;font-size:18px}.metric-card strong{display:block;margin:16px 0 10px;font-size:39px;letter-spacing:-.06em}.metric-card small{display:flex;align-items:center;gap:4px;color:#aaa;font-size:10px}.metric-card small .el-icon{font-size:12px}.hero-panel{display:grid;grid-template-columns:minmax(0,1.25fr) minmax(280px,.75fr);gap:24px;max-width:1320px;min-height:390px;margin:0 auto 14px;padding:42px 48px;border:1px solid #f0d1cf;border-radius:24px;background:linear-gradient(120deg,#eaf2ff,#fff 70%);overflow:hidden}.hero-copy{align-self:center;max-width:700px}.hero-copy h2{max-width:680px;margin:0;font-size:clamp(30px,4vw,54px);line-height:1.12;letter-spacing:-.06em}.hero-copy p{max-width:560px;margin:18px 0 24px;color:#777;font-size:14px;line-height:1.8}.hero-search{display:flex;align-items:center;gap:10px;max-width:620px;padding:5px 5px 5px 16px;border:1px solid #ecd4d2;border-radius:14px;background:#fff}.hero-search>.el-icon{color:#1f6ed4}.hero-search input{flex:1;min-width:0;border:0;outline:0;color:#333;background:transparent;font:13px inherit}.hero-search button{display:flex;align-items:center;gap:6px;padding:11px 15px;border:0;border-radius:10px;color:#fff;background:#1f6ed4;cursor:pointer;font-size:12px;font-weight:650}.hero-search button:hover{background:#1554ad}.hero-links{display:flex;align-items:center;flex-wrap:wrap;gap:14px;margin-top:16px;color:#aaa;font-size:11px}.hero-links button{display:inline-flex;align-items:center;gap:3px;border:0;color:#666;background:none;cursor:pointer;font-size:11px}.hero-links button:hover{color:#1f6ed4}.hero-visual{position:relative;display:grid;place-items:center;min-height:280px}.visual-orbit{position:absolute;border:1px solid #e7aaa6;border-radius:50%;transform:rotate(-24deg)}.orbit-a{width:275px;height:130px}.orbit-b{width:235px;height:235px;border-color:#c9daf2;transform:rotate(22deg)}.visual-core{position:relative;z-index:1;display:flex;align-items:center;justify-content:center;flex-direction:column;width:126px;height:126px;border-radius:50%;color:#fff;background:#1f6ed4;box-shadow:0 16px 35px #1f6ed440}.visual-core span{font-size:35px;font-weight:750;letter-spacing:-.08em}.visual-core small{font:9px ui-monospace,SFMono-Regular,monospace;letter-spacing:.22em;opacity:.8}.visual-caption{position:absolute;right:2%;bottom:7%;display:flex;flex-direction:column;gap:4px;padding:13px 15px;border:1px solid #f1d6d4;border-radius:13px;background:#ffffffe8}.visual-caption b{font-size:17px}.visual-caption span{color:#999;font-size:10px}.dash-columns{display:grid;grid-template-columns:1fr 1fr;gap:14px;max-width:1320px;margin:0 auto 14px}.dash-card,.quick-strip{border:1px solid #e8e8e6;border-radius:20px;background:#fff;box-shadow:0 9px 26px #00000004}.dash-card{padding:25px}.card-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.card-heading h3,.quick-strip h3{margin:0;font-size:20px;letter-spacing:-.03em}.card-link{display:flex;align-items:center;gap:5px;border:0;color:#1f6ed4;background:none;cursor:pointer;font-size:11px}.flow-list{margin-top:20px}.flow-item{display:flex;align-items:center;gap:11px;width:100%;padding:12px 0;border:0;border-bottom:1px solid #f2f2f1;color:#666;background:none;text-align:left;cursor:pointer}.flow-item:last-child{border-bottom:0}.flow-item:hover{color:#1f6ed4}.flow-dot{width:9px;height:9px;border-radius:50%;background:#ddd}.flow-dot.accent{background:#1f6ed4}.flow-dot.info{background:#e59b48}.flow-dot.warning{background:#ebbb58}.flow-dot.gold{background:#dd7d69}.flow-dot.success{background:#61a579}.flow-label{flex:1;font-size:13px}.flow-item strong{color:#1b1b1b;font-size:17px}.assistant-card{background:linear-gradient(145deg,#fff,#f5f9ff)}.online-mark{display:flex;align-items:center;gap:6px;color:#58a276;font-size:11px}.online-mark i{width:6px;height:6px;border-radius:50%;background:#58a276}.assistant-card>p{margin:20px 0 15px;color:#999;font-size:12px}.assistant-input{display:flex;align-items:center;gap:8px;padding:5px 5px 5px 13px;border:1px solid #edd8d6;border-radius:13px;background:#fff}.assistant-input:focus-within{border-color:#1f6ed4;box-shadow:0 0 0 3px #1f6ed410}.assistant-input input{flex:1;min-width:0;border:0;outline:0;font-size:12px}.assistant-input button{display:grid;place-items:center;width:34px;height:34px;border:0;border-radius:10px;color:#fff;background:#1f6ed4;cursor:pointer}.assistant-chips{display:flex;flex-wrap:wrap;gap:7px;margin-top:15px}.assistant-chips button{padding:8px 10px;border:1px solid #efd9d8;border-radius:99px;color:#bf5a57;background:#fff;cursor:pointer;font-size:10px}.assistant-chips button:hover{color:#fff;background:#1f6ed4}.quick-strip{display:flex;align-items:center;gap:38px;max-width:1320px;margin:0 auto;padding:25px}.quick-strip>div:first-child{min-width:125px}.quick-grid{display:grid;grid-template-columns:repeat(3,1fr);flex:1;gap:8px}.quick-grid button{display:flex;align-items:center;gap:10px;padding:10px;border:0;border-radius:13px;background:#fafafa;text-align:left;cursor:pointer}.quick-grid button:hover{background:#eaf2ff}.quick-icon{display:grid;place-items:center;width:34px;height:34px;border-radius:11px;color:#1f6ed4;background:#eaf2ff}.quick-grid button>span:nth-child(2){display:flex;flex:1;flex-direction:column;gap:3px}.quick-grid b{font-size:12px}.quick-grid small{color:#aaa;font-size:9px;letter-spacing:.08em}.quick-grid>.el-icon{color:#bbb}@media(max-width:950px){.hero-panel{grid-template-columns:1fr;padding:32px}.hero-visual{min-height:220px}.dash-columns{grid-template-columns:1fr}}@media(max-width:680px){.neo-dashboard{padding:28px 16px 50px}.dash-intro{display:block}.dash-actions{margin-top:20px}.metric-row{grid-template-columns:1fr}.hero-panel{padding:25px 20px}.hero-search{align-items:stretch;flex-wrap:wrap}.hero-search input{min-height:35px}.hero-search button{width:100%;justify-content:center}.quick-strip{display:block;padding:21px}.quick-grid{grid-template-columns:1fr;margin-top:18px}}
</style>

<style scoped>
/* 欢迎标题保持稳定字号，仅在窄屏下切换到明确的移动端规格。 */
.dash-intro h1 {
  font-size: 65px !important;
  white-space: nowrap;
}

@media (max-width: 680px) {
  .dash-intro h1 {
    font-size: 42px !important;
    white-space: normal;
  }
}
</style>
