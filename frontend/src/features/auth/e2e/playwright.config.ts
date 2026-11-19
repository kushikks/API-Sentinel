import { defineConfig } from '@playwright/test'
import { fileURLToPath } from 'node:url'

const frontend = fileURLToPath(new URL('../../../../', import.meta.url))
const evidence = fileURLToPath(new URL('../../../../../.local/evidence/', import.meta.url))

export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.ts',
  outputDir: evidence + 'playwright/',
  timeout: 70_000,
  expect: { timeout: 20_000 },
  workers: 1,
  reporter: [
    ['list'],
    ['junit', { outputFile: evidence + 'playwright.xml' }],
  ],
  use: {
    baseURL: 'http://127.0.0.1:5173',
    channel: process.env.SENTINEL_BROWSER ?? 'msedge',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npm run dev',
    cwd: frontend,
    url: 'http://127.0.0.1:5173/sign-in',
    reuseExistingServer: true,
    timeout: 25_000,
  },
})
