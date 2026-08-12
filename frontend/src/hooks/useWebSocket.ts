import { useEffect, useRef, useState, useCallback } from 'react';
import { Client } from '@stomp/stompjs';
import {
  createWebSocketClient,
  type ConnectionStatus,
} from '../api/websocket';
import type {
  WebSocketMessage,
  LiveFeedMessage,
  AnomalyMessage,
  ProvisionalRefundRecoveredMessage,
} from '../api/types';
import { isAnomalyMessage, isRecoveryMessage } from '../api/types';
import { useAuth } from '../contexts/AuthContext';

export interface UseWebSocketReturn {
  status: ConnectionStatus;
  liveMessages: LiveFeedMessage[];
  anomalies: AnomalyMessage[];
  dismissAnomaly: (index: number) => void;
  recoveryEvents: ProvisionalRefundRecoveredMessage[];
  dismissRecovery: (index: number) => void;
}

/**
 * Manages the STOMP-over-SockJS WebSocket lifecycle.
 * Buffers the most recent live-feed messages and anomaly alerts.
 */
export function useWebSocket(maxMessages = 100): UseWebSocketReturn {
  const [status, setStatus] = useState<ConnectionStatus>('disconnected');
  const [liveMessages, setLiveMessages] = useState<LiveFeedMessage[]>([]);
  const [anomalies, setAnomalies] = useState<AnomalyMessage[]>([]);
  const [recoveryEvents, setRecoveryEvents] = useState<ProvisionalRefundRecoveredMessage[]>([]);
  const clientRef = useRef<Client | null>(null);
  const { token } = useAuth();

  useEffect(() => {
    if (!token) {
      setStatus('disconnected');
      return;
    }

    const client = createWebSocketClient({
      onStatusChange: setStatus,
      onMessage: (msg: WebSocketMessage) => {
        if (isAnomalyMessage(msg)) {
          setAnomalies((prev) => [msg, ...prev]);
        } else if (isRecoveryMessage(msg)) {
          setRecoveryEvents((prev) => [msg, ...prev]);
        } else {
          setLiveMessages((prev) => {
            const next = [msg, ...prev];
            return next.slice(0, maxMessages);
          });
        }
      },
    });
    clientRef.current = client;

    return () => {
      client.deactivate();
    };
  }, [maxMessages, token]);


  const dismissAnomaly = useCallback((index: number) => {
    setAnomalies((prev) => prev.filter((_, i) => i !== index));
  }, []);

  const dismissRecovery = useCallback((index: number) => {
    setRecoveryEvents((prev) => prev.filter((_, i) => i !== index));
  }, []);

  return { status, liveMessages, anomalies, dismissAnomaly, recoveryEvents, dismissRecovery };
}
