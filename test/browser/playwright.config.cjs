const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: '.',
  testMatch: '*.spec.cjs',
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  timeout: 30000,
  use: { browserName: 'chromium', trace: 'retain-on-failure' },
});
