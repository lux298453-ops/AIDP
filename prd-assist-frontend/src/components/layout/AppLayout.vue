<script setup lang="ts">
import HeaderBar from './HeaderBar.vue'
</script>

<template>
  <div class="app-layout">
    <HeaderBar />
    <div class="content">
      <router-view v-slot="{ Component }" :key="$route.fullPath">
        <component :is="Component" class="route-view" />
      </router-view>
    </div>
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background-color: #f8fafc;
  overflow: hidden;
}
.content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  background-color: #f8fafc;
  display: flex;
  flex-direction: column;
}
.route-view {
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  display: flex;
  flex-direction: column;
}
</style>
