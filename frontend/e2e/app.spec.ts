import { expect, test } from '@playwright/test'
import { mockApi } from './mockApi'

async function pickSymbol(page: import('@playwright/test').Page, symbol: string) {
  await page.getByLabel('Search symbols').first().fill(symbol)
  await page.getByText(new RegExp(`^${symbol} — `)).first().click()
}

test('chart loads a symbol, switches chart type and saves the layout', async ({ page }) => {
  const api = await mockApi(page)
  await page.goto('/')
  await pickSymbol(page, 'RELIANCE')
  await expect(page.locator('.symbol-label strong')).toHaveText('RELIANCE')
  await expect(page.getByTestId('chart-main').locator('canvas').first()).toBeVisible()

  await page.getByLabel('Chart type').selectOption('heikin')
  await page.getByRole('button', { name: '5m', exact: true }).click()
  await expect.poll(() => (api.uiState.layout as any)?.chartType, { timeout: 5000 }).toBe('heikin')
  expect((api.uiState.layout as any).symbol).toBe('RELIANCE')
  expect((api.uiState.layout as any).timeframe).toBe('5m')
})

test('saved layout is restored on load', async ({ page }) => {
  await mockApi(page, {
    uiState: { layout: { symbol: 'TCS', timeframe: '1h', indicators: [], chartType: 'line', watchlist: 'Core' } },
  })
  await page.goto('/')
  await expect(page.locator('.symbol-label strong')).toHaveText('TCS')
  await expect(page.getByLabel('Chart type')).toHaveValue('line')
  await expect(page.getByRole('button', { name: '1h', exact: true })).toHaveClass(/active/)
  // Watchlist panel opened with quotes and an index badge.
  await expect(page.locator('.wl-table')).toContainText('NIFTY50')
  await expect(page.locator('.wl-table .badge')).toHaveText('idx')
  await expect(page.locator('.wl-table')).toContainText('+10.00%')
})

test('horizontal line drawing is saved per symbol', async ({ page }) => {
  const api = await mockApi(page)
  await page.goto('/')
  await pickSymbol(page, 'RELIANCE')
  await page.getByRole('button', { name: 'Horizontal line (1 click)' }).click()
  const box = (await page.getByTestId('chart-main').boundingBox())!
  await page.mouse.click(box.x + box.width * 0.4, box.y + box.height * 0.4)
  await expect.poll(() => (api.uiState['drawings:RELIANCE'] as any)?.items?.length ?? 0).toBe(1)
  expect((api.uiState['drawings:RELIANCE'] as any).items[0].type).toBe('hline')
  await expect(page.getByRole('button', { name: 'Undo last drawing' })).toBeEnabled()
})

test('price alert template compiles to a crossover expression', async ({ page }) => {
  const api = await mockApi(page)
  await page.goto('/')
  await pickSymbol(page, 'RELIANCE')
  await page.getByRole('button', { name: '🔔 Alert' }).click()
  await page.getByLabel('Price level').fill('3000')
  await expect(page.locator('.expr-preview')).toHaveText('crossover(close, 3000)')
  await page.getByRole('button', { name: 'Create alert' }).click()
  await expect(page.getByText('is active')).toBeVisible()
  const post = api.requests.find((r) => r.method === 'POST' && r.path === '/alerts')!
  expect(post.body).toMatchObject({ symbol: 'RELIANCE', expression: 'crossover(close, 3000)', triggerMode: 'ONCE' })
  await expect(page.locator('.results-table')).toContainText('RELIANCE: crossover(close, 3000)')
})

test('backtest shows metrics and can plot trades on the chart', async ({ page }) => {
  const api = await mockApi(page)
  await page.goto('/')
  await pickSymbol(page, 'RELIANCE')
  await page.getByRole('button', { name: 'Backtest', exact: true }).click()
  await page.getByRole('button', { name: 'Run backtest' }).click()
  await expect(page.locator('.tile').filter({ hasText: 'Net profit' })).toContainText('₹12,345.00')
  await expect(page.locator('.tile').filter({ hasText: 'Win rate' })).toContainText('66.7%')
  const req = api.requests.find((r) => r.path === '/backtest')!
  expect(req.body).toMatchObject({ symbol: 'RELIANCE', timeframe: '1d', direction: 'LONG', stopLossPct: 5 })
  await page.getByRole('button', { name: 'Show trades on chart' }).click()
  await expect(page.locator('.symbol-label')).toContainText('1d')
})

test('backtest scan ranks a universe and opens a symbol from the results', async ({ page }) => {
  const api = await mockApi(page)
  await page.goto('/')
  await page.getByRole('button', { name: 'Backtest', exact: true }).click()
  await page.getByRole('tab', { name: 'Scan universe' }).click()
  await page.getByLabel('Rank by').selectOption('PROFIT_FACTOR')
  await page.getByText('Only symbols with an entry/exit signal').click()
  await page.getByRole('button', { name: 'Run scan' }).click()

  const req = api.requests.find((r) => r.path === '/backtest/scan')!
  expect(req.body).toMatchObject({
    universe: { type: 'index', value: 'NIFTY50' }, sortBy: 'PROFIT_FACTOR', onlySignals: true, minTrades: 3,
    strategy: { timeframe: '1d', direction: 'LONG', stopLossPct: 5 },
  })
  expect(req.body.strategy.symbol).toBeUndefined()

  const rows = page.locator('.scan-table tbody tr')
  await expect(rows).toHaveCount(2)
  await expect(rows.first()).toContainText('Buy at next open')
  await expect(rows.nth(1)).toContainText('Holding +2.50%')
  await expect(page.getByText('2 of 3 symbols')).toBeVisible()

  await rows.first().click()
  await expect(page.getByRole('tab', { name: 'Single symbol' })).toHaveAttribute('aria-selected', 'true')
  const single = api.requests.filter((r) => r.path === '/backtest').pop()!
  expect(single.body.symbol).toBe('TCS')
  await expect(page.locator('.card-head h3')).toContainText('Holding +6.67%')
})

test('portfolio report shows P&L, tax table and a CSV link', async ({ page }) => {
  await mockApi(page)
  await page.goto('/')
  await page.getByRole('button', { name: 'Portfolio', exact: true }).click()
  await expect(page.locator('.tile').filter({ hasText: 'Net P&L' })).toContainText('₹480.00')
  await expect(page.getByText('FY2026-27')).toBeVisible()
  await expect(page.getByRole('link', { name: 'Download CSV' })).toHaveAttribute('href', '/api/v1/portfolio/report.csv')
})

test('mobile layout has no horizontal overflow @mobile', async ({ page }) => {
  await mockApi(page, { uiState: { layout: { symbol: 'RELIANCE', timeframe: '1d', indicators: [], chartType: 'candles', watchlist: 'Core' } } })
  await page.goto('/')
  await expect(page.locator('.symbol-label strong')).toHaveText('RELIANCE')
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow).toBeLessThanOrEqual(0)
})
