import { useEffect, useRef, useState } from 'react'
import { getBackendUrl, getAuthToken, isAuthenticated } from './api'

export function useSSE() {
  const [events, setEvents] = useState([])
  const [lastStateChange, setLastStateChange] = useState(null)
  const [lastPresenceChange, setLastPresenceChange] = useState(null)
  const [isConnected, setIsConnected] = useState(false)
  
  const eventSourceRef = useRef(null)
  const reconnectTimeoutRef = useRef(null)

  useEffect(() => {
    if (!isAuthenticated()) return

    const connectSSE = () => {
      const token = getAuthToken()
      if (!token) return

      const url = `${getBackendUrl()}/api/stream?token=${token}`
      const es = new EventSource(url)
      eventSourceRef.current = es

      es.onopen = () => {
        console.log('[SSE] Connection opened')
        setIsConnected(true)
      }

      es.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data)
          
          if (data.type === 'PING') return

          // Append to event log (max 50)
          setEvents(prev => [data, ...prev].slice(0, 50))

          if (data.type === 'DEVICE_STATE_CHANGED') {
            setLastStateChange(data)
          } else if (data.type === 'DEVICE_PRESENCE_CHANGED') {
            setLastPresenceChange(data)
          }

        } catch (err) {
          console.error('[SSE] Failed to parse event', err)
        }
      }

      es.onerror = (error) => {
        console.error('[SSE] Connection error', error)
        setIsConnected(false)
        es.close()
        
        // Exponential backoff or simple fixed backoff
        clearTimeout(reconnectTimeoutRef.current)
        reconnectTimeoutRef.current = setTimeout(() => {
          console.log('[SSE] Attempting reconnection...')
          connectSSE()
        }, 3000)
      }
    }

    connectSSE()

    return () => {
      clearTimeout(reconnectTimeoutRef.current)
      if (eventSourceRef.current) {
        eventSourceRef.current.close()
        console.log('[SSE] Connection closed on unmount')
      }
    }
  }, [])

  return { events, lastStateChange, lastPresenceChange, isConnected }
}
