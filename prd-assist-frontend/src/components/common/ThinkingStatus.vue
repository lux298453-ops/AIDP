<script setup lang="ts">
/**
 * Claude/Codex 风格的生成状态提示：
 * 轮播趣味状态词（英文 + 中文），替代直接滚动原始流式内容的预览。
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'

const props = defineProps<{
  /** 阶段消息（时间顺序，最后一条为当前步骤），显示为步骤时间线 */
  steps?: string[]
}>()

const visibleSteps = computed(() => (props.steps || []).filter(Boolean).slice(-5))

const WORDS: string[] = [
  'Thinking', 'Brewing', 'Crafting', 'Forging', 'Conjuring',
  'Simmering', 'Percolating', 'Synthesizing', 'Pondering', 'Cooking',
  'Hatching', 'Weaving', 'Sculpting', 'Marinating', 'Crunching',
  'Manifesting', 'Ruminating', 'Transmuting', 'Ideating', 'Vibing',
]

const index = ref(Math.floor(Math.random() * WORDS.length))
const visible = ref(true)
const elapsedSeconds = ref(0)

let rotateTimer: ReturnType<typeof setInterval> | null = null
let tickTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  rotateTimer = setInterval(() => {
    visible.value = false
    setTimeout(() => {
      index.value = (index.value + 1 + Math.floor(Math.random() * 3)) % WORDS.length
      visible.value = true
    }, 260)
  }, 2600)
  tickTimer = setInterval(() => { elapsedSeconds.value += 1 }, 1000)
})

onBeforeUnmount(() => {
  if (rotateTimer) clearInterval(rotateTimer)
  if (tickTimer) clearInterval(tickTimer)
})

function formatElapsed(total: number) {
  const m = Math.floor(total / 60)
  const s = total % 60
  return m > 0 ? `${m} 分 ${s} 秒` : `${s} 秒`
}
</script>

<template>
  <div class="thinking-status">
    <div class="status-word" :class="{ hidden: !visible }">
      <span class="word-en">{{ WORDS[index] }}</span>
      <span class="dots"><i /><i /><i /></span>
    </div>
    <div class="elapsed">已进行 {{ formatElapsed(elapsedSeconds) }}</div>
    <div v-if="visibleSteps.length" class="steps">
      <div
        v-for="(step, i) in visibleSteps"
        :key="i + ':' + step"
        class="step"
        :class="{ current: i === visibleSteps.length - 1 }"
      >
        <span class="step-icon">
          <span v-if="i < visibleSteps.length - 1" class="step-check">✓</span>
          <span v-else class="step-pulse" />
        </span>
        <span class="step-text">{{ step }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.thinking-status {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 18px 0 6px;
  user-select: none;
}
.status-word {
  display: flex;
  align-items: baseline;
  gap: 10px;
  transition: opacity 0.26s ease, transform 0.26s ease;
  opacity: 1;
  transform: translateY(0);
}
.status-word.hidden {
  opacity: 0;
  transform: translateY(6px);
}
.word-en {
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 0.02em;
  background: linear-gradient(90deg, #c08532, #26251e, #c08532);
  background-size: 200% 100%;
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  animation: shimmer 2.6s linear infinite;
}
@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: 0% 0; }
}
.dots {
  display: inline-flex;
  gap: 4px;
  align-items: center;
}
.dots i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #c08532;
  animation: bounce 1.2s ease-in-out infinite;
}
.dots i:nth-child(2) { animation-delay: 0.15s; }
.dots i:nth-child(3) { animation-delay: 0.3s; }
@keyframes bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.5; }
  30% { transform: translateY(-5px); opacity: 1; }
}
.elapsed {
  font-size: 11px;
  color: rgba(38, 37, 30, 0.35);
  letter-spacing: 0.03em;
}
.steps {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 7px;
  align-items: flex-start;
  max-width: 420px;
}
.step {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: rgba(38, 37, 30, 0.35);
  animation: step-in 0.3s ease;
}
.step.current {
  color: rgba(38, 37, 30, 0.8);
}
@keyframes step-in {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}
.step-icon {
  width: 14px;
  display: inline-flex;
  justify-content: center;
  flex-shrink: 0;
}
.step-check {
  color: #1f8a65;
  font-size: 11px;
}
.step-pulse {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #c08532;
  animation: pulse 1.4s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { transform: scale(0.8); opacity: 0.55; box-shadow: 0 0 0 0 rgba(192, 133, 50, 0.35); }
  50% { transform: scale(1); opacity: 1; box-shadow: 0 0 0 5px rgba(192, 133, 50, 0); }
}
.step-text {
  line-height: 1.5;
  text-align: left;
}
</style>
