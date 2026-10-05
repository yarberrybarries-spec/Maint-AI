<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { gsap } from 'gsap'
import { Search, Folder, Star, Trophy } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getMaintenanceManualList, getMaintenanceManualDetail, getMaintenanceManualRank, startMaintenanceManualRead, maintenanceManualHeartbeat, searchChapter } from '@/api/maintenanceManual'

const router = useRouter()

const loading = ref(false)
const searchQuery = ref('')
const list = ref([])
const recommendList = ref([])
const rankList = ref([])
const pagination = reactive({ page: 1, pageSize: 12, total: 0 })
const rankType = ref('total')
const currentReadSession = ref(null)
const heartbeatTimer = ref(null)
const HEARTBEAT_INTERVAL = 20000

const hotTags = ['发动机维修', '电气故障', '设备保养', '液压系统', '日常维护']

// ── Tab 状态 ──
const activeTab = ref('manual') // 'manual' | 'chapter'

// ── 章节搜索状态 ──
const chapterQuery = ref('')
const chapterResults = ref([])        // 原始列表
const chapterGroups = ref([])         // 分组列表
const chapterLoading = ref(false)
let chapterDebounceTimer = null
const searchTime = ref(0)             // 查询耗时 ms
const activeViewMode = ref('list')   // 'list' | 'group'
const chunkTypeFilter = ref('all')    // 'all' | 'text' | 'image' | 'table'

// ── 筛选后结果 ──
const filteredResults = computed(() => {
  if (chunkTypeFilter.value === 'all') return chapterResults.value
  return chapterResults.value.filter(r => r.chunkType === chunkTypeFilter.value)
})

async function loadList() {
  loading.value = true
  try {
    const res = await getMaintenanceManualList({
      page: pagination.page,
      size: pagination.pageSize,
      manualName: searchQuery.value || undefined,
    })
    if (res.code === '200' || res.code === 200) {
      list.value = res.data.records || res.data.list || []
      pagination.total = res.data.total || 0
    }
  } catch (e) {
    ElMessage.error('加载失败: ' + e.message)
  } finally {
    loading.value = false
  }
}

async function loadRecommend() {
  try {
    const res = await getMaintenanceManualRank('week', 10)
    if (res.code === '200' || res.code === 200) {
      recommendList.value = res.data || []
    }
  } catch (e) {
    console.warn('周榜加载失败', e.message)
  }
}

async function loadRank() {
  try {
    const res = await getMaintenanceManualRank(rankType.value, 10)
    if (res.code === '200' || res.code === 200) {
      rankList.value = res.data || []
      // 补充 fileSize 和 createdAt
      await Promise.all(
        rankList.value.map(async (item) => {
          try {
            const detail = await getMaintenanceManualDetail(item.manualId)
            if (detail.code === '200' || detail.code === 200) {
              item.fileSize = detail.data?.fileSize
              item.createdAt = detail.data?.createdAt
            }
          } catch {
            // 静默失败，不阻断展示
          }
        })
      )
    }
  } catch (e) {
    console.warn('排行榜加载失败', e.message)
  }
}

onMounted(() => {
  loadRecommend()
  loadRank()
  if (!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
    nextTick(() => {
      gsap.from('.page-header-card', { y: -18, opacity: 0, duration: 0.5, ease: 'power3.out' })
      gsap.from('.search-bar-card', { y: 22, opacity: 0, duration: 0.55, ease: 'power3.out', delay: 0.12 })
      gsap.from('.manual-panel .section-card', { y: 26, opacity: 0, duration: 0.5, ease: 'power3.out', stagger: 0.12, delay: 0.2 })
    })
  }
})

function handleSearch() {
  if (!searchQuery.value.trim()) return
  router.push({ path: '/user/search-result', query: { keyword: searchQuery.value } })
}

function handleSearchByTag(tag) {
  router.push({ path: '/user/search-result', query: { keyword: tag } })
}

function handleChapterSearch() {
  if (!chapterQuery.value.trim()) {
    chapterResults.value = []
    chapterGroups.value = []
    searchTime.value = 0
    return
  }
  clearTimeout(chapterDebounceTimer)
  chapterDebounceTimer = setTimeout(async () => {
    chapterLoading.value = true
    try {
      const res = await searchChapter({ query: chapterQuery.value })
      if (res.code === '200' || res.code === 200) {
        chapterResults.value = res.data?.results || []
        chapterGroups.value = res.data?.chapterGroups || []
        searchTime.value = res.data?.queryTimeMs || 0
      }
    } catch (e) {
      ElMessage.error('章节搜索失败: ' + e.message)
    } finally {
      chapterLoading.value = false
    }
  }, 300)
}

function changeRankType(type) {
  rankType.value = type
  loadRank()
}

// ── 章节搜索：直接打开 PDF（不创建阅读会话） ──
async function openChapterInPdf(item) {
  let fileUrl = item.sourceFileUrl
  if (!fileUrl) {
    ElMessage.error('文件链接不存在，请确认该手册已上传并解析完成')
    return
  }
  if (item.page) {
    fileUrl = `${fileUrl}#page=${item.page}`
  }
  window.open(fileUrl, '_blank')
}

// ── 章节搜索：内容类型过滤切换 ──
function setChunkTypeFilter(type) {
  chunkTypeFilter.value = type
}

// ── 章节搜索：视图模式切换 ──
function setViewMode(mode) {
  activeViewMode.value = mode
}

// ── 章节搜索：分数显示格式化 ──
function formatScore(score) {
  if (!score) return ''
  return Math.round(score * 100)
}

// ── 章节搜索：chunkType 标签 ──
function getChunkTypeLabel(chunkType) {
  const map = { text: '文本', image: '图片', table: '表格' }
  return map[chunkType] || chunkType || '文本'
}

function stopHeartbeat() {
  if (heartbeatTimer.value) {
    clearInterval(heartbeatTimer.value)
    heartbeatTimer.value = null
  }
  currentReadSession.value = null
}

onBeforeUnmount(() => {
  stopHeartbeat()
})

async function handleRead(id) {
  // 开始阅读会话并启动心跳
  try {
    const startRes = await startMaintenanceManualRead(id)
    if (startRes.code === '200' || startRes.code === 200) {
      currentReadSession.value = startRes.data.readSessionId
      await maintenanceManualHeartbeat(currentReadSession.value)
      heartbeatTimer.value = setInterval(async () => {
        if (currentReadSession.value) {
          await maintenanceManualHeartbeat(currentReadSession.value)
        }
      }, HEARTBEAT_INTERVAL)
    }
  } catch (e) {
    console.warn('阅读会话创建失败', e.message)
  }

  // 打开文件
  try {
    const res = await getMaintenanceManualDetail(id)
    const fileUrl = res.data?.fileUrl
    if (!fileUrl) {
      ElMessage.error('文件链接不存在，请确认该手册已上传并解析完成')
      return
    }
    window.open(fileUrl, '_blank')
  } catch (e) {
    ElMessage.error('打开手册失败：' + (e.message || '请稍后重试'))
  }
}

function formatSize(bytes) {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function formatDate(dateStr) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleDateString('zh-CN')
}

function getFileTypeColor(fileType) {
  const type = (fileType || 'pdf').toLowerCase().replace(/^\./, '')
  if (type === 'pdf') return { bg: 'rgba(31,110,212,0.1)', accent: '#1f6ed4', label: 'PDF' }
  if (type === 'docx' || type === 'doc') return { bg: 'rgba(47,111,189,0.12)', accent: '#2f6fbd', label: 'WORD' }
  if (type === 'xlsx' || type === 'xls') return { bg: 'rgba(40,120,200,0.1)', accent: '#2878c8', label: 'EXCEL' }
  if (type === 'txt') return { bg: 'rgba(70,107,145,0.12)', accent: '#466b91', label: 'TXT' }
  return { bg: 'rgba(31,110,212,0.1)', accent: '#1f6ed4', label: 'FILE' }
}

function getRankBadgeClass(index) {
  if (index === 0) return 'rank-gold'
  if (index === 1) return 'rank-silver'
  if (index === 2) return 'rank-bronze'
  return ''
}
</script>
<template>
  <div class="neo-search-page">
    <header class="search-intro"><div><span class="dash-kicker">KNOWLEDGE / RETRIEVAL</span><h1>知识检索</h1><p>从手册、案例和章节内容里，快速找到可以执行的答案。</p></div><div class="search-note"><span>01</span><p>输入设备、故障现象<br>或维护关键词</p></div></header>
    <section class="search-box"><div class="search-tabs"><button :class="{active: activeTab === 'manual'}" @click="activeTab='manual'">手册检索</button><button :class="{active: activeTab === 'chapter'}" @click="activeTab='chapter'">章节检索</button></div><div v-if="activeTab === 'manual'" class="search-input-row"><el-icon><Search /></el-icon><input v-model="searchQuery" placeholder="例如：液压泵压力异常" @keyup.enter="handleSearch"><button @click="handleSearch">搜索 <el-icon><ArrowRight /></el-icon></button></div><div v-else class="search-input-row"><el-icon><Search /></el-icon><input v-model="chapterQuery" placeholder="搜索章节中的精确内容" @input="handleChapterSearch"><span v-if="chapterLoading" class="search-loading">检索中…</span><button v-else @click="handleChapterSearch">搜索 <el-icon><ArrowRight /></el-icon></button></div><div class="hot-row"><span>热门：</span><button v-for="tag in hotTags" :key="tag" @click="activeTab='manual';handleSearchByTag(tag)">{{ tag }}</button></div></section>

    <template v-if="activeTab === 'manual'">
      <section class="search-columns"><div class="results-panel"><div class="section-head"><div><span class="dash-kicker">LIBRARY</span><h2>推荐手册</h2></div><span>{{ list.length }} 本</span></div><div v-loading="loading" class="manual-grid"><article v-for="(item,index) in list" :key="item.id || item.manualId || index" class="manual-card" @click="handleRead(item.id || item.manualId)"><div class="manual-cover" :class="getFileTypeColor(item.fileType)"><strong>{{ getFileTypeColor(item.fileType).label }}</strong><span>{{ String(index + 1).padStart(2,'0') }}</span></div><div class="manual-copy"><span class="manual-meta">{{ formatDate(item.createdAt) }} · {{ formatSize(item.fileSize) }}</span><h3>{{ item.manualName || item.name || '未命名手册' }}</h3><p>{{ item.description || '面向现场维修的标准化知识手册' }}</p><span class="manual-open">打开手册 <el-icon><ArrowRight /></el-icon></span></div></article><el-empty v-if="!loading && !list.length" description="暂无匹配手册" /></div></div><aside class="side-panel"><div class="section-head"><div><span class="dash-kicker">TRENDING</span><h2>本周热读</h2></div></div><div class="rank-list"><button v-for="(item,index) in recommendList" :key="item.manualId || index" @click="handleRead(item.manualId)"><span class="rank-no">{{ String(index + 1).padStart(2,'0') }}</span><span class="rank-copy"><b>{{ item.manualName || item.name || '热门手册' }}</b><small>{{ item.readCount || item.count || 0 }} 次阅读</small></span><el-icon><ArrowRight /></el-icon></button><p v-if="!recommendList.length" class="empty-tip">热门内容将在数据同步后显示</p></div><div class="rank-switch"><button v-for="type in [{key:'total',label:'总榜'},{key:'week',label:'周榜'},{key:'month',label:'月榜'}]" :key="type.key" :class="{active: rankType === type.key}" @click="changeRankType(type.key)">{{ type.label }}</button></div></aside></section>
      <section class="search-footer"><div><span class="dash-kicker">EXPLORE MORE</span><h2>不知道从哪里开始？</h2></div><div class="footer-actions"><button @click="router.push('/user/ai-chat')">让 AI 帮我定位 <el-icon><ArrowRight /></el-icon></button><button @click="router.push('/user/case-upload')">上传一条现场经验 <el-icon><ArrowRight /></el-icon></button></div></section>
    </template>

    <template v-else>
      <section class="chapter-results"><div class="chapter-head"><div><span class="dash-kicker">FULL TEXT SEARCH</span><h2>章节命中结果</h2></div><div class="chapter-tools"><span v-if="searchTime">约 {{ searchTime }} ms</span><button :class="{active: activeViewMode === 'list'}" @click="setViewMode('list')">列表</button><button :class="{active: activeViewMode === 'group'}" @click="setViewMode('group')">分组</button></div></div><div v-if="filteredResults.length" class="chapter-list"><button v-for="(item,index) in filteredResults" :key="item.id || index" class="chapter-hit" @click="openChapterInPdf(item)"><span class="hit-index">{{ String(index + 1).padStart(2,'0') }}</span><span class="hit-copy"><b>{{ item.manualName || item.sourceFileName || '知识手册' }} <em>{{ item.sectionTitle || item.chapterTitle || '章节内容' }}</em></b><p>{{ item.content || item.text || item.snippet || '打开查看原文' }}</p><small>{{ item.page ? `第 ${item.page} 页` : '' }} · {{ getChunkTypeLabel(item.chunkType) }} <i v-if="item.score">匹配 {{ formatScore(item.score) }}%</i></small></span><el-icon><ArrowRight /></el-icon></button></div><el-empty v-else description="输入关键词后开始章节检索" /></section>
    </template>
  </div>
</template>

<style scoped>
.search-box,
.search-tabs,
.search-input-row,
.hot-row button,
.footer-actions button,
.chapter-tools button.active { border-color: #dbe7f5 !important; }
.hot-row button,
.footer-actions button { color: #1f6ed4 !important; }
.manual-cover { color: #1f6ed4 !important; }
.neo-search-page{min-height:100%;padding:46px clamp(20px,5vw,72px) 70px;background:#f7f7f5;color:#1b1b1b}.dash-kicker{display:block;margin-bottom:11px;color:#a6a6a4;font:700 10px/1.2 ui-monospace,SFMono-Regular,monospace;letter-spacing:.15em}.search-intro{display:flex;align-items:flex-end;justify-content:space-between;gap:20px;max-width:1320px;margin:0 auto 30px}.search-intro h1{margin:0;font-size:clamp(36px,5vw,62px);line-height:1;letter-spacing:-.07em}.search-intro p{margin:14px 0 0;color:#858585;font-size:14px}.search-note{display:flex;align-items:flex-start;gap:12px;padding:12px 15px;border-left:2px solid #1f6ed4;color:#999}.search-note span{color:#1f6ed4;font:700 18px ui-monospace,SFMono-Regular,monospace}.search-note p{margin:0;font-size:11px;line-height:1.6}.search-box{max-width:1320px;margin:0 auto 14px;padding:24px 26px;border:1px solid #f0d1cf;border-radius:20px;background:linear-gradient(120deg,#eaf2ff,#fff 70%)}.search-tabs{display:flex;gap:19px;margin-bottom:17px;border-bottom:1px solid #f1dad8}.search-tabs button{position:relative;padding:0 0 13px;border:0;color:#aaa;background:none;cursor:pointer;font-size:13px}.search-tabs button.active{color:#1f6ed4;font-weight:650}.search-tabs button.active:after{content:'';position:absolute;right:0;bottom:-1px;left:0;height:2px;background:#1f6ed4}.search-input-row{display:flex;align-items:center;gap:10px;padding:6px 6px 6px 15px;border:1px solid #ecd4d2;border-radius:13px;background:#fff}.search-input-row>.el-icon{color:#1f6ed4;font-size:18px}.search-input-row input{flex:1;min-width:0;border:0;outline:0;font-size:13px}.search-input-row button{display:flex;align-items:center;gap:6px;padding:11px 16px;border:0;border-radius:10px;color:#fff;background:#1f6ed4;cursor:pointer;font-size:12px}.search-input-row button:hover{background:#1554ad}.search-loading{color:#1f6ed4;font-size:11px}.hot-row{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin-top:14px;color:#aaa;font-size:11px}.hot-row button{padding:6px 10px;border:1px solid #f0d8d6;border-radius:99px;color:#b85f5b;background:#fff;cursor:pointer;font-size:10px}.hot-row button:hover{color:#1f6ed4;border-color:#1f6ed4}.search-columns{display:grid;grid-template-columns:minmax(0,1.55fr) minmax(280px,.65fr);gap:14px;max-width:1320px;margin:0 auto 14px}.results-panel,.side-panel,.chapter-results,.search-footer{border:1px solid #e8e8e6;border-radius:20px;background:#fff;box-shadow:0 9px 26px #00000004}.results-panel,.side-panel{padding:26px}.section-head,.chapter-head{display:flex;align-items:flex-end;justify-content:space-between;gap:15px}.section-head h2,.chapter-head h2,.search-footer h2{margin:0;font-size:23px;letter-spacing:-.04em}.section-head>span{color:#aaa;font-size:11px}.manual-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:22px}.manual-card{display:flex;gap:13px;padding:12px;border:1px solid #efefee;border-radius:14px;background:#fff;cursor:pointer;transition:.2s}.manual-card:hover{border-color:#9fc1ea;background:#f5f9ff;transform:translateY(-2px)}.manual-cover{display:flex;flex:0 0 62px;align-items:center;justify-content:center;flex-direction:column;gap:4px;height:83px;border-radius:10px;color:#5a4a38}.manual-cover strong{font-size:13px}.manual-cover span{font:9px ui-monospace,SFMono-Regular,monospace;opacity:.65}.manual-copy{display:flex;min-width:0;flex:1;flex-direction:column;align-items:flex-start}.manual-meta{color:#aaa;font-size:9px}.manual-copy h3{overflow:hidden;width:100%;margin:8px 0 5px;color:#222;font-size:13px;text-overflow:ellipsis;white-space:nowrap}.manual-copy p{display:-webkit-box;overflow:hidden;margin:0;color:#999;font-size:10px;line-height:1.5;-webkit-box-orient:vertical;-webkit-line-clamp:2}.manual-open{display:flex;align-items:center;gap:3px;margin-top:auto;padding-top:8px;color:#1f6ed4;font-size:10px}.rank-list{margin-top:17px}.rank-list button{display:flex;align-items:center;gap:10px;width:100%;padding:13px 0;border:0;border-bottom:1px solid #f1f1f0;background:none;text-align:left;cursor:pointer}.rank-list button:hover b{color:#1f6ed4}.rank-no{color:#1f6ed4;font:700 11px ui-monospace,SFMono-Regular,monospace}.rank-copy{display:flex;min-width:0;flex:1;flex-direction:column;gap:4px}.rank-copy b{overflow:hidden;color:#333;font-size:12px;text-overflow:ellipsis;white-space:nowrap}.rank-copy small{color:#aaa;font-size:10px}.rank-list button>.el-icon{color:#bbb}.empty-tip{color:#aaa;font-size:11px;line-height:1.7}.rank-switch{display:flex;gap:3px;margin-top:18px;padding:3px;border-radius:9px;background:#fafafa}.rank-switch button{flex:1;padding:7px;border:0;border-radius:7px;color:#999;background:transparent;cursor:pointer;font-size:10px}.rank-switch button.active{color:#1f6ed4;background:#fff}.search-footer{display:flex;align-items:center;justify-content:space-between;gap:20px;max-width:1320px;margin:0 auto;padding:25px 26px}.footer-actions{display:flex;gap:8px}.footer-actions button{display:flex;align-items:center;gap:6px;padding:10px 12px;border:1px solid #ecd5d3;border-radius:99px;color:#b85f5b;background:#fff;cursor:pointer;font-size:11px}.footer-actions button:hover{color:#fff;background:#1f6ed4}.chapter-results{max-width:1320px;margin:0 auto;padding:26px}.chapter-tools{display:flex;align-items:center;gap:5px}.chapter-tools>span{margin-right:8px;color:#aaa;font-size:10px}.chapter-tools button{padding:7px 10px;border:1px solid #eee;border-radius:8px;color:#999;background:#fff;cursor:pointer;font-size:10px}.chapter-tools button.active{color:#1f6ed4;border-color:#f1c7c4;background:#eaf2ff}.chapter-list{margin-top:20px}.chapter-hit{display:flex;align-items:flex-start;gap:15px;width:100%;padding:18px 0;border:0;border-bottom:1px solid #efefee;background:none;text-align:left;cursor:pointer}.chapter-hit:hover .hit-copy>b{color:#1f6ed4}.hit-index{color:#1f6ed4;font:700 11px ui-monospace,SFMono-Regular,monospace}.hit-copy{min-width:0;flex:1}.hit-copy>b{display:block;color:#222;font-size:13px}.hit-copy>b em{margin-left:8px;color:#999;font-style:normal;font-weight:400}.hit-copy p{display:-webkit-box;overflow:hidden;margin:8px 0;color:#777;font-size:12px;line-height:1.6;-webkit-box-orient:vertical;-webkit-line-clamp:2}.hit-copy small{color:#aaa;font-size:10px}.hit-copy small i{margin-left:9px;color:#1f6ed4;font-style:normal}.chapter-hit>.el-icon{color:#bbb}@media(max-width:950px){.search-columns{grid-template-columns:1fr}.manual-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.search-footer{display:block}.footer-actions{margin-top:18px}}@media(max-width:620px){.neo-search-page{padding:28px 16px 50px}.search-intro{display:block}.search-note{margin-top:20px}.search-box{padding:20px}.manual-grid{grid-template-columns:1fr}.manual-card{min-height:105px}.chapter-head{display:block}.chapter-tools{margin-top:16px}.footer-actions{display:grid;grid-template-columns:1fr}}
</style>
