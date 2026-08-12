// ── TypeScript types matching backend DTOs exactly ──

export interface TransactionDto {
  txnId: string;
  state: string;
  amountInr: number;
  penaltyAmountInr: number;
  remitterBankId: string | null;
  remitterBankName: string | null;
  beneficiaryBankId: string | null;
  beneficiaryBankName: string | null;
  createdAt: string;
  tatDeadline: string | null;
  penaltyStartAt: string | null;
  resolvedAt: string | null;
  declineCode: string | null;
  orderReference: string | null;
  mlClassification: string | null;
  mlConfidence: number | null;
  sourceGateway: string | null;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number; // current page (0-indexed)
  size: number;
  first: boolean;
  last: boolean;
}

export interface StateTransitionDto {
  fromState: string | null;
  toState: string;
  transitionedAt: string;
  reason: string | null;
}

export interface BankScorecardDto {
  bankId: string;
  bankName: string;
  historicalBdRate: number;
  historicalTdRate: number;
  historicalDeemedApprovedRate: number;
  liveStateCounts: Record<string, number>;
  totalTransactions: number;
  liveReliabilityScore: number;
}

/** Response from POST /api/transactions/{txnId}/provisional-refund */
export interface ProvisionalRefundDto {
  id: number;
  txnId: string;
  amountRefundedByMerchant: number;
  refundedAt: string;
  recoveryStatus: string;
}

/** Response from GET /api/merchants/provisional-summary */
export interface ProvisionalSummaryDto {
  total_pending_recovery: number;
  count: number;
}

/** WebSocket: state transition message */
export interface LiveFeedMessage {
  txnId: string;
  oldState: string | null;
  newState: string;
  penaltyAmountInr: number;
  bankId: string;
  timestamp: string;
}

/** WebSocket: anomaly message */
export interface AnomalyMessage {
  eventType: 'ANOMALY_FLAGGED';
  bankId: string;
  bankName: string;
  failureRateNow: number;
  historicalBaseline: number;
  affectedCount: number;
  timestamp: string;
}

/** WebSocket: provisional refund recovered event */
export interface ProvisionalRefundRecoveredMessage {
  eventType: 'PROVISIONAL_REFUND_RECOVERED';
  provisionalRefundId: number;
  txnId: string;
  amountRecovered: number;
  timestamp: string;
}

export type WebSocketMessage = LiveFeedMessage | AnomalyMessage | ProvisionalRefundRecoveredMessage;

export function isAnomalyMessage(msg: WebSocketMessage): msg is AnomalyMessage {
  return 'eventType' in msg && msg.eventType === 'ANOMALY_FLAGGED';
}

export function isRecoveryMessage(msg: WebSocketMessage): msg is ProvisionalRefundRecoveredMessage {
  return 'eventType' in msg && msg.eventType === 'PROVISIONAL_REFUND_RECOVERED';
}
