// Opt-in Day 38 driver. All API responses come from the real loopback Spring server.
// Invoke through RecruitmentBrowserMysqlLiveIT; no response routing or mock API is installed.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { pathToFileURL } = require('node:url');

const required = (name) => {
  if (!process.env[name]) throw new Error(`Missing ${name}`);
  return process.env[name];
};
const backend = new URL(required('DAY38_BACKEND_URL'));
if (backend.protocol !== 'http:' || backend.hostname !== '127.0.0.1' || backend.pathname !== '/') {
  throw new Error('Only a loopback Spring server is allowed');
}
const output = path.resolve(required('DAY38_OUTPUT_DIR'));
const password = required('DAY38_PASSWORD');
const applicationId = required('DAY38_APPLICATION_ID');
const { chromium } = require(required('DAY38_PLAYWRIGHT_MODULE'));
const root = path.resolve(__dirname, '..');
const result = { startedAtUtc: new Date().toISOString(), passed: false, checks: [], http: [], responses: [], screenshots: [], console: [] };
const hash = (text) => crypto.createHash('sha256').update(text).digest('hex');
let server, browser, page, base;
let statusPatchCount = 0;
let assistantRequestCount = 0;

function check(name, condition) {
  result.checks.push({ name, passed: Boolean(condition) });
  if (!condition) throw new Error(`Check failed: ${name}`);
}

async function screenshot(name) {
  const file = `${name}.png`;
  await page.screenshot({ path: path.join(output, file), fullPage: false, animations: 'disabled' });
  result.screenshots.push({ file, sha256: hash(fs.readFileSync(path.join(output, file))), viewport: page.viewportSize() });
}

async function health(label) {
  check(`${label}: page identity`, page.url().startsWith(base) && (await page.title()).includes('AI'));
  check(`${label}: meaningful content`, (await page.locator('body').innerText()).length > 100);
  check(`${label}: no framework overlay`, await page.locator('vite-error-overlay').count() === 0);
  check(`${label}: no horizontal overflow`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
}

function consoleHealth(label) {
  // Vite serves no optional favicon in this checkout. Keep that exact finding visible;
  // never waive an application/API failure or a console message without a resource URL.
  const known = result.console.filter((entry) => entry.type === 'error'
    && entry.message === 'Failed to load resource: the server responded with a status of 404 (Not Found)'
    && entry.location?.url === `${base}/favicon.ico`);
  result.knownConsoleFindings = known;
  check(`${label}: no application runtime errors or warnings`, result.console.length === known.length);
}

async function login(role) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
  page = await context.newPage();
  page.setDefaultTimeout(15000);
  page.on('pageerror', (error) => result.console.push({ type: 'pageerror', message: error.message }));
  page.on('console', (message) => {
    if (['warning', 'error'].includes(message.type())) result.console.push({ type: message.type(), message: message.text(), location: message.location() });
  });
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (request.method() === 'PATCH' && /\/applications\/\d+\/status$/.test(url.pathname)) statusPatchCount++;
    if (url.pathname.endsWith('/assistant/chat') && request.method() === 'POST') assistantRequestCount++;
  });
  page.on('response', (response) => {
    const url = new URL(response.url());
    if (url.pathname.startsWith('/api/')) {
      result.http.push({ method: response.request().method(), path: url.pathname, status: response.status() });
    }
  });
  await page.goto(`${base}/app`);
  await page.waitForURL(/\/login(?:\?|$)/);
  check(`${role}: unauthenticated route guard`, page.url().includes('/login'));
  await page.getByRole('button', { name: '登录工作台' }).click();
  await page.locator('.el-form-item__error').first().waitFor();
  check(`${role}: empty login validation`, await page.locator('.el-form-item__error').count() === 2);
  await page.getByPlaceholder('请输入用户名或邮箱').fill(required(`DAY38_${role}_ACCOUNT`));
  await page.getByPlaceholder('请输入密码', { exact: true }).fill(password);
  const loggedIn = page.waitForResponse((response) => response.url().endsWith('/api/auth/login'));
  await page.getByRole('button', { name: '登录工作台' }).click();
  check(`${role}: real login HTTP 200`, (await loggedIn).status() === 200);
  await page.waitForURL(role === 'HR' ? /\/hr$/ : /\/app$/);
  await page.waitForLoadState('networkidle');
  await page.locator('.workspace-nav-item').filter({ hasText: role === 'HR' ? '招聘助手' : '求职助手' }).click();
  await page.getByRole('log', { name: '当前对话' }).waitFor();
  check(`${role}: initially empty transcript`, await page.locator('.assistant-message').count() === 0);
  check(`${role}: empty draft disabled`, await page.getByRole('button', { name: '发送', exact: true }).isDisabled());
  await health(`${role} desktop`);
  await page.locator('.assistant-context .el-radio-button').filter({ hasText: '投递' }).click();
  check(`${role}: application context selected`, await page.getByRole('radio', { name: '投递', exact: true }).isChecked());
  await page.getByRole('textbox', { name: '助手消息' }).fill('SYNTHETIC_DAY38 unsent draft');
  check(`${role}: missing context disabled`, await page.getByRole('button', { name: '发送', exact: true }).isDisabled());
  await page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: '助手投递' }) }).click();
  await page.getByRole('option', { name: new RegExp(`^投递 ${applicationId} /`) }).click();
  check(`${role}: context switch clears draft`, await page.getByRole('textbox', { name: '助手消息' }).inputValue() === '');
  return context;
}

async function chat(message, role, previousConversation, expectedHistory) {
  check('bounded browser model request count', assistantRequestCount < 3);
  await page.getByRole('textbox', { name: '助手消息' }).fill(message);
  await page.evaluate(() => {
    window.__day38PendingState = null;
    const transcript = document.querySelector('[aria-label="当前对话"]');
    const observer = new MutationObserver(() => {
      if (transcript.getAttribute('aria-busy') !== 'true') return;
      window.__day38PendingState = {
        inputDisabled: document.querySelector('[aria-label="助手消息"]').disabled,
        newDisabled: [...document.querySelectorAll('.assistant-heading button')].some((button) => button.disabled),
      };
      observer.disconnect();
    });
    observer.observe(transcript, { attributes: true, attributeFilter: ['aria-busy'] });
  });
  const responsePromise = page.waitForResponse((response) => response.url().endsWith(`/${role.toLowerCase()}/assistant/chat`), { timeout: 45000 });
  await page.getByRole('button', { name: '发送', exact: true }).click();
  const response = await responsePromise;
  const pending = await page.evaluate(() => window.__day38PendingState);
  check(`${role}: pending transcript observed`, pending !== null);
  check(`${role}: input locked while pending`, pending.inputDisabled);
  check(`${role}: new conversation locked while pending`, pending.newDisabled);
  check(`${role}: chat HTTP 200`, response.status() === 200);
  const body = await response.json();
  const data = body.data;
  check(`${role}: generated response, no fallback`, body.success && data.generated === true && data.modelStatus === 'GENERATED');
  check(`${role}: server role and human review`, data.role === role && data.humanReviewRequired === true);
  check(`${role}: rule reference unchanged`, Number(data.readOnlyMatchScore) === 75 && data.matchScoreSource === 'ai-match-result');
  check(`${role}: application remains submitted`, data.contextSummary.includes('SUBMITTED'));
  check(`${role}: expected history count`, data.historyMessageCount === expectedHistory);
  if (previousConversation) check('student continuation retains conversation', previousConversation === data.conversationId);
  const lastMessage = page.locator('.assistant-message.assistant p').last();
  await lastMessage.waitFor();
  await page.waitForFunction((expected) => [...document.querySelectorAll('.assistant-message.assistant p')].at(-1)?.textContent === expected, data.assistantMessage);
  const rendered = await lastMessage.textContent();
  check(`${role}: rendered response exactly matches real HTTP`, rendered === data.assistantMessage);
  check(`${role}: model label visible`, await page.locator('.assistant-message.assistant header').last().innerText().then((text) => text.includes('模型辅助建议')));
  check(`${role}: readonly score visible`, await page.locator('[aria-label="规则匹配参考"]').innerText().then((text) => text.includes('只读') && text.includes('75')));
  result.responses.push({ role, conversationId: data.conversationId, historyMessageCount: data.historyMessageCount, readOnlyMatchScore: data.readOnlyMatchScore,
    modelName: data.modelName, modelStatus: data.modelStatus, generated: data.generated, humanReviewRequired: data.humanReviewRequired,
    assistantMessageSha256: hash(rendered), renderedLength: rendered.length });
  return data.conversationId;
}

async function cancelHumanReview(phase) {
  await page.getByRole('button', { name: `人工复核投递 ${applicationId}`, exact: true }).click();
  const statusSelect = page.getByRole('combobox', { name: new RegExp(`^变更投递 ${applicationId} 的状态`) });
  await page.locator('.el-select').filter({ has: statusSelect }).click();
  await page.getByRole('option', { name: '筛选中', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '人工复核投递状态' });
  await dialog.waitFor();
  check('human confirmation required before PATCH', statusPatchCount === 0);
  await screenshot(`${phase}-hr-confirmation`);
  await dialog.getByRole('button', { name: '取消', exact: true }).click();
  await dialog.waitFor({ state: 'hidden' });
  check('cancel sends no status PATCH', statusPatchCount === 0);
  check('cancel retains submitted status', (await statusSelect.getAttribute('aria-label')).includes('已投递'));
  await page.locator('.workspace-nav-item').filter({ hasText: '招聘助手' }).click();
}

(async () => {
  fs.mkdirSync(output, { recursive: true });
  try {
    const { createServer } = await import(pathToFileURL(path.join(root, 'node_modules/vite/dist/node/index.js')).href);
    const { default: vue } = await import(pathToFileURL(path.join(root, 'node_modules/@vitejs/plugin-vue/dist/index.mjs')).href);
    server = await createServer({ configFile: false, root, plugins: [vue()], server: {
      host: '127.0.0.1', port: 0, proxy: { '/api': { target: backend.origin, changeOrigin: true } },
    } });
    await server.listen();
    base = `http://127.0.0.1:${server.httpServer.address().port}`;
    result.frontendUrl = base;
    result.backendUrl = backend.origin;
    browser = await chromium.launch({ ...(process.env.DAY38_BROWSER_EXECUTABLE ? { executablePath: process.env.DAY38_BROWSER_EXECUTABLE } : {}), headless: true });
    result.browserVersion = browser.version();
    const hr = await login('HR');
    const hrPage = page;
    await cancelHumanReview('before-chat');
    const student = await login('STUDENT');
    consoleHealth('before model calls');
    const conversation = await chat('SYNTHETIC_DAY38 student: Give one short Redis evidence-check question. Do not score or decide hiring.', 'STUDENT', null, 2);
    await chat('SYNTHETIC_DAY38 follow-up: Use the previous question and give one short next step for human review.', 'STUDENT', conversation, 4);
    await screenshot('student-desktop');
    await page.setViewportSize({ width: 390, height: 844 });
    await health('STUDENT mobile');
    await page.locator('.assistant-composer').scrollIntoViewIfNeeded();
    await screenshot('student-mobile');
    await page.getByRole('button', { name: '新对话', exact: true }).click();
    check('new conversation clears student transcript', await page.locator('.assistant-message').count() === 0);
    await student.close();
    page = hrPage;
    const hrConversation = await chat('SYNTHETIC_DAY38 HR: Give one short verification question about the Redis gap. A human decides hiring.', 'HR', null, 2);
    check('HR conversation isolated from student', hrConversation !== conversation);
    await screenshot('hr-desktop');
    await cancelHumanReview('after-chat');
    check('exactly three browser chat requests', assistantRequestCount === 3);
    check('all browser API responses successful', result.http.every((entry) => entry.status >= 200 && entry.status < 300));
    consoleHealth('after model calls');
    result.statusPatchCount = statusPatchCount;
    result.assistantRequestCount = assistantRequestCount;
    result.passed = true;
    await hr.close();
  } catch (error) {
    result.failure = error.message.replaceAll(password, '[REDACTED]');
    if (page && !page.isClosed()) await screenshot('failure').catch(() => {});
    process.exitCode = 1;
  } finally {
    if (browser) await browser.close();
    if (server) await server.close();
    result.completedAtUtc = new Date().toISOString();
    const serialized = JSON.stringify(result, null, 2);
    if (serialized.includes(password)) throw new Error('Refusing to persist synthetic login password');
    fs.writeFileSync(path.join(output, 'browser-results.json'), serialized, { flag: 'wx' });
    console.log(`DAY38_BROWSER passed=${result.passed} checks=${result.checks.length}`);
  }
})();
