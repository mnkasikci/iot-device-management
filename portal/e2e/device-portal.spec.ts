import { ChildProcess, spawn } from 'node:child_process';
import { expect, test } from '@playwright/test';

// Two simulated devices: e2e-00001 is a thermostat, e2e-00002 a relay (types round-robin).
let simulator: ChildProcess;

test.beforeAll(() => {
  simulator = spawn(
    'java',
    ['-jar', '../simulator/target/simulator.jar', '--count', '2', '--interval', '1', '--prefix', 'e2e'],
    { stdio: 'ignore' },
  );
});

test.afterAll(() => {
  simulator?.kill();
});

test('a simulated device shows up live and a command reaches ACKED', async ({ page }) => {
  await page.goto('/devices');
  await page.getByPlaceholder('Search id…').fill('e2e-');

  const link = page.getByRole('link', { name: 'e2e-00002' });
  await expect(link).toBeVisible({ timeout: 30_000 });
  await link.click();

  await expect(page.getByRole('heading', { name: 'e2e-00002' })).toBeVisible();
  await expect(page.locator('.detail-header .badge')).toHaveText('online');

  await page.getByRole('button', { name: 'Send setRelay' }).click();
  const newest = page.locator('app-device-detail tbody tr').first();
  await expect(newest).toContainText('setRelay');
  await expect(newest).toContainText('ACKED', { timeout: 10_000 }); // arrives via SSE
});

test('an invalid value comes back as FAILED with the device message', async ({ page }) => {
  await page.goto('/devices/e2e-00001');
  await expect(page.locator('.detail-header .badge')).toHaveText('online', { timeout: 30_000 });

  await page.getByLabel('Target temperature °C').fill('99');
  await page.getByRole('button', { name: 'Send setTargetTemp' }).click();

  const newest = page.locator('app-device-detail tbody tr').first();
  await expect(newest).toContainText('FAILED', { timeout: 10_000 });
  await expect(newest).toContainText('between 5 and 30');
});
