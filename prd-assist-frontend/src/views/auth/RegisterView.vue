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
  <div class="auth-container">
    <div class="auth-card">
      <h2>AI 辅助设计平台</h2>
      <p class="subtitle">注册</p>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="handleRegister">
        <el-form-item prop="nickname" label="昵称">
          <el-input v-model="form.nickname" placeholder="请输入显示昵称" size="large" />
        </el-form-item>

        <el-form-item prop="username" label="用户名">
          <el-input v-model="form.username" placeholder="3-32位字母或数字" size="large" />
        </el-form-item>

        <el-form-item prop="password" label="密码">
          <el-input v-model="form.password" type="password" placeholder="6-64位密码" size="large" show-password />
        </el-form-item>

        <el-form-item prop="confirmPassword" label="确认密码">
          <el-input v-model="form.confirmPassword" type="password" placeholder="请再次输入密码" size="large" show-password />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" native-type="submit" size="large" :loading="loading" block>
            注册
          </el-button>
        </el-form-item>
      </el-form>

      <div class="auth-footer">
        已有账号？<router-link to="/login">立即登录</router-link>
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth-container {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}
.auth-card {
  width: 420px;
  padding: 40px 36px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.18);
}
.auth-card h2 {
  text-align: center;
  margin-bottom: 4px;
  color: #303133;
  font-size: 22px;
}
.subtitle {
  text-align: center;
  color: #909399;
  margin-bottom: 28px;
  font-size: 14px;
}
.auth-footer {
  text-align: center;
  color: #909399;
  font-size: 13px;
  margin-top: 8px;
}
.auth-footer a {
  color: #409eff;
  text-decoration: none;
}
</style>
