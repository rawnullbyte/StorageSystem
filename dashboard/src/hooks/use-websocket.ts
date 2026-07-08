import { useEffect, useRef, useState } from "react";

const WS_URL = import.meta.env.VITE_WS_URL || "ws://localhost:8000/ws";

export type WsStatus = "connected" | "disconnected" | "connecting";

export interface WsEventBagAdded {
  type: "BagAdded";
  payload: {
    container_id: string;
    lcsc_part_number: string;
    quantity: number;
  };
}

export interface WsEventQuantityUpdated {
  type: "QuantityUpdated";
  payload: {
    container_id: string;
    lcsc_part_number: string;
    new_quantity: number;
  };
}

export type WsEventMessage = WsEventBagAdded | WsEventQuantityUpdated;

/**
 * Hook that connects to the backend WebSocket and calls `onEvent` for each
 * broadcast event. Automatically reconnects on disconnect.
 */
export function useWebSocket(onEvent: (event: WsEventMessage) => void) {
  const [status, setStatus] = useState<WsStatus>("disconnected");
  const wsRef = useRef<WebSocket | null>(null);
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;

  useEffect(() => {
    let reconnectTimer: ReturnType<typeof setTimeout>;
    let stopped = false;

    function connect() {
      if (stopped) return;
      setStatus("connecting");

      try {
        const ws = new WebSocket(WS_URL);
        wsRef.current = ws;

        ws.onopen = () => setStatus("connected");

        ws.onmessage = (msg) => {
          try {
            const data = JSON.parse(msg.data) as WsEventMessage;
            onEventRef.current(data);
          } catch {
            // Ignore malformed messages
          }
        };

        ws.onclose = () => {
          wsRef.current = null;
          if (!stopped) {
            setStatus("disconnected");
            // Reconnect after 3 seconds
            reconnectTimer = setTimeout(connect, 3000);
          }
        };

        ws.onerror = () => {
          ws.close();
        };
      } catch {
        // WebSocket constructor failed
        if (!stopped) {
          reconnectTimer = setTimeout(connect, 3000);
        }
      }
    }

    connect();

    return () => {
      stopped = true;
      clearTimeout(reconnectTimer);
      wsRef.current?.close();
    };
  }, []);

  return status;
}
