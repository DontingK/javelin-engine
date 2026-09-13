<template>
  <div class="project-loading-page">
    <div class="loading-content">
      <div class="loading-spinner"></div>
      <div class="loading-text">正在加载项目</div>
      <div v-if="projectName" class="loading-project">{{ projectName }}</div>
      <div class="progress-bar-track">
        <div class="progress-bar-thumb"></div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 项目加载页面。
 *
 * <p>在项目打开或切换期间显示加载状态，包含持续滚动的进度条。</p>
 */
interface Props {
  /** 项目名称 */
  projectName?: string
}

withDefaults(defineProps<Props>(), {
  projectName: ''
})
</script>

<style scoped>
.project-loading-page {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  background: var(--editor-bg);
  color: var(--editor-text);
}

.loading-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  width: 320px;
}

.loading-spinner {
  width: 40px;
  height: 40px;
  border: 3px solid var(--editor-border);
  border-top-color: var(--editor-accent);
  border-radius: 50%;
  animation: project-loading-spin 0.8s linear infinite;
}

.loading-text {
  font-size: 16px;
  font-weight: 500;
  color: var(--editor-text-heading);
}

.loading-project {
  font-size: 13px;
  color: var(--editor-text-dim);
  max-width: 100%;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.progress-bar-track {
  position: relative;
  width: 100%;
  height: 4px;
  background: var(--editor-border);
  border-radius: 2px;
  overflow: hidden;
}

.progress-bar-thumb {
  position: absolute;
  top: 0;
  left: -40%;
  width: 40%;
  height: 100%;
  background: var(--editor-accent);
  border-radius: 2px;
  animation: project-loading-scroll 1.2s linear infinite;
}

@keyframes project-loading-spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

@keyframes project-loading-scroll {
  from {
    transform: translateX(0);
  }
  to {
    transform: translateX(350%);
  }
}
</style>
