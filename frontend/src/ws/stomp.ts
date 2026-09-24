import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import type { AlertEvent, LiveUpdate } from '../api/types'

/**
 * Single shared STOMP connection (over SockJS for mobile-network resilience).
 * Subscribe per symbol to /topic/ticks/{symbol}.
 */
let client: Client | null = null
let connected = false
const pending: Array<() => void> = []

function ensureClient(): Client {
  if (client) return client
  client = new Client({
    // SockJS gives http(s) fallback transports for restrictive networks.
    webSocketFactory: () => new SockJS('/ws') as unknown as WebSocket,
    reconnectDelay: 3000,
    onConnect: () => {
      connected = true
      pending.splice(0).forEach((fn) => fn())
    },
    onWebSocketClose: () => {
      connected = false
    },
  })
  client.activate()
  return client
}

/** Subscribe to a STOMP topic; returns an unsubscribe function (safe to call before connect). */
export function subscribeTopic<T>(destination: string, onMessage: (m: T) => void): () => void {
  const c = ensureClient()
  let sub: StompSubscription | null = null
  let cancelled = false

  const doSubscribe = () => {
    if (cancelled) return
    sub = c.subscribe(destination, (msg: IMessage) => {
      try {
        onMessage(JSON.parse(msg.body) as T)
      } catch {
        /* ignore malformed frames */
      }
    })
  }

  if (connected) doSubscribe()
  else pending.push(doSubscribe)

  return () => {
    cancelled = true
    if (sub) sub.unsubscribe()
  }
}

/** Live candle updates for one symbol. Subscribing also asks the backend to stream it. */
export function subscribeTicks(symbol: string, onTick: (u: LiveUpdate) => void): () => void {
  return subscribeTopic<LiveUpdate>(`/topic/ticks/${symbol.toUpperCase()}`, onTick)
}

/** Fired alerts, broadcast to every connected client. */
export function subscribeAlerts(onAlert: (e: AlertEvent) => void): () => void {
  return subscribeTopic<AlertEvent>('/topic/alerts', onAlert)
}
