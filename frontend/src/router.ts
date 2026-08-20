import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from './layout/AppLayout.vue'
import LoginView from './views/LoginView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { public: true, title: '登录' } },
    {
      path: '/', component: AppLayout, redirect: '/dashboard', children: [
        { path: 'dashboard', component: () => import('./views/DashboardView.vue'), meta: { title: '首页' } },
        { path: 'data-sources', component: () => import('./views/DataSourceView.vue'), meta: { title: '数据采集', permissions: ['DATA_VIEW','DATA_EDIT'] } },
        { path: 'medical-data', component: () => import('./views/MedicalDataView.vue'), meta: { title: '医疗数据', permissions: ['DATA_VIEW'] } },
        { path: 'quality', component: () => import('./views/QualityView.vue'), meta: { title: '数据质量', permissions: ['QUALITY_MANAGE'] } },
        { path: 'slides', component: () => import('./views/SlideView.vue'), meta: { title: '数字切片', permissions: ['SLIDE_VIEW'] } },
        { path: 'files', component: () => import('./views/FileView.vue'), meta: { title: '文件管理', permissions: ['FILE_MANAGE'] } },
        { path: 'reports', component: () => import('./views/ReportView.vue'), meta: { title: '数据上报', permissions: ['REPORT_GENERATE','DATA_VIEW'] } },
        { path: 'system', component: () => import('./views/SystemView.vue'), meta: { title: '系统管理', permissions: ['USER_MANAGE','SYSTEM_CONFIG','MONITOR_VIEW','LOG_VIEW','DICT_MANAGE'] } },
      ],
    },
  ],
})

router.beforeEach((to) => {
  document.title = `${String(to.meta.title || '')} - 医疗数据上报平台`
  if (!to.meta.public && !localStorage.getItem('medical_token')) return '/login'
  if (to.path === '/login' && localStorage.getItem('medical_token')) return '/dashboard'
  const user = JSON.parse(localStorage.getItem('medical_user') || 'null') as { role?: string; permissions?: string[] } | null
  const required = to.meta.permissions as string[] | undefined
  if (required?.length && user?.role !== 'ADMIN' && !required.some((item) => user?.permissions?.includes(item))) return '/dashboard'
})

export default router
