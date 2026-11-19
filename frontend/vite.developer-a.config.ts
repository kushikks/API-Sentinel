import { defineConfig } from 'vite'

const host = '127.0.0.1'
const proxy = {
  '/auth': {
    target: process.env.IDENTITY_ORIGIN ?? 'http://127.0.0.1:8081',
  },
  '/apis': {
    target: process.env.REGISTRY_ORIGIN ?? 'http://127.0.0.1:8082',
  },
  '/analyses': {
    target: process.env.ANALYZER_ORIGIN ?? 'http://127.0.0.1:8083',
  },
}

export default defineConfig({
  esbuild: { jsx: 'automatic' },
  server: { host, port: 5173, strictPort: true, proxy },
  preview: { host, port: 4173, strictPort: true, proxy },
})
