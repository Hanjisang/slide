export const statusLabels: Record<string, string> = {
  READY: '可用',
  FAILED: '失败',
  PENDING: '待处理',
  RUNNING: '执行中',
  SUCCESS: '成功',
  BLOCKED: '已阻断',
  ARCHIVED: '已归档',
  NOT_ARCHIVED: '未归档',
  CORRECTED: '已修正',
  IGNORED: '已忽略',
  PASSED: '通过',
  UPLOADING: '上传中',
  UPLOADED: '已上传',
  PARSING: '解析中',
  PAUSED: '已暂停',
  COMPLETED: '已完成',
  WAITING: '待执行',
  INIT: '待开始',
  INTERRUPTED: '已中断',
  RETRYING: '重试中',
  REPORTED: '已上报',
  PRECHECKING: '预审核中',
  GENERATING: '生成中',
  REPORTING: '上报中',
  OPEN: '待处理',
  ACKNOWLEDGED: '已确认',
  CLOSED: '已关闭',
  METADATA_ONLY: '仅登记',
  SDK_NOT_AVAILABLE: '解析组件不可用',
  PARSER_UNAVAILABLE: '解析引擎不可用',
  SDK_REQUIRED: '需要解析组件',
  SDK_PRESENT: '解析组件已加载',
  AVAILABLE: '可用',
}

export function statusLabel(value: unknown): string {
  const key = String(value ?? '')
  return statusLabels[key] || key || '未知'
}

export function statusType(value: unknown): 'success' | 'warning' | 'danger' | 'info' {
  const key = String(value ?? '')
  if (['READY', 'SUCCESS', 'PASSED', 'CORRECTED', 'COMPLETED', 'AVAILABLE'].includes(key)) return 'success'
  if (['FAILED', 'BLOCKED', 'SDK_NOT_AVAILABLE', 'PARSER_UNAVAILABLE', 'SDK_REQUIRED'].includes(key)) return 'danger'
  if (['PENDING', 'RUNNING', 'UPLOADING', 'UPLOADED', 'PARSING', 'PRECHECKING', 'GENERATING', 'REPORTING', 'WAITING', 'OPEN', 'INIT', 'INTERRUPTED', 'RETRYING'].includes(key)) return 'warning'
  return 'info'
}

export function priorityLabel(value: unknown): string {
  const priority = Number(value)
  if (priority >= 7) return '高'
  if (priority >= 3) return '中'
  return '低'
}

export function frequencyLabel(value: unknown): string {
  return ({ MANUAL: '手动', HOURLY: '每小时', DAILY: '每日', WEEKLY: '每周', MONTHLY: '每月', CRON: '自定义周期' } as Record<string, string>)[String(value)] || String(value ?? '')
}
