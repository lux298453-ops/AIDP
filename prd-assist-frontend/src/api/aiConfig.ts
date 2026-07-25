import client from './client'
import type { AiModelConfig, AiModelConfigRequest, Result } from '@/types/api'

export function getAiConfig() {
  return client.get<Result<AiModelConfig>>('/ai-config')
}

export function saveAiConfig(data: AiModelConfigRequest) {
  return client.put<Result<AiModelConfig>>('/ai-config', data)
}
