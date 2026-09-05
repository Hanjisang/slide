<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bell, DataAnalysis, DataBoard, Files, Fold, FolderOpened, Menu as MenuIcon, Promotion, Setting, SwitchButton } from '@element-plus/icons-vue'
import { ElNotification } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import api from '../api'

const route = useRoute(); const router = useRouter(); const auth = useAuthStore(); const collapsed = ref(false); const mobile = ref(false)
const navCollapsed = computed(() => collapsed.value || mobile.value)
const activeAlerts = ref<any[]>([]); let alertTimer: number | undefined
const notifiedAlertIds = new Set<string>(JSON.parse(sessionStorage.getItem('notifiedAlertIds') || '[]'))
const hasCriticalAlert = computed(() => activeAlerts.value.some((item) => item.severity === 'CRITICAL'))
const menuGroups = [
  { path: '/dashboard', label: '首页', icon: DataBoard },
  { label: '医疗数据采集与治理', icon: DataAnalysis, permissions: ['DATA_VIEW','DATA_EDIT','QUALITY_MANAGE'], children: [
    { path: '/data-sources', label: '数据采集' },
    { path: '/medical-data', label: '医疗数据' },
    { path: '/quality?tab=conversion', label: '数据清洗转换' },
    { path: '/quality?tab=validation', label: '数据校验' },
  ] },
  { label: '数字切片', icon: Files, permissions: ['SLIDE_VIEW'], children: [
    { path: '/slides?tab=adapters', label: '多格式数字切片显示' },
    { path: '/slides?tab=slides', label: '数字切片管理' },
  ] },
  { label: '医疗数据上报', icon: Promotion, permissions: ['REPORT_GENERATE','REPORT_SEND','DATA_VIEW'], children: [
    { path: '/reports?tab=plans', label: '上报任务调度管理' },
    { path: '/reports?tab=prechecks', label: '数据预审核' },
    { path: '/reports?tab=transfers', label: '安全传输保障' },
    { path: '/reports?tab=specs', label: '多平台上报' },
    { path: '/reports?tab=batches', label: '上报记录' },
  ] },
  { label: '基础数据', icon: FolderOpened, permissions: ['DICT_MANAGE','REPORT_GENERATE'], children: [
    { path: '/reports?tab=templates', label: '报告模板设置' },
    { path: '/system?tab=pathology-rules', label: '病理号规则设置' },
    { path: '/system?tab=basic', label: '标本类型管理' },
  ] },
  { label: '系统管理', icon: Setting, permissions: ['USER_MANAGE','SYSTEM_CONFIG','MONITOR_VIEW','LOG_VIEW','DICT_MANAGE','QUALITY_MANAGE'], children: [
    { path: '/system?tab=users', label: '用户管理' },
    { path: '/system?tab=roles', label: '权限管理' },
    { path: '/system?tab=logs', label: '日志管理' },
    { path: '/system?tab=resources', label: '资源管理', icon: Bell },
    { path: '/system?tab=quality', label: '质控管理' },
  ] },
]
const menus = computed(() => menuGroups.filter((item) => auth.hasAny(item.permissions)))
const title = computed(() => String(route.meta.title || ''))
async function logout() { await auth.logout(); router.push('/login') }
function syncViewport() { mobile.value = window.innerWidth <= 900 }
function notify(alert:any, id:string) {
  if (notifiedAlertIds.has(id)) return
  notifiedAlertIds.add(id); sessionStorage.setItem('notifiedAlertIds', JSON.stringify([...notifiedAlertIds]))
  ElNotification({ title: alert.severity === 'CRITICAL' ? '严重告警' : '系统告警', message: alert.message, type: alert.severity === 'CRITICAL' ? 'error' : 'warning', duration: 8000 })
}
async function pollAlerts() {
  if (!auth.hasAny(['MONITOR_VIEW'])) return
  const [eventsResult, healthResult] = await Promise.allSettled([
    api.get('/alerts/events/active', { headers: { 'X-Silent-Error': 'true' } }),
    api.get('/system/health', { headers: { 'X-Silent-Error': 'true' } }),
  ])
  const events:any[] = eventsResult.status === 'fulfilled' ? eventsResult.value : []
  const memoryAlerts:any[] = healthResult.status === 'fulfilled' ? healthResult.value.criticalAlerts || [] : []
  activeAlerts.value = [...events, ...memoryAlerts]
  events.forEach((alert) => notify(alert, `event:${alert.id}`))
  memoryAlerts.forEach((alert) => notify(alert, `memory:${alert.eventType}:${alert.startedAt}`))
}
function openAlerts() { router.push({ path: '/system', query: { tab: 'alerts' } }) }
onMounted(() => { syncViewport(); window.addEventListener('resize', syncViewport); pollAlerts(); alertTimer = window.setInterval(pollAlerts, 30000) })
onBeforeUnmount(() => { window.removeEventListener('resize', syncViewport); if (alertTimer) window.clearInterval(alertTimer) })
</script>

<template>
  <div class="app-shell" :class="{ collapsed: navCollapsed }">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark"><span></span><i></i></div>
        <div v-show="!navCollapsed" class="brand-copy"><strong>MedPath</strong><small>医疗数据上报平台</small></div>
      </div>
      <el-menu :default-active="route.fullPath" router class="side-menu" :collapse="navCollapsed">
        <template v-for="item in menus" :key="item.path || item.label">
        <el-sub-menu v-if="item.children" :index="item.label">
          <template #title><el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span></template>
          <el-menu-item v-for="child in item.children" :key="child.path" :index="child.path">{{ child.label }}</el-menu-item>
        </el-sub-menu>
        <el-menu-item v-else :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon><template #title>{{ item.label }}</template>
        </el-menu-item>
        </template>
      </el-menu>
      <button class="collapse-button" type="button" @click="collapsed = !collapsed" :title="navCollapsed ? '展开导航' : '收起导航'">
        <el-icon><MenuIcon v-if="navCollapsed" /><Fold v-else /></el-icon><span v-if="!navCollapsed">收起导航</span>
      </button>
    </aside>
    <main class="main-area">
      <header class="topbar">
        <div><h1>{{ title }}</h1><p>{{ new Date().toLocaleDateString('zh-CN', { year:'numeric', month:'long', day:'numeric', weekday:'long' }) }}</p></div>
        <div class="topbar-actions"><el-badge v-if="auth.hasAny(['MONITOR_VIEW'])" :value="activeAlerts.length" :hidden="activeAlerts.length===0" :type="hasCriticalAlert?'danger':'warning'">
          <button class="alert-button" :class="{ critical: hasCriticalAlert }" type="button" title="活动告警" @click="openAlerts"><el-icon><Bell /></el-icon></button>
        </el-badge><el-dropdown>
          <button class="user-button" type="button"><span class="avatar">{{ auth.user?.displayName?.slice(0,1) }}</span><span>{{ auth.user?.displayName }}</span></button>
          <template #dropdown><el-dropdown-menu><el-dropdown-item disabled>{{ auth.user?.role }}</el-dropdown-item><el-dropdown-item divided @click="logout"><el-icon><SwitchButton /></el-icon>退出登录</el-dropdown-item></el-dropdown-menu></template>
        </el-dropdown></div>
      </header>
      <section class="content"><router-view /></section>
    </main>
  </div>
</template>
