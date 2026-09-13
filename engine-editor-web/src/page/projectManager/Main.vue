<template>
  <div class="project-page">
    <aside class="sidebar">
      <div class="brand">Javelin Engine 2</div>
      <JeButton
          type="primary"
          :loading="isSelectingDirectory"
          @click="startCreate"
      >
        新建项目
      </JeButton>
      <JeButton
          type="secondary"
          @click="startOpen"
      >
        打开项目
      </JeButton>
    </aside>

    <main class="main-area">
      <template v-if="mode === 'list'">
        <h1 class="page-title">最近项目</h1>
        <ul v-if="recentProjects.length > 0" class="project-list">
          <li
              v-for="project in recentProjects"
              :key="project.id"
              class="project-card"
              @click="handleRecentProjectClick(project)"
          >
            <span class="project-name">{{ project.name }}</span>
            <span class="project-path">{{ project.path }}</span>
          </li>
        </ul>
        <div v-else class="empty-tip">
          暂无最近项目，点击左侧"新建项目"或"打开项目"。
        </div>
      </template>

      <template v-else-if="mode === 'create'">
        <h1 class="page-title">新建项目</h1>
        <div class="form">
          <label class="form-row">
            <span class="form-label">项目名称</span>
            <input v-model="form.name" class="form-input" type="text" placeholder="输入项目名称"/>
          </label>
          <label class="form-row">
            <span class="form-label">项目路径</span>
            <input v-model="form.path" class="form-input" type="text" placeholder="输入项目路径"/>
          </label>
          <div class="form-actions">
            <JeButton type="secondary" @click="mode = 'list'">取消</JeButton>
            <JeButton
                type="primary"
                :loading="isCreating"
                :disabled="!canSubmit"
                @click="submitCreate"
            >
              创建
            </JeButton>
          </div>
        </div>
      </template>

      <template v-else-if="mode === 'open'">
        <h1 class="page-title">打开项目</h1>
        <div class="form">
          <label class="form-row">
            <span class="form-label">项目路径</span>
            <input v-model="form.path" class="form-input" type="text" placeholder="输入项目路径"/>
          </label>
          <div class="form-actions">
            <JeButton type="secondary" @click="mode = 'list'">取消</JeButton>
            <JeButton
                type="primary"
                :loading="isOpening"
                :disabled="!form.path"
                @click="submitOpen"
            >
              打开
            </JeButton>
          </div>
        </div>
      </template>
    </main>
  </div>
</template>

<script setup lang="ts">
import {ref, computed, reactive, onMounted, watch} from 'vue'
import {useProject, type ProjectInfo} from './composables/useProject.ts'
import {selectDirectory} from '../../service/fileSystemService.ts'
import {getRecentProjects, openProject, createProject} from '../../service/projectService.ts'
import type {FileVo} from '../../service/projectService.ts'
import JeButton from '../../components/common/JeButton.vue'

const {startLoadingProject, openProject: openProjectPage, cancelLoadingProject} = useProject()

type PageMode = 'list' | 'create' | 'open'

const mode = ref<PageMode>('list')

const recentProjects = ref<ProjectInfo[]>([])

const form = reactive({
  name: '',
  path: ''
})

const isSelectingDirectory = ref(false)
const isCreating = ref(false)
const isOpening = ref(false)
const isOpeningRecent = ref<string | null>(null)

const canSubmit = computed(() => form.name.trim().length > 0 && form.path.trim().length > 0)

function loadRecentProjects(): void {
  getRecentProjects()
      .then(list => {
        recentProjects.value = list.map(item => ({
          id: item.path,
          name: item.name,
          path: item.path
        }))
      })
      .catch(error => {
        console.warn('加载最近项目失败:', error)
      })
}

watch(mode, newMode => {
  if (newMode === 'list') {
    loadRecentProjects()
  }
})

onMounted(() => {
  loadRecentProjects()
})

function startCreate(): void {
  if (isSelectingDirectory.value) {
    return
  }
  form.name = ''
  form.path = ''
  isSelectingDirectory.value = true
  selectDirectory()
      .then(path => {
        if (path) {
          form.path = path
          mode.value = 'create'
        }
      })
      .catch(error => {
        console.warn('选择目录失败:', error)
      })
      .finally(() => {
        isSelectingDirectory.value = false
      })
}

function startOpen(): void {
  form.name = ''
  form.path = ''
  mode.value = 'open'
}

function submitCreate(): void {
  if (isCreating.value || !canSubmit.value) {
    return
  }
  isCreating.value = true
  const pendingProject: ProjectInfo = {
    id: Date.now().toString(),
    name: form.name.trim(),
    path: form.path.trim()
  }
  startLoadingProject(pendingProject)
  createProject({name: pendingProject.name, directory: pendingProject.path})
      .then((data: FileVo) => {
        const project: ProjectInfo = {
          id: pendingProject.id,
          name: data.name,
          path: data.path
        }
        recentProjects.value.unshift(project)
        openProjectPage(project)
      })
      .catch(error => {
        console.warn('创建项目失败:', error)
        cancelLoadingProject()
      })
      .finally(() => {
        isCreating.value = false
      })
}

function submitOpen(): void {
  if (isOpening.value || !form.path) {
    return
  }
  openProjectByPath(form.path.trim())
}

function handleRecentProjectClick(project: ProjectInfo): void {
  if (isOpeningRecent.value === project.path) {
    return
  }
  openProjectByPath(project.path, project)
}

function openProjectByPath(path: string, existingProject?: ProjectInfo): void {
  const isRecent = existingProject !== undefined
  if (isRecent) {
    isOpeningRecent.value = path
  } else {
    isOpening.value = true
  }
  const pendingProject: ProjectInfo = existingProject ?? {
    id: Date.now().toString(),
    name: dataNameFromPath(path),
    path
  }
  startLoadingProject(pendingProject)
  openProject({path})
      .then((data: FileVo) => {
        const project: ProjectInfo = {
          id: pendingProject.id,
          name: data.name,
          path: data.path
        }
        if (!existingProject) {
          recentProjects.value.unshift(project)
        }
        openProjectPage(project)
      })
      .catch(error => {
        console.warn('打开项目失败:', error)
        cancelLoadingProject()
      })
      .finally(() => {
        if (isRecent) {
          isOpeningRecent.value = null
        } else {
          isOpening.value = false
        }
      })
}

/**
 * 从项目路径提取项目名称。
 *
 * @param path 项目路径
 * @returns 项目名称
 */
function dataNameFromPath(path: string): string {
  const normalized = path.replace(/\\/g, '/').replace(/\/$/, '')
  const lastSlash = normalized.lastIndexOf('/')
  return lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized
}
</script>

<style scoped>
.project-page {
  display: flex;
  width: 100%;
  height: 100%;
  background: var(--editor-bg);
  color: var(--editor-text);
}

.sidebar {
  display: flex;
  flex-direction: column;
  width: 260px;
  padding: 32px 24px;
  background: var(--editor-surface);
  border-right: 1px solid var(--editor-border);
  gap: 12px;
}

.brand {
  font-size: 18px;
  font-weight: 600;
  color: var(--editor-text-heading);
  margin-bottom: 24px;
}

.main-area {
  flex: 1;
  padding: 48px 64px;
  overflow: auto;
}

.page-title {
  margin: 0 0 24px;
  font-size: 24px;
  font-weight: 500;
  color: var(--editor-text-heading);
}

.project-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 600px;
}

.project-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 16px;
  background: var(--editor-surface);
  border: 1px solid var(--editor-border);
  border-radius: 6px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}

.project-card:hover {
  background: var(--editor-surface-light);
  border-color: var(--editor-accent);
}

.project-name {
  font-size: 15px;
  color: var(--editor-text-heading);
}

.project-path {
  font-size: 12px;
  color: var(--editor-text-dim);
}

.empty-tip {
  color: var(--editor-text-dim);
  max-width: 600px;
}

.form {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 480px;
}

.form-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-label {
  font-size: 13px;
  color: var(--editor-text-dim);
}

.form-input {
  padding: 8px 10px;
  background: var(--editor-surface);
  border: 1px solid var(--editor-border);
  border-radius: 4px;
  color: var(--editor-text-heading);
  font-size: 14px;
}

.form-input:focus {
  outline: none;
  border-color: var(--editor-accent);
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 8px;
}
</style>
