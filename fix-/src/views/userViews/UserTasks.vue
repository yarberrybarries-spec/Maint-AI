<script setup>
import { ref, reactive, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { gsap } from 'gsap'
import {
  ArrowRight,
  CircleCheck,
  Delete,
  Plus,
  Refresh,
  Search,
  Tickets,
  Timer,
} from '@element-plus/icons-vue'
import { deleteTask, getTaskDetail, listTasks } from '@/api/maintenanceTask'
import { notifyStore } from '@/stores/notifyStore'
import { taskStatus, TASK_STATUS, urgency } from '@/constants/taskStatus'
import TaskCreateDialog from '@/components/task/TaskCreateDialog.vue'

const router = useRouter()
const pageRef = ref(null)
const loading = ref(false)
const tasks = ref([])
const total = ref(0)
const showCreate = ref(false)
const query = reactive({ page: 1, size: 12, status: '', deviceName: '' })

let taskMotionContext = null

const statusTabs = computed(() => [
  { value: '', label: '全部任务' },
  ...Object.entries(TASK_STATUS).map(([value, meta]) => ({ value, label: meta.label })),
])

const pageStats = computed(() => {
  const rows = tasks.value
  return {
    waiting: rows.filter((t) => ['CREATED', 'GENERATING', 'GENERATED'].includes(t.status)).length,
    executing: rows.filter((t) => t.status === 'EXECUTING').length,
    completed: rows.filter((t) => t.status === 'CLOSED').length,
  }
})

const overviewMetrics = computed(() => [
  { label: '检索结果', value: total.value, note: '条任务', type: 'total' },
  { label: '当前页待处理', value: pageStats.value.waiting, note: '生成 / 待执行', type: 'waiting' },
  { label: '当前页执行中', value: pageStats.value.executing, note: '现场作业', type: 'executing' },
  { label: '当前页已完成', value: pageStats.value.completed, note: '闭环任务', type: 'completed' },
])

const resultRange = computed(() => {
  if (!total.value || !tasks.value.length) return '0'
  const start = (query.page - 1) * query.size + 1
  const end = start + tasks.value.length - 1
  return `${start}-${end}`
})

const DONE_STEP_STATUSES = new Set(['AI_PASSED', 'COMPLETED', 'SKIPPED'])

function flowStage(task) {
  const total = Number(task.stepCount || 0)
  const completed = Number(task.completedStepCount || 0)

  if (task.status === 'CLOSED') {
    return {
      percent: 100,
      label: total ? `已完成 ${total} / ${total} 步` : '任务已闭环',
    }
  }

  if (task.status === 'EXECUTING') {
    const percent = total ? Math.round((completed / total) * 100) : 0
    return {
      percent: Math.min(100, Math.max(0, percent)),
      label: total ? `已完成 ${completed} / ${total} 步` : '等待步骤同步',
    }
  }

  const labels = {
    CREATED: '等待生成规程',
    GENERATING: 'AI 生成步骤',
    GENERATED: total ? `等待执行 · 0 / ${total} 步` : '等待开始执行',
    GENERATE_FAILED: '生成异常待重试',
  }
  return { percent: 0, label: labels[task.status] || '等待状态同步' }
}

function formatDate(value) {
  return value ? String(value).replace('T', ' ').slice(0, 16) : '等待时间信息'
}

async function animateTaskCards() {
  await nextTick()
  if (!pageRef.value || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return
  taskMotionContext?.revert()
  taskMotionContext = gsap.context(() => {
    gsap.fromTo(
      '.task-card',
      { autoAlpha: 0, y: 14 },
      { autoAlpha: 1, y: 0, duration: 0.4, stagger: 0.045, ease: 'power2.out', clearProps: 'transform,opacity,visibility' },
    )
  }, pageRef.value)
}

async function load() {
  loading.value = true
  let loaded = false
  try {
    const res = await listTasks({
      ...query,
      status: query.status || undefined,
      deviceName: query.deviceName || undefined,
    })
    const d = res?.data || {}
    const records = d.records || []
    const detailResults = await Promise.all(records.map(async (task) => {
      if (task.status !== 'EXECUTING') return task
      try {
        const detail = await getTaskDetail(task.id)
        const steps = detail?.data?.steps || []
        const completedStepCount = steps.filter((step) => DONE_STEP_STATUSES.has(step.status)).length
        return {
          ...task,
          stepCount: Number(task.stepCount || steps.length),
          completedStepCount,
        }
      } catch {
        return task
      }
    }))
    tasks.value = detailResults
    total.value = Number(d.total || 0)
    loaded = true
  } catch (err) {
    ElMessage.error('加载任务失败：' + (err.message || '请稍后重试'))
  } finally {
    loading.value = false
  }
  if (loaded) animateTaskCards()
}

function selectStatus(status) {
  if (query.status === status) return
  query.status = status
  query.page = 1
  load()
}

function resetSearch() {
  query.page = 1
  load()
}

function resetFilters() {
  query.status = ''
  query.deviceName = ''
  resetSearch()
}

function openTask(task) {
  router.push(`/user/tasks/${task.id}`)
}

async function confirmDelete(e, task) {
  e.stopPropagation()
  try {
    await ElMessageBox.confirm(
      `确认删除任务「${task.taskNumber || task.id}」？此操作不可恢复，关联步骤和对话记录也会一并删除。`,
      '删除检修任务',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
    await deleteTask(task.id)
    ElMessage.success('任务已删除')
    load()
  } catch (err) {
    if (err === 'cancel') return
    ElMessage.error('删除失败：' + (err.message || '请稍后重试'))
  }
}

function onCreated(taskId) {
  load()
  if (taskId) router.push(`/user/tasks/${taskId}`)
}

watch(() => notifyStore.state.notifications.length, () => load())
onMounted(load)
onUnmounted(() => taskMotionContext?.revert())
</script>
<template>
  <div ref="pageRef" class="neo-tasks">
    <header class="tasks-head"><div><span class="dash-kicker">FIELD OPERATIONS / TASKS</span><h1>检修任务</h1><p>每一个任务都有下一步，每一个下一步都能被追踪。</p></div><button class="task-primary" @click="showCreate = true"><el-icon><Plus /></el-icon>新建任务</button></header>
    <section class="task-overview">
  <div class="overview-metrics">
    <div v-for="(metric, index) in overviewMetrics" :key="metric.label" class="task-metric">
      <span>{{ String(index + 1).padStart(2, '0') }}</span>
      <div>
        <small>{{ metric.label }}</small>
        <strong>{{ metric.value }}</strong>
        <em>{{ metric.note }}</em>
      </div>
    </div>
  </div>
</section>
    <section class="task-console"><div class="console-top"><div><span class="dash-kicker">MY WORK QUEUE</span><h2>我的工作队列</h2></div><span class="queue-count">{{ resultRange }} <b>/</b> {{ total }}</span></div><div class="task-controls"><div class="status-pills"><button v-for="tab in statusTabs" :key="tab.value || 'all'" :class="{active: query.status === tab.value}" @click="selectStatus(tab.value)">{{ tab.label }}</button></div><div class="task-search"><el-icon><Search /></el-icon><input v-model="query.deviceName" placeholder="搜索设备或问题关键词" @keyup.enter="resetSearch"><button v-if="query.deviceName" class="clear-search" @click="query.deviceName='';resetSearch">×</button><button class="search-submit" @click="resetSearch">搜索</button></div><button class="filter-reset" @click="resetFilters"><el-icon><Refresh /></el-icon></button></div>
      <section v-loading="loading" class="task-table-wrap"><div class="task-table-head"><span>任务编号 / 设备</span><span>优先级</span><span>进度</span><span>创建时间</span><span></span></div><button v-for="task in tasks" :key="task.id" class="task-row" @click="openTask(task)"><div class="task-cell task-main"><span class="task-number">{{ task.taskNumber || `TASK-${task.id}` }}</span><strong>{{ task.deviceName || '未命名设备' }}</strong><small>{{ task.faultDescription || task.problemDescription || '暂无故障描述' }}</small></div><div class="task-cell"><span class="priority-pill" :class="`priority-${task.urgency}`">{{ task.urgency != null ? urgency(task.urgency).label : '常规' }}</span><el-tag :type="taskStatus(task.status).type" effect="plain">{{ taskStatus(task.status).label }}</el-tag></div><div class="task-cell task-progress-cell"><div class="progress-line"><i :style="{width: `${flowStage(task).percent}%`}"></i></div><span>{{ flowStage(task).percent }}%</span><small>{{ flowStage(task).label }}</small></div><div class="task-cell task-date">{{ formatDate(task.createdAt || task.updatedAt) }}</div><div class="task-cell task-actions"><button class="row-arrow" @click.stop="openTask(task)"><el-icon><ArrowRight /></el-icon></button><button class="row-delete" @click.stop="confirmDelete($event, task)"><el-icon><Delete /></el-icon></button></div></button><el-empty v-if="!loading && !tasks.length" description="暂无检修任务，先创建一个吧" /></section><div v-if="total > query.size" class="task-pagination"><el-pagination v-model:current-page="query.page" :page-size="query.size" :total="total" layout="prev, pager, next" @current-change="load" /></div>
    </section>
    <TaskCreateDialog v-model="showCreate" @created="onCreated" />
  </div>
</template>

<style scoped>
.neo-tasks {
  min-height: 100%;
  width: 100%;
  padding: 0 clamp(18px, 3vw, 56px) 70px;
  background: transparent !important;
  color: #1b1b1b;
}
.dash-kicker { display: block; margin-bottom: 11px; color: #1f6ed4; font: 700 10px/1.2 ui-monospace, SFMono-Regular, monospace; letter-spacing: .15em; }
.tasks-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; width: 100%; max-width: 1320px; margin: 0 auto 30px; }
.tasks-head h1 { margin: 0; color: #202020; font-size: clamp(34px, 5vw, 65px); line-height: 1.08; font-weight: 700; letter-spacing: -.06em; }
.tasks-head p { margin: 15px 0 0; color: #858585; font-size: 14px; }
.task-primary { display: inline-flex; align-items: center; gap: 8px; padding: 13px 18px; border: 0; border-radius: 99px; color: #fff; background: #1f6ed4; box-shadow: 0 10px 20px rgba(31,110,212,.14); cursor: pointer; font-size: 12px; font-weight: 650; transition: .2s; }
.task-primary:hover { background: #1554ad; transform: translateY(-2px); }
.task-overview { display: grid; grid-template-columns: 1fr; width: 100%; max-width: 1320px; margin: 0 auto 14px; overflow: hidden; border: 1px solid #e8e8e6; border-radius: 18px; background: #fff; box-shadow: 0 9px 26px rgba(0,0,0,.025); }
.overview-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); min-width: 0; }
.task-metric { display: flex; align-items: flex-start; gap: 15px; padding: 22px; border-right: 1px solid #efefee; }
.overview-metrics .task-metric:last-child { border-right: 0; }
.task-metric > span:first-child { color: #1f6ed4; font: 700 10px ui-monospace, SFMono-Regular, monospace; }
.task-metric small, .task-metric em { display: block; color: #999; font-size: 11px; font-style: normal; }
.task-metric strong { display: block; margin: 8px 0 4px; color: #202020; font-size: 30px; letter-spacing: -.05em; }
.task-console { width: 100%; max-width: 1320px; min-height: 0; margin: 0 auto 14px; padding: 26px !important; border: 1px solid #e8e8e6; border-radius: 20px; background: #fff; box-shadow: 0 9px 26px rgba(0,0,0,.025); }
.console-top { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; }
.console-top h2 { margin: 0; color: #202020; font-size: 24px; letter-spacing: -.04em; }
.queue-count { color: #999; font-size: 12px; }
.queue-count b { padding: 0 6px; color: #ddd; }
.task-controls { display: flex; align-items: center; gap: 13px; min-width: 0; margin-top: 25px; }
.status-pills { display: flex; flex: 1; gap: 4px; min-width: 0; overflow: auto; }
.status-pills button { padding: 9px 12px; border: 1px solid transparent; border-radius: 99px; color: #777; background: #fafafa; cursor: pointer; white-space: nowrap; font-size: 11px; }
.status-pills button:hover, .status-pills button.active { border-color: #c9daf2; color: #1f6ed4; background: #eaf2ff; }
.task-search { display: flex; flex: 0 1 290px; align-items: center; gap: 8px; min-width: 220px; padding: 4px 4px 4px 12px; border: 1px solid #e6e6e5; border-radius: 11px; background: #fff; }
.task-search:focus-within { border-color: #1f6ed4; box-shadow: 0 0 0 3px rgba(31,110,212,.12); }
.task-search > .el-icon { color: #aaa; }
.task-search input { flex: 1; min-width: 0; border: 0; outline: 0; color: #333; background: transparent; font-size: 11px; }
.clear-search { border: 0; color: #aaa; background: none; cursor: pointer; font-size: 16px; }
.search-submit { display: inline-flex; align-items: center; justify-content: center; padding: 9px 12px; border: 0; border-radius: 8px; color: #fff; background: #1f6ed4; cursor: pointer; font-size: 11px; }
.filter-reset { display: grid; width: 35px; height: 35px; place-items: center; border: 1px solid #e6e6e5; border-radius: 10px; color: #888; background: #fff; cursor: pointer; }
.filter-reset:hover { border-color: #9fc1ea; color: #1f6ed4; }
.task-table-wrap { width: 100%; max-width: none; margin: 24px 0 0; overflow: hidden; border: 1px solid #e8e8e6; border-radius: 20px; background: #fff; box-shadow: 0 9px 26px rgba(0,0,0,.025); }
.task-table-head, .task-row { display: grid; grid-template-columns: minmax(240px, 2.2fr) minmax(140px, 1fr) minmax(180px, 1.2fr) minmax(150px, 1fr) 72px; align-items: center; gap: 15px; }
.task-table-head { padding: 13px 22px; color: #aaa; background: #fafafa; font-size: 10px; letter-spacing: .08em; }
.task-row { width: 100%; padding: 18px 22px; border: 0; border-top: 1px solid #f1f1f0; color: #555; background: #fff; text-align: left; cursor: pointer; transition: .18s; }
.task-row:hover { background: #f5f9ff; }
.task-cell { min-width: 0; }
.task-main { display: flex; flex-direction: column; gap: 5px; }
.task-number { color: #777; font: 10px ui-monospace, SFMono-Regular, monospace; }
.task-main strong { overflow: hidden; color: #222; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.task-main small { overflow: hidden; color: #999; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.task-cell:nth-child(2) { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.priority-pill { color: #777; font-size: 11px; }
.priority-3, .priority-4 { color: #1f6ed4; }
.task-progress-cell { display: grid; grid-template-columns: 1fr auto; gap: 5px 9px; align-items: center; }
.progress-line { height: 5px; overflow: hidden; border-radius: 99px; background: #f0f0ef; }
.progress-line i { display: block; height: 100%; border-radius: 99px; background: #1f6ed4; transition: width .3s; }
.task-progress-cell > span { color: #202020; font-size: 11px; }
.task-progress-cell small { grid-column: 1 / -1; color: #999; font-size: 10px; }
.task-date { color: #777; font-size: 11px; }
.task-actions { display: flex; justify-content: flex-end; gap: 5px; }
.row-arrow, .row-delete { display: grid; width: 30px; height: 30px; place-items: center; border: 0; border-radius: 9px; background: transparent; cursor: pointer; }
.row-arrow { color: #1f6ed4; }
.row-delete { color: #aaa; }
.row-arrow:hover, .row-delete:hover { color: #1f6ed4; background: #eaf2ff; }
.el-empty { grid-column: 1 / -1; padding: 55px; }
.task-pagination { display: flex; width: 100%; justify-content: center; padding: 25px; }
.task-pagination .is-active { color: #fff; background: #1f6ed4; }

.neo-tasks{background:transparent!important}
.tasks-head h1{font-size:clamp(34px,5vw,65px);line-height:1.08;letter-spacing:-.06em;font-weight:700}
.tasks-head p{margin-top:15px}



.neo-tasks { width: 100%; max-width: none!important; padding-right: clamp(18px, 3vw, 56px); padding-left: clamp(18px, 3vw, 56px); }
.tasks-head, .task-overview, .task-console { width: 100%; max-width: 1400px!important; margin-right: auto; margin-left: auto; }
.task-console { min-height: 0!important; height: auto!important; padding: 26px!important; }
.task-table-wrap { width: 100%; max-width: none!important; margin: 24px 0 0; }
.task-pagination { width: 100%; max-width: none; }
.task-controls { min-width: 0; }
.task-search { flex: 0 1 290px; min-width: 220px; }
.task-overview { grid-template-columns: 1fr; }
.overview-metrics { width: 100%; }

@media (max-width: 1000px) {
  .task-search { flex-basis: 100%; min-width: 0; }
  .overview-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .overview-metrics .task-metric:nth-child(2) { border-right: 0; }
  .overview-metrics .task-metric:nth-child(n + 3) { border-top: 1px solid #efefee; }
  .task-table-head { display: none; }
  .task-row { grid-template-columns: 1fr auto; padding: 18px; }
  .task-row .task-progress-cell, .task-row .task-date { grid-column: 1; }
  .task-row .task-actions { grid-column: 2; grid-row: 1 / span 3; align-self: center; }
}
@media (max-width: 650px) {
  .neo-tasks { padding: 0 16px 50px; }
  .tasks-head { display: block; }
  .task-primary { margin-top: 20px; }
  .task-metric { padding: 16px; }
  .task-console { padding: 19px !important; }
  .console-top { display: block; }
  .queue-count { display: block; margin-top: 10px; }
  .status-pills { width: 100%; }
}
</style>
