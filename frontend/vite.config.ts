import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

// Backend base URL — proxied in dev, baked at build time for prod.
const API_TARGET = process.env.VITE_API_TARGET || 'http://localhost:8080'

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['favicon.svg'],
      manifest: {
        name: 'StockCharts — NSE Technical Charting',
        short_name: 'StockCharts',
        description: 'Live NSE charts and technical indicators',
        theme_color: '#0e1117',
        background_color: '#0e1117',
        display: 'standalone',
        orientation: 'portrait',
        icons: [
          { src: 'pwa-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'pwa-512.png', sizes: '512x512', type: 'image/png' },
          { src: 'pwa-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
    }),
  ],
  server: {
    host: true, // listen on 0.0.0.0 so a phone on the LAN can connect
    port: 5173,
    // Hosts allowed to reach the dev server (e.g. when fronted by a reverse proxy).
    // Vite matches these against the Host header, so use the hostname only.
    allowedHosts: ['rocketchat.techfreaks.cloud'],
    proxy: {
      '/api': { target: API_TARGET, changeOrigin: true },
      '/ws': { target: API_TARGET, changeOrigin: true, ws: true },
    },
  },
})
