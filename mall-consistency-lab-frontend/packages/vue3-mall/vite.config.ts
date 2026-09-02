import vue from '@vitejs/plugin-vue';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  return {
    plugins: [vue()],
    server: {
      port: 3001,
      proxy: { '/api': { target: env.VITE_API_BASE ?? 'http://localhost:8080', changeOrigin: true } },
      watch: {
        // 忽略编辑器/工具原子写入产生的临时文件，避免 Windows 下 EBUSY 崩溃
        ignored: ['**/*.tmp', '**/*tmpdir/**']
      }
    }
  };
});
