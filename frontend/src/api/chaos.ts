import { apiFetch } from './client';
import type { TransactionDto } from './types';

export function seedDemoData(): Promise<{ message: string; transactions: TransactionDto[] }> {
  return apiFetch('/chaos/seed-demo-data');
}

export function fireDuplicate(txnId: string): Promise<any> {
  return apiFetch(`/chaos/fire-duplicate/${txnId}`, {
    method: 'POST',
  });
}

export function forceBreach(txnId: string): Promise<TransactionDto> {
  return apiFetch(`/chaos/force-breach/${txnId}`, {
    method: 'POST',
  });
}

export function createDeemedApproved(): Promise<TransactionDto> {
  return apiFetch('/chaos/create-deemed-approved', {
    method: 'POST',
  });
}

export function triggerAnomaly(bankId: string): Promise<{ message: string; bankId: string; transactions: TransactionDto[] }> {
  return apiFetch(`/chaos/trigger-anomaly/${bankId}`, {
    method: 'POST',
  });
}

export function simulateRazorpayPayment(): Promise<any> {
  return apiFetch('/chaos/simulate-razorpay-payment', {
    method: 'POST',
  });
}
