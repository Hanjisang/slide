<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Lock, User } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'

const router = useRouter(); const auth = useAuthStore(); const loading = ref(false)
const form = reactive({ username: 'admin', password: 'Admin@123' })
async function submit() { loading.value = true; try { await auth.login(form.username, form.password); router.push('/dashboard') } finally { loading.value = false } }
</script>

<template>
  <div class="login-page">
    <aside class="login-aside">
      <div class="login-brand"><div class="brand-mark"><span></span><i></i></div><strong>MedPath</strong></div>
      <div class="login-message"><h1>医疗数据及数字病理上报平台</h1><p>连接医院数据源，完成标准化治理、质量修正、数字切片管理和规范上报。</p></div>
      <small>Medical Data & Digital Pathology Platform</small>
    </aside>
    <main class="login-main">
      <el-form class="login-form" @submit.prevent="submit">
        <h2>登录系统</h2><p>使用平台账号进入工作台</p>
        <el-form-item><el-input v-model="form.username" size="large" placeholder="用户名" :prefix-icon="User" /></el-form-item>
        <el-form-item><el-input v-model="form.password" size="large" type="password" show-password placeholder="密码" :prefix-icon="Lock" @keyup.enter="submit" /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading">登录</el-button>
        <div class="demo-account">演示账号：admin<br>初始密码：Admin@123</div>
      </el-form>
    </main>
  </div>
</template>

