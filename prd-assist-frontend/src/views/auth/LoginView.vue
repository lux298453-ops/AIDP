<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { login } from '@/api/auth'
import { useUserStore } from '@/store/modules/user'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref<FormInstance>()

// 表单数据
const form = reactive({
  username: '',
  password: '',
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
}

const loading = ref(false)

/** 提交登录 */
async function handleLogin() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const res = await login({ username: form.username, password: form.password })
    const { token, userId, username, nickname } = res.data.data
    userStore.setAuth(token, userId, username, nickname)
    ElMessage.success(`欢迎回来，${nickname || username}`)
    router.push('/')
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
      <h2>AI文档平台</h2>

      <el-form ref="formRef" class="auth-form" :model="form" :rules="rules" label-position="top" @submit.prevent="handleLogin">
        <el-form-item prop="username" label="用户名">
          <el-input v-model="form.username" placeholder="请输入用户名" size="large" />
        </el-form-item>

        <el-form-item prop="password" label="密码">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" size="large" show-password />
        </el-form-item>

        <el-form-item class="auth-actions">
          <el-button class="auth-submit" type="primary" native-type="submit" size="large" :loading="loading">
            登录
          </el-button>
        </el-form-item>
      </el-form>

      <div class="auth-footer">
        没有账号？<router-link to="/register">立即注册</router-link>
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
  background: #f2f1ed;
}
.auth-card {
  width: 420px;
  max-width: calc(100vw - 48px);
  padding: 44px 42px 36px;
  background: #fff;
  border-radius: 10px;
  box-shadow: rgba(0, 0, 0, 0.14) 0px 28px 70px, rgba(0, 0, 0, 0.1) 0px 14px 32px, rgba(38, 37, 30, 0.1) 0px 0px 0px 1px;
}
.auth-form {
  margin-top: 26px;
}
.auth-actions {
  margin-top: 22px;
  margin-bottom: 10px;
}
:deep(.auth-actions .el-form-item__content) {
  justify-content: center;
}
.auth-submit {
  width: 180px;
  min-width: 180px;
}
.auth-card h2 {
  text-align: center;
  margin-bottom: 4px;
  color: #26251e;
  font-size: 26px;
  font-weight: 400;
  letter-spacing: -0.325px;
}
.subtitle {
  text-align: center;
  color: rgba(38, 37, 30, 0.55);
  margin-bottom: 28px;
  font-size: 14px;
  font-family: 'Source Serif 4', 'Iowan Old Style', Georgia, serif;
}
.auth-footer {
  text-align: center;
  color: rgba(38, 37, 30, 0.55);
  font-size: 13px;
  margin-top: 8px;
}
.auth-footer a {
  color: #f54e00;
  text-decoration: none;
  transition: color 0.15s;
}
.auth-footer a:hover {
  color: #cf2d56;
}
</style>
