import { defineConfig } from '@playwright/test';

/**
 * End-to-end test of the whole system: backend (dev mode, Postgres via Dev Services → needs
 * Docker and, with Colima, the env vars from CLAUDE.md), portal dev server, and a few simulated
 * devices started by the test itself. Servers already running on 8080/4200 are reused.
 *
 *   cd simulator && ./mvnw -q package     # once, the test runs simulator/target/simulator.jar
 *   cd portal && npm run e2e
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:4200',
    // The installed Google Chrome — no extra browser download.
    // Without Chrome: `npx playwright install chromium` and remove this line.
    channel: 'chrome',
  },
  webServer: [
    {
      command: 'cd ../backend && ./mvnw quarkus:dev -Dquarkus.console.enabled=false',
      url: 'http://localhost:8080/q/health/ready',
      timeout: 180_000,
      reuseExistingServer: true,
    },
    {
      command: 'npm start',
      url: 'http://localhost:4200',
      timeout: 120_000,
      reuseExistingServer: true,
    },
  ],
});
