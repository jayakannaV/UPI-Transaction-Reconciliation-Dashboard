import { apiFetch } from './client';

export interface ConnectionDto {
  connectionId: string;
  gateway: string;
  status: string; // "ACTIVE" | "DISCONNECTED"
  connectedAt: string | null;
  disconnectedAt: string | null;
}

export interface ConnectionCreateResponse {
  connectionId: string;
  webhookUrl: string;
  webhookSecret: string;
}

export interface MerchantConnectRequest {
  gateway: string;
  apiKey: string;
  apiSecret: string;
  name?: string;
}

export function getConnections(): Promise<ConnectionDto[]> {
  return apiFetch<ConnectionDto[]>('/connections');
}

export function createConnection(
  request: MerchantConnectRequest
): Promise<ConnectionCreateResponse> {
  return apiFetch<ConnectionCreateResponse>('/connections', {
    method: 'POST',
    body: JSON.stringify(request),
  });
}

export function disconnectConnection(id: string): Promise<void> {
  return apiFetch<void>(`/connections/${id}`, {
    method: 'DELETE',
  });
}

export function reconnectConnection(
  id: string,
  request: MerchantConnectRequest
): Promise<ConnectionCreateResponse> {
  return apiFetch<ConnectionCreateResponse>(`/connections/${id}/reconnect`, {
    method: 'POST',
    body: JSON.stringify(request),
  });
}
