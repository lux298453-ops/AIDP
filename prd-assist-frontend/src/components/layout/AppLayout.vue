<script setup lang="ts">
import HeaderBar from './HeaderBar.vue'
</script>

<template>
  <div class="app-layout">
    <HeaderBar />
    <div class="content">
      <router-view />
    </div>
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
}
.content {
  flex: 1;
  overflow-y: auto;
  background: #f5f7fa;
}
</style>
