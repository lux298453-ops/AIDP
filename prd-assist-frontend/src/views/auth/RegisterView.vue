<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { register } from '@/api/auth'

const router = useRouter()
const formRef = ref<FormInstance>()

// 表单数据
const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: '',
})

// 校验规则
const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度3-32个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码长度6-64个字符', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== form.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { max: 50, message: '昵称最长50个字符', trigger: 'blur' },
  ],
}

const loading = ref(false)

/** 提交注册 */
async function handleRegister() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await register({
      username: form.username,
      password: form.password,
      nickname: form.nickname,
    })
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } catch {
    // 错误已由 axios 拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>
<template>
  <div class="auth-page">
    <!-- 浅色极光晨曦流光背景 (Light Aurora Mesh) -->
    <div class="aurora-canvas" aria-hidden="true">
      <div class="aurora-blob aurora-blob-1" />
      <div class="aurora-blob aurora-blob-2" />
      <div class="aurora-blob aurora-blob-3" />
      <div class="aurora-blob aurora-blob-4" />
      <div class="aurora-dot-overlay" />
    </div>

    <!-- 纯白透光磨砂水晶卡片 (Glassmorphism Card) -->
    <div class="auth-card">
      <!-- Logo & Title -->
      <div class="auth-header">
        <div class="auth-logo-box">
          <span>AI</span>
        </div>
        <h1 class="auth-title">创建您的账号</h1>
        <p class="auth-subtitle">注册即刻体验新一代 AI 文档与原型创作辅助</p>
      </div>

      <el-form ref="formRef" class="auth-form" :model="form" :rules="rules" label-position="top" @submit.prevent="handleRegister">
        <el-form-item prop="nickname" label="显示昵称">
          <el-input v-model="form.nickname" placeholder="例如：Alex / 产品经理小王" size="large" />
        </el-form-item>

        <el-form-item prop="username" label="用户名">
          <el-input v-model="form.username" placeholder="3-32 位字母或数字" size="large" />
        </el-form-item>

        <el-form-item prop="password" label="密码">
          <el-input v-model="form.password" type="password" placeholder="6-64 位安全密码" size="large" show-password />
        </el-form-item>

        <el-form-item prop="confirmPassword" label="确认密码">
          <el-input v-model="form.confirmPassword" type="password" placeholder="请再次输入确认密码" size="large" show-password />
        </el-form-item>

        <el-form-item class="auth-actions">
          <el-button class="auth-submit-btn" type="primary" native-type="submit" size="large" :loading="loading">
            立即注册
          </el-button>
        </el-form-item>
      </el-form>

      <div class="auth-footer">
        已有账号？<router-link to="/login" class="auth-link">立即登录</router-link>
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f8fafc;
  overflow: hidden;
  padding: 32px 16px;
}

/* ====== 极光流光画布 ====== */
.aurora-canvas {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
  z-index: 1;
}
.aurora-blob {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}
/* 极光 1：品牌蔚蓝 */
.aurora-blob-1 {
  top: 5%;
  left: 15%;
  width: 580px;
  height: 580px;
  background: radial-gradient(circle, rgba(59, 130, 246, 0.26) 0%, rgba(147, 197, 253, 0.12) 50%, transparent 75%);
  filter: blur(65px);
  animation: blobDrift1 16s infinite alternate ease-in-out;
}
/* 极光 2：原型暖橙 */
.aurora-blob-2 {
  bottom: 5%;
  right: 15%;
  width: 620px;
  height: 620px;
  background: radial-gradient(circle, rgba(249, 115, 22, 0.22) 0%, rgba(254, 215, 170, 0.10) 50%, transparent 75%);
  filter: blur(75px);
  animation: blobDrift2 18s infinite alternate-reverse ease-in-out;
}
/* 极光 3：雅致紫罗兰 */
.aurora-blob-3 {
  top: 38%;
  right: 25%;
  width: 480px;
  height: 480px;
  background: radial-gradient(circle, rgba(168, 85, 247, 0.20) 0%, rgba(233, 213, 255, 0.08) 50%, transparent 75%);
  filter: blur(70px);
  animation: blobDrift3 14s infinite alternate ease-in-out;
}
/* 极光 4：薄荷碧绿（通透清澈） */
.aurora-blob-4 {
  bottom: 25%;
  left: 20%;
  width: 460px;
  height: 460px;
  background: radial-gradient(circle, rgba(20, 184, 166, 0.18) 0%, rgba(153, 246, 228, 0.06) 50%, transparent 75%);
  filter: blur(70px);
  animation: blobDrift4 20s infinite alternate ease-in-out;
}

@keyframes blobDrift1 {
  0% { transform: translate(0, 0) scale(1); }
  50% { transform: translate(70px, -40px) scale(1.12); }
  100% { transform: translate(-30px, 60px) scale(0.92); }
}
@keyframes blobDrift2 {
  0% { transform: translate(0, 0) scale(1); }
  50% { transform: translate(-60px, -50px) scale(1.08); }
  100% { transform: translate(40px, 40px) scale(0.95); }
}
@keyframes blobDrift3 {
  0% { transform: translate(0, 0) scale(0.95); }
  50% { transform: translate(-50px, 60px) scale(1.15); }
  100% { transform: translate(40px, -30px) scale(1); }
}
@keyframes blobDrift4 {
  0% { transform: translate(0, 0) scale(1); }
  50% { transform: translate(50px, -60px) scale(1.1); }
  100% { transform: translate(-40px, 30px) scale(0.9); }
}

/* 科技微点阵纹理遮罩 */
.aurora-dot-overlay {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(rgba(15, 23, 42, 0.08) 1.2px, transparent 1.2px);
  background-size: 24px 24px;
  mask-image: radial-gradient(circle at center, black 50%, transparent 90%);
  -webkit-mask-image: radial-gradient(circle at center, black 50%, transparent 90%);
  pointer-events: none;
}

/* ====== 登录卡片（磨砂高透光水晶卡片） ====== */
.auth-card {
  width: 100%;
  max-width: 440px;
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  border: 1px solid rgba(255, 255, 255, 0.85);
  border-radius: 18px;
  box-shadow: 
    0 0 0 1px rgba(15, 23, 42, 0.06),
    0 25px 60px -15px rgba(15, 23, 42, 0.08),
    0 0 35px -5px rgba(59, 130, 246, 0.08);
  padding: 38px 36px 30px;
  position: relative;
  z-index: 10;
}

.auth-header {
  text-align: center;
  margin-bottom: 26px;
}
.auth-logo-box {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 11px;
  background: linear-gradient(135deg, #1e293b 0%, #0f172a 100%);
  color: #ffffff;
  font-weight: 700;
  font-size: 18px;
  margin-bottom: 14px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.22), 0 4px 10px rgba(15, 23, 42, 0.15);
}
.auth-title {
  font-size: 22px;
  font-weight: 700;
  color: #0f172a;
  letter-spacing: -0.025em;
  margin: 0 0 6px;
}
.auth-subtitle {
  font-size: 13px;
  color: #64748b;
  margin: 0;
  line-height: 1.5;
}

.auth-form {
  margin-bottom: 8px;
}
:deep(.el-form-item) {
  margin-bottom: 16px;
}
:deep(.el-form-item__label) {
  font-size: 12.5px !important;
  font-weight: 600 !important;
  color: #334155 !important;
  margin-bottom: 4px !important;
  line-height: 1.2 !important;
}
:deep(.el-input__wrapper) {
  border-radius: 8px !important;
  box-shadow: 0 0 0 1px #cbd5e1 inset !important;
  transition: all 0.15s ease !important;
}
:deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #2563eb inset, 0 0 0 3px rgba(37, 99, 235, 0.15) !important;
}

.auth-actions {
  margin-top: 24px;
  margin-bottom: 0;
}
.auth-submit-btn {
  width: 100%;
  height: 42px;
  border-radius: 8px !important;
  font-size: 14.5px !important;
  font-weight: 600 !important;
  letter-spacing: -0.01em;
  background: linear-gradient(180deg, #3b82f6 0%, #2563eb 100%) !important;
  border: 1px solid #1d4ed8 !important;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.25), 0 2px 6px rgba(37, 99, 235, 0.25) !important;
  transition: all 0.15s cubic-bezier(0.16, 1, 0.3, 1) !important;
}
.auth-submit-btn:hover {
  background: linear-gradient(180deg, #2563eb 0%, #1d4ed8 100%) !important;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.2), 0 4px 12px rgba(37, 99, 235, 0.3) !important;
  transform: translateY(-1px);
}
.auth-submit-btn:active {
  transform: translateY(1px);
}

.auth-footer {
  text-align: center;
  font-size: 12.5px;
  color: #64748b;
  margin-top: 22px;
  padding-top: 18px;
  border-top: 1px solid #f1f5f9;
}
.auth-link {
  color: #2563eb;
  font-weight: 600;
  text-decoration: none;
  margin-left: 4px;
  transition: color 0.15s ease;
}
.auth-link:hover {
  color: #1d4ed8;
  text-decoration: underline;
}
</style>
