<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DataAnalysis, DataBoard, DocumentChecked, Files, FirstAidKit, Fold, Menu as MenuIcon, Setting, SwitchButton } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'

const route = useRoute(); const router = useRouter(); const auth = useAuthStore(); const collapsed = ref(false); const mobile = ref(false)
const navCollapsed = computed(() => collapsed.value || mobile.value)
const menus = [
  { path: '/dashboard', label: '首页', icon: DataBoard },
  { path: '/data-sources', label: '数据源管理', icon: DataAnalysis },
  { path: '/medical-data', label: '医疗数据', icon: FirstAidKit },
  { path: '/quality', label: '数据质量', icon: DocumentChecked },
  { path: '/slides', label: '数字切片', icon: Files },
  { path: '/reports', label: '数据上报', icon: DataBoard },
  { path: '/system', label: '系统管理', icon: Setting },
]
const title = computed(() => String(route.meta.title || ''))
async function logout() { await auth.logout(); router.push('/login') }
function syncViewport() { mobile.value = window.innerWidth <= 900 }
onMounted(() => { syncViewport(); window.addEventListener('resize', syncViewport) })
onBeforeUnmount(() => window.removeEventListener('resize', syncViewport))
</script>

<template>
  <div class="app-shell" :class="{ collapsed: navCollapsed }">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark"><span></span><i></i></div>
        <div v-show="!navCollapsed" class="brand-copy"><strong>MedPath</strong><small>医疗数据上报平台</small></div>
      </div>
      <el-menu :default-active="route.path" router class="side-menu" :collapse="navCollapsed">
        <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon><template #title>{{ item.label }}</template>
        </el-menu-item>
      </el-menu>
      <button class="collapse-button" type="button" @click="collapsed = !collapsed" :title="navCollapsed ? '展开导航' : '收起导航'">
        <el-icon><MenuIcon v-if="navCollapsed" /><Fold v-else /></el-icon><span v-if="!navCollapsed">收起导航</span>
      </button>
    </aside>
    <main class="main-area">
      <header class="topbar">
        <div><h1>{{ title }}</h1><p>{{ new Date().toLocaleDateString('zh-CN', { year:'numeric', month:'long', day:'numeric', weekday:'long' }) }}</p></div>
        <el-dropdown>
          <button class="user-button" type="button"><span class="avatar">{{ auth.user?.displayName?.slice(0,1) }}</span><span>{{ auth.user?.displayName }}</span></button>
          <template #dropdown><el-dropdown-menu><el-dropdown-item disabled>{{ auth.user?.role }}</el-dropdown-item><el-dropdown-item divided @click="logout"><el-icon><SwitchButton /></el-icon>退出登录</el-dropdown-item></el-dropdown-menu></template>
        </el-dropdown>
      </header>
      <section class="content"><router-view /></section>
    </main>
  </div>
</template>
