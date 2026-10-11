import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  build: {
    rolldownOptions: {
      input: {
        main: resolve(__dirname, 'page/dialog.html'),
        dialog: resolve(__dirname, 'page/main.html'),
        pack: resolve(__dirname, 'page/pack.html'),
        projectLoading: resolve(__dirname, 'page/projectLoading.html'),
        projectManager: resolve(__dirname, 'page/projectManager.html'),
      }
    }
  }
})
