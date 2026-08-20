import axios from 'axios'
import { ElMessage } from 'element-plus'

export interface ApiEnvelope<T> { code: number; message: string; data: T }

const client = axios.create({ baseURL: '/api', timeout: 30000 })
client.interceptors.request.use((config) => {
  const token = localStorage.getItem('medical_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})
client.interceptors.response.use(
  (response) => response.config.responseType === 'blob' ? response.data : response.data.data,
  (error) => {
    const message = error.response?.data?.message || error.message || '请求失败'
    if (error.response?.status === 401) {
      localStorage.removeItem('medical_token')
      if (location.pathname !== '/login') location.href = '/login'
    } else if (error.config?.headers?.['X-Silent-Error'] !== 'true') ElMessage.error(message)
    return Promise.reject(error)
  },
)

// Response handlers unwrap the common { code, message, data } envelope.
export default client as any
