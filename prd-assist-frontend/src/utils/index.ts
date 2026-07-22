/**
 * 格式化文件大小
 */
export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

/**
 * 格式化日期
 */
export function formatDate(dateStr: string): string {
  if (!dateStr) return ''
  return new Date(dateStr).toLocaleString('zh-CN')
}

/**
 * 获取任务类型的中文名称
 */
export function getTaskTypeLabel(type: string): string {
  const labels: Record<string, string> = {
    PRD_GENERATE: 'PRD生成',
    PRD_ENHANCE: 'PRD增强',
    PRD_REVIEW: 'PRD审查',
    PROTOTYPE_GENERATE: '原型生成',
  }
  return labels[type] || type
}

/**
 * 获取任务状态的中文名称和颜色
 */
export function getTaskStatusInfo(status: string) {
  const map: Record<string, { label: string; color: string }> = {
    PENDING: { label: '等待中', color: '#909399' },
    RUNNING: { label: '运行中', color: '#409eff' },
    SUCCESS: { label: '已完成', color: '#67c23a' },
    FAILED: { label: '失败', color: '#f56c6c' },
  }
  return map[status] || { label: status, color: '#909399' }
}
