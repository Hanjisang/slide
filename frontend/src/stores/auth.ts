import { defineStore } from 'pinia'
import api from '../api'

interface User { id: number; username: string; displayName: string; role: string; permissions: string[] }

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('medical_token') || '',
    user: JSON.parse(localStorage.getItem('medical_user') || 'null') as User | null,
  }),
  actions: {
    async login(username: string, password: string) {
      const data = await api.post('/auth/login', { username, password }) as { token: string; user: User }
      this.token = data.token; this.user = data.user
      localStorage.setItem('medical_token', data.token)
      localStorage.setItem('medical_user', JSON.stringify(data.user))
    },
    async logout() {
      try { await api.post('/auth/logout') } finally {
        this.token = ''; this.user = null
        localStorage.removeItem('medical_token'); localStorage.removeItem('medical_user')
      }
    },
    hasAny(required?: string[]) {
      if (!required?.length || this.user?.role === 'ADMIN') return true
      return required.some((permission) => this.user?.permissions?.includes(permission))
    },
  },
})
