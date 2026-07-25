<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getAiConfig, saveAiConfig } from '@/api/aiConfig'
import type { AiModelConfigRequest } from '@/types/api'

const loading = ref(false)
const saving = ref(false)
const savedMaskedKey = ref('')
const hasSavedKey = ref(false)
const savedProvider = ref('')
const savedBaseUrl = ref('')

type Preset = {
  label: string
  value: string
  desc: string
  baseUrl: string
  model: string
  apiType: string
  appendApiPath: boolean
  openAiAuthEnabled: boolean
  authHeaderType: string
  reasoningEffort: string
  imageDetail: string
  maxOutputTokens: number
  supportsImage: boolean
}

const presets: Preset[] = [
  {
    label: 'ChatGPT / OpenAI',
    value: 'openai',
    desc: '官方 OpenAI API，适合 GPT 系列模型',
    baseUrl: 'https://api.openai.com',
    model: 'gpt-5.1',
    apiType: 'responses',
    appendApiPath: true,
    openAiAuthEnabled: true,
    authHeaderType: 'bearer',
    reasoningEffort: 'medium',
    imageDetail: 'high',
    maxOutputTokens: 16384,
    supportsImage: true,
  },
  {
    label: 'Claude',
    value: 'claude',
    desc: 'Anthropic 官方接口，适合 Claude 系列模型',
    baseUrl: 'https://api.anthropic.com',
    model: 'claude-sonnet-4-20250514',
    apiType: 'messages',
    appendApiPath: true,
    openAiAuthEnabled: true,
    authHeaderType: 'x-api-key',
    reasoningEffort: '',
    imageDetail: 'high',
    maxOutputTokens: 16384,
    supportsImage: true,
  },
  {
    label: 'DeepSeek',
    value: 'deepseek',
    desc: 'DeepSeek 官方接口，使用 Chat Completions',
    baseUrl: 'https://api.deepseek.com',
    model: 'deepseek-chat',
    apiType: 'chat-completions',
    appendApiPath: true,
    openAiAuthEnabled: true,
    authHeaderType: 'bearer',
    reasoningEffort: '',
    imageDetail: 'high',
    maxOutputTokens: 8192,
    supportsImage: false,
  },
  {
    label: '中转站 / 自定义',
    value: 'custom',
    desc: '兼容 OpenAI 的聚合平台或私有网关',
    baseUrl: '',
    model: '',
    apiType: 'chat-completions',
    appendApiPath: true,
    openAiAuthEnabled: true,
    authHeaderType: 'bearer',
    reasoningEffort: '',
    imageDetail: 'high',
    maxOutputTokens: 16384,
    supportsImage: true,
  },
]

const form = reactive<AiModelConfigRequest>({
  provider: 'openai',
  baseUrl: 'https://api.openai.com',
  apiKey: '',
  model: 'gpt-5.1',
  apiType: 'responses',
  appendApiPath: true,
  openAiAuthEnabled: true,
  authHeaderType: 'bearer',
  actorAuthorization: '',
  reasoningEffort: 'medium',
  disableResponseStorage: true,
  maxOutputTokens: 16384,
  imageDetail: 'high',
  enabled: true,
})

const selectedPreset = computed(() => presets.find(item => item.value === form.provider) || presets[0])
const isClaude = computed(() => form.provider === 'claude')
const isDeepSeek = computed(() => form.provider === 'deepseek')
const isCustom = computed(() => form.provider === 'custom')
const showApiType = computed(() => isCustom.value)
const showOpenAiAdvanced = computed(() => form.provider === 'openai' || isCustom.value)
const showImageDetail = computed(() => selectedPreset.value.supportsImage && !isDeepSeek.value)
const modelPlaceholder = computed(() => selectedPreset.value.model || '请输入模型名称')
const baseUrlPlaceholder = computed(() => selectedPreset.value.baseUrl || 'https://api.example.com')
const apiKeyLabel = computed(() => isClaude.value ? 'Claude API Key' : isDeepSeek.value ? 'DeepSeek API Key' : 'API Key')

function applyPreset(provider: string) {
  const preset = presets.find(item => item.value === provider)
  if (!preset) return
  form.provider = preset.value
  form.baseUrl = preset.baseUrl
  form.model = preset.model
  form.apiType = preset.apiType
  form.appendApiPath = preset.appendApiPath
  form.openAiAuthEnabled = preset.openAiAuthEnabled
  form.authHeaderType = preset.authHeaderType
  form.reasoningEffort = preset.reasoningEffort
  form.imageDetail = preset.imageDetail
  form.maxOutputTokens = preset.maxOutputTokens
  if (preset.value !== 'custom') form.actorAuthorization = ''
}

async function loadConfig() {
  loading.value = true
  try {
    const { data } = await getAiConfig()
    const config = data.data
    Object.assign(form, {
      provider: config.provider || 'openai',
      baseUrl: config.baseUrl || '',
      apiKey: '',
      model: config.model || '',
      apiType: config.apiType || 'responses',
      appendApiPath: config.appendApiPath !== false,
      openAiAuthEnabled: config.openAiAuthEnabled !== false,
      authHeaderType: config.authHeaderType || 'bearer',
      actorAuthorization: config.actorAuthorization || '',
      reasoningEffort: config.reasoningEffort || '',
      disableResponseStorage: config.disableResponseStorage !== false,
      maxOutputTokens: config.maxOutputTokens || 16384,
      imageDetail: config.imageDetail || 'high',
      enabled: config.enabled !== false,
    })
    savedMaskedKey.value = config.maskedApiKey || ''
    hasSavedKey.value = !!config.hasApiKey
    savedProvider.value = form.provider
    savedBaseUrl.value = form.baseUrl
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  if (!form.baseUrl.trim()) {
    ElMessage.warning('请填写 Base URL')
    return
  }
  if (!form.model.trim()) {
    ElMessage.warning('请填写模型名称')
    return
  }
  if (!form.apiKey?.trim() && !hasSavedKey.value) {
    ElMessage.warning('请填写 API Key')
    return
  }

  const providerOrEndpointChanged = savedProvider.value
    && (savedProvider.value !== form.provider || savedBaseUrl.value !== form.baseUrl.trim())
  if (!form.apiKey?.trim() && providerOrEndpointChanged) {
    ElMessage.warning('切换供应商或 Base URL 时请重新填写 API Key')
    return
  }

  saving.value = true
  try {
    const payload: AiModelConfigRequest = {
      ...form,
      baseUrl: form.baseUrl.trim(),
      model: form.model.trim(),
      apiType: isCustom.value ? form.apiType : selectedPreset.value.apiType,
      appendApiPath: isCustom.value ? form.appendApiPath : selectedPreset.value.appendApiPath,
      openAiAuthEnabled: isCustom.value ? form.openAiAuthEnabled : selectedPreset.value.openAiAuthEnabled,
      authHeaderType: isCustom.value ? form.authHeaderType : selectedPreset.value.authHeaderType,
      actorAuthorization: isCustom.value ? form.actorAuthorization?.trim() || undefined : undefined,
      reasoningEffort: showOpenAiAdvanced.value ? form.reasoningEffort?.trim() || undefined : undefined,
      imageDetail: showImageDetail.value ? form.imageDetail : selectedPreset.value.imageDetail,
      apiKey: form.apiKey?.trim() || undefined,
    }
    const { data } = await saveAiConfig(payload)
    savedMaskedKey.value = data.data.maskedApiKey || ''
    hasSavedKey.value = !!data.data.hasApiKey
    savedProvider.value = data.data.provider || form.provider
    savedBaseUrl.value = data.data.baseUrl || form.baseUrl
    form.apiKey = ''
    ElMessage.success('模型配置已保存')
  } finally {
    saving.value = false
  }
}

onMounted(loadConfig)
</script>

<template>
  <div class="settings-page" v-loading="loading">
    <div class="page-header">
      <div>
        <h2>模型设置</h2>
        <p>配置当前账号用于 PRD 生成、增强、审查和原型生成的模型接口。</p>
      </div>
      <el-tag v-if="hasSavedKey" type="success" effect="plain">已配置 Key：{{ savedMaskedKey }}</el-tag>
      <el-tag v-else type="warning" effect="plain">未配置 API Key</el-tag>
    </div>

    <div class="settings-layout">
      <section class="provider-panel">
        <button
          v-for="preset in presets"
          :key="preset.value"
          class="provider-option"
          :class="{ active: form.provider === preset.value }"
          @click="applyPreset(preset.value)"
        >
          <span class="provider-name">{{ preset.label }}</span>
          <span class="provider-desc">{{ preset.desc }}</span>
        </button>
      </section>

      <section class="form-panel">
        <div class="form-title">
          <div>
            <h3>{{ selectedPreset.label }}</h3>
            <p>{{ selectedPreset.desc }}</p>
          </div>
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
        </div>

        <el-form label-position="top" class="ai-form">
          <div class="section-title">基础配置</div>

          <el-form-item label="Base URL">
            <el-input v-model="form.baseUrl" :placeholder="baseUrlPlaceholder" />
          </el-form-item>

          <div class="form-grid">
            <el-form-item label="模型名称">
              <el-input v-model="form.model" :placeholder="modelPlaceholder" />
            </el-form-item>
            <el-form-item :label="apiKeyLabel">
              <el-input
                v-model="form.apiKey"
                type="password"
                show-password
                autocomplete="off"
                :placeholder="hasSavedKey ? '留空则继续使用已保存的 Key' : '请输入 API Key'"
              />
            </el-form-item>
          </div>

          <el-alert
            v-if="isDeepSeek"
            class="provider-note"
            title="DeepSeek 当前按文本模型使用；上传参考图时会自动转为文字描述流程。"
            type="info"
            :closable="false"
            show-icon
          />

          <el-collapse class="advanced">
            <el-collapse-item title="高级配置" name="advanced">
              <div v-if="showApiType" class="form-grid">
                <el-form-item label="接口类型">
                  <el-select v-model="form.apiType">
                    <el-option label="Responses" value="responses" />
                    <el-option label="Chat Completions" value="chat-completions" />
                  </el-select>
                </el-form-item>
                <el-form-item label="API Key 发送方式">
                  <el-select v-model="form.authHeaderType" :disabled="!form.openAiAuthEnabled">
                    <el-option label="Authorization: Bearer" value="bearer" />
                    <el-option label="x-api-key" value="x-api-key" />
                    <el-option label="x-goog-api-key" value="x-goog-api-key" />
                    <el-option label="不发送" value="none" />
                  </el-select>
                </el-form-item>
              </div>

              <div v-if="isCustom" class="switch-row">
                <el-checkbox v-model="form.appendApiPath">自动拼接 API 路径</el-checkbox>
                <el-checkbox v-model="form.openAiAuthEnabled">发送 API Key 认证</el-checkbox>
              </div>

              <div class="form-grid">
                <el-form-item v-if="showOpenAiAdvanced" label="Reasoning Effort">
                  <el-select v-model="form.reasoningEffort" clearable placeholder="不发送">
                    <el-option label="low" value="low" />
                    <el-option label="medium" value="medium" />
                    <el-option label="high" value="high" />
                    <el-option label="xhigh" value="xhigh" />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="showImageDetail" label="图片细节">
                  <el-select v-model="form.imageDetail">
                    <el-option label="auto" value="auto" />
                    <el-option label="low" value="low" />
                    <el-option label="high" value="high" />
                  </el-select>
                </el-form-item>
              </div>

              <div class="form-grid">
                <el-form-item label="最大输出 Token">
                  <el-input-number v-model="form.maxOutputTokens" :min="1024" :max="100000" :step="1024" />
                </el-form-item>
                <el-form-item v-if="isCustom" label="自定义 Actor Header">
                  <el-input v-model="form.actorAuthorization" placeholder="可选" />
                </el-form-item>
              </div>

              <el-checkbox v-if="showOpenAiAdvanced" v-model="form.disableResponseStorage">
                关闭响应存储
              </el-checkbox>
            </el-collapse-item>
          </el-collapse>

          <div class="form-actions">
            <el-button @click="loadConfig">重置</el-button>
            <el-button type="primary" :loading="saving" @click="handleSave">保存配置</el-button>
          </div>
        </el-form>
      </section>
    </div>
  </div>
</template>

<style scoped>
.settings-page {
  max-width: 1180px;
  margin: 0 auto;
  padding: 36px 48px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 20px;
  margin-bottom: 22px;
}

.page-header h2 {
  margin: 0 0 6px;
  font-size: 26px;
  font-weight: 500;
  color: #26251e;
}

.page-header p {
  margin: 0;
  color: rgba(38, 37, 30, 0.58);
  font-size: 14px;
}

.settings-layout {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 18px;
}

.provider-panel,
.form-panel {
  background: #fff;
  border: 1px solid rgba(38, 37, 30, 0.1);
  border-radius: 8px;
}

.provider-panel {
  padding: 10px;
}

.provider-option {
  width: 100%;
  min-height: 76px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  text-align: left;
  padding: 14px;
  cursor: pointer;
  transition: all 0.15s;
}

.provider-option:hover {
  background: #f7f7f4;
}

.provider-option.active {
  background: #ecf5ff;
  border-color: #409eff;
}

.provider-name,
.provider-desc {
  display: block;
}

.provider-name {
  font-size: 15px;
  font-weight: 600;
  color: #26251e;
  margin-bottom: 6px;
}

.provider-desc {
  font-size: 12px;
  color: rgba(38, 37, 30, 0.55);
  line-height: 1.45;
}

.form-panel {
  padding: 24px;
}

.section-title {
  margin-bottom: 14px;
  font-size: 14px;
  font-weight: 600;
  color: #26251e;
}

.form-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 20px;
}

.form-title h3 {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 500;
  color: #26251e;
}

.form-title p {
  margin: 0;
  font-size: 13px;
  color: rgba(38, 37, 30, 0.55);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.switch-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;
  margin: 2px 0 16px;
}

.provider-note {
  margin-bottom: 16px;
}

.advanced {
  border-top: 1px solid rgba(38, 37, 30, 0.08);
  border-bottom: 1px solid rgba(38, 37, 30, 0.08);
  margin-bottom: 18px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 900px) {
  .settings-page {
    padding: 28px 18px;
  }

  .page-header,
  .settings-layout,
  .form-grid {
    grid-template-columns: 1fr;
  }

  .page-header {
    display: grid;
  }
}
</style>
