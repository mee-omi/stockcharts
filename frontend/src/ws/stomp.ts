import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import type { LiveUpdate } from '../api/types'

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

export function subscribeTicks(
  symbol: string,
  onTick: (u: LiveUpdate) => void,
): () => void {
  const c = ensureClient()
  let sub: StompSubscription | null = null

  const doSubscribe = () => {
    sub = c.subscribe(`/topic/ticks/${symbol.toUpperCase()}`, (msg: IMessage) => {
      try {
        onTick(JSON.parse(msg.body) as LiveUpdate)
      } catch {
        /* ignore malformed frames */
      }
    })
  }

  if (connected) doSubscribe()
  else pending.push(doSubscribe)

  return () => {
    if (sub) sub.unsubscribe()
  }
}
