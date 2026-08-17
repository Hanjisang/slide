<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import api from '../api'

const loading = ref(false); const data = ref<any>({ today:{}, health:{ components:{}, queues:{} } })
const stats = computed(() => [
  ['今日采集数据量', data.value.today.collected || 0, '#176b5b'], ['今日异常数据量', data.value.today.errors || 0, '#ba3b46'],
  ['今日成功上报量', data.value.today.reportedSuccess || 0, '#167d8d'], ['今日失败上报量', data.value.today.reportedFailed || 0, '#b76d19'],
  ['今日新增切片数', data.value.today.newSlides || 0, '#535f9b'], ['正在处理切片数', data.value.today.processingSlides || 0, '#61736e'],
])
const components = computed(() => data.value.health.components || {})
function formatBytes(value=0) { if (!value) return '0 B'; const units=['B','KB','MB','GB','TB']; const i=Math.min(Math.floor(Math.log(value)/Math.log(1024)),4); return `${(value/1024**i).toFixed(i?1:0)} ${units[i]}` }
async function load() { loading.value=true; try { data.value=await api.get('/system/dashboard') } finally { loading.value=false } }
onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="page-toolbar"><div><h2>运行概览</h2><p>采集、质量、切片与上报的今日运行情况</p></div><el-button :icon="Refresh" @click="load">刷新</el-button></div>
    <div class="stats-grid"><div v-for="item in stats" :key="item[0]" class="stat-card" :style="{ '--accent': item[2] }"><span>{{ item[0] }}</span><strong>{{ item[1] }}</strong></div></div>
    <div class="dashboard-grid">
      <section class="data-panel"><div class="data-panel-header"><strong>系统状态</strong><small>实时探测</small></div><div class="health-list">
        <div v-for="(label,key) in { mysql:'MySQL', minio:'MinIO', slideWorker:'Slide Worker' }" :key="key" class="health-item"><label>{{ label }}</label><span class="status-dot" :class="String(components[key]?.status || 'DOWN').toLowerCase()">{{ components[key]?.status === 'UP' ? '正常' : '异常' }}</span></div>
      </div></section>
      <section class="data-panel"><div class="data-panel-header"><strong>对象存储</strong><small>MinIO</small></div><div class="storage-lines">
        <div class="storage-line"><span>已使用空间</span><strong>{{ formatBytes(components.minio?.usedBytes) }}</strong></div>
        <div class="storage-line"><span>对象数量</span><strong>{{ components.minio?.objectCount || 0 }}</strong></div>
        <div class="storage-line"><span>数字切片</span><strong>{{ components.minio?.slideCount || 0 }}</strong></div>
      </div></section>
    </div>
  </div>
</template>
