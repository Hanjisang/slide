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
        { path: 'data-sources', component: () => import('./views/DataSourceView.vue'), meta: { title: '数据源管理' } },
        { path: 'medical-data', component: () => import('./views/MedicalDataView.vue'), meta: { title: '医疗数据' } },
        { path: 'quality', component: () => import('./views/QualityView.vue'), meta: { title: '数据质量' } },
        { path: 'slides', component: () => import('./views/SlideView.vue'), meta: { title: '数字切片' } },
        { path: 'reports', component: () => import('./views/ReportView.vue'), meta: { title: '数据上报' } },
        { path: 'system', component: () => import('./views/SystemView.vue'), meta: { title: '系统管理' } },
      ],
    },
  ],
})

router.beforeEach((to) => {
  document.title = `${String(to.meta.title || '')} - 医疗数据上报平台`
  if (!to.meta.public && !localStorage.getItem('medical_token')) return '/login'
  if (to.path === '/login' && localStorage.getItem('medical_token')) return '/dashboard'
})

export default router

