<script>
export default { name: 'UserAIChat' }
</script>

<script setup>
import AIChat from '@/components/AIChat.vue'
</script>

<template>
  <div class="neo-ai-frame">
    <header class="ai-frame-head"><div><div class="ai-frame-kicker">FIELD OPERATIONS / ASSISTANT</div><h1>AI 协作台</h1><p>把现场问题说清楚，和 AI 一起拆解成下一步动作。</p></div></header>
    <section class="ai-frame-body"><AIChat storage-key="user-ai-sessions" /></section>
  </div>
</template>
