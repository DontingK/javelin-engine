<template>
  <div class="panel project-panel">
    <div class="empty-tip">项目面板（待实现）</div>
  </div>
</template>

<script setup lang="ts">
/**
 * 项目面板占位组件。
 * 
 * <p>TODO: 服务层搭建后实现目录树和文件列表功能。</p>
 */
</script>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 8px;
}

.empty-tip {
  color: var(--editor-text-dim);
  font-size: 12px;
  text-align: center;
  margin-top: 16px;
}
</style>
