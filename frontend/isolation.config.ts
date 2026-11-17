import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./isolation',testMatch:'**/*.browser.ts',workers:1,timeout:30000});
