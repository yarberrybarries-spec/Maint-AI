<script setup>
import KnowledgeGraphExplorer from '../../components/KnowledgeGraphExplorer.vue'
</script>

<template>
  <div class="kg-page">
    <!-- 普通用户端：只读浏览，无审核动作 -->
    <KnowledgeGraphExplorer :readonly="true" />
  </div>
</template>

<style scoped>
.kg-page {
  position: relative;
  width: 100%;
  /* 给图谱一个明确的可滚动高度，避免主内容区按 auto 高度计算后底部被截断。 */
  height: clamp(680px, calc(100vh - 150px), 960px);
  min-height: 680px;
}
@media (max-width: 900px) {
  .kg-page { height: auto; min-height: 820px; }
}
</style>
