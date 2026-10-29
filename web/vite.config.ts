import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In dev the SPA proxies /api to the API container; in docker compose nginx does the same.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': process.env.API_URL ?? 'http://localhost:8082' },
  },
})
