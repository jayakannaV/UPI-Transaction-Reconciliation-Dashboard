import { useEffect, useState } from 'react';
import type { TransactionDto, StateTransitionDto } from '../api/types';
import { getTransactions, getTransactionHistory, generateComplaint, createProvisionalRefund } from '../api/transactions';
import { StateBadge } from './StateBadge';
import { ComplaintModal } from './ComplaintModal';
import { formatINR, formatDateTime, shortId } from '../utils/format';
import { getStateDisplay, getTransitionLabel } from '../utils/stateLabels';

interface TransactionDetailProps {
  txnId: string;
  onClose: () => void;
}

export function TransactionDetail({ txnId, onClose }: TransactionDetailProps) {
  const [transaction, setTransaction] = useState<TransactionDto | null>(null);
  const [history, setHistory] = useState<StateTransitionDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [complaintText, setComplaintText] = useState<string | null>(null);
  const [grievanceEmail, setGrievanceEmail] = useState<string | null>(null);
  const [generatingComplaint, setGeneratingComplaint] = useState(false);
  const [refundLoading, setRefundLoading] = useState(false);
  const [refundSuccess, setRefundSuccess] = useState(false);
  const [refundError, setRefundError] = useState<string | null>(null);

  useEffect(() => {
    const fetchData = async () => {
      try {
        setLoading(true);
        const [txnPage, hist] = await Promise.all([
          getTransactions({ page: 0 }),
          getTransactionHistory(txnId),
        ]);
        // Find the specific transaction in results
        const txn = txnPage.content.find((t) => t.txnId === txnId);
        setTransaction(txn ?? null);
        setHistory(hist);
      } catch (err) {
        console.error('Failed to load transaction detail:', err);
      } finally {
        setLoading(false);
      }
    };
    fetchData();
  }, [txnId]);

  const handleGenerateComplaint = async () => {
    try {
      setGeneratingComplaint(true);
      const result = await generateComplaint(txnId);
      setComplaintText(result.complaint);
      setGrievanceEmail(result.grievanceEmail ?? null);
    } catch (err) {
      console.error('Failed to generate complaint:', err);
    } finally {
      setGeneratingComplaint(false);
    }
  };

  const isRealGateway = transaction?.sourceGateway && transaction.sourceGateway !== 'simulated';

  const showComplaintCTA = transaction
    ? getStateDisplay(transaction.state).showComplaintAction
        && (!isRealGateway || transaction.connectionStatus === 'DISCONNECTED')
    : false;

  const isGatewayResolved = transaction
    ? isRealGateway
        && transaction.state === 'RESOLVED_REFUNDED'
    : false;

  const REFUND_ELIGIBLE_STATES = ['DEEMED_APPROVED', 'PENDING_RECONCILIATION', 'PENALTY_ACCRUING'];
  const showRefundCTA = transaction
    ? REFUND_ELIGIBLE_STATES.includes(transaction.state) && !refundSuccess
    : false;

  const handleProvisionalRefund = async () => {
    if (!transaction) return;
    try {
      setRefundLoading(true);
      setRefundError(null);
      await createProvisionalRefund(transaction.txnId, transaction.amountInr);
      setRefundSuccess(true);
    } catch (err: any) {
      console.error('Failed to create provisional refund:', err);
      setRefundError(err?.message ?? 'Failed to track refund. Please try again.');
    } finally {
      setRefundLoading(false);
    }
  };

  return (
    <>
      <div className="drawer-overlay" onClick={onClose} />
      <div className="drawer" role="dialog" aria-label="Transaction details">
        <div className="drawer__header">
          <h2 className="drawer__title">
            Transaction #{shortId(txnId)}
          </h2>
          <button className="drawer__close" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>

        <div className="drawer__body">
          {loading ? (
            <div className="loading-spinner"><div className="spinner" /></div>
          ) : transaction ? (
            <>
              <h3 className="drawer__section-title">Overview</h3>
              <div className="detail-grid">
                <div className="detail-item">
                  <span className="detail-item__label">Status</span>
                  <StateBadge state={transaction.state} />
                </div>
                <div className="detail-item">
                  <span className="detail-item__label">Amount</span>
                  <span className="detail-item__value">
                    {formatINR(transaction.amountInr)}
                  </span>
                </div>
                <div className="detail-item">
                  <span className="detail-item__label">Penalty Accrued</span>
                  <span className="detail-item__value" style={{ color: (transaction.penaltyAmountInr ?? 0) > 0 ? 'var(--warning)' : undefined }}>
                    {formatINR(transaction.penaltyAmountInr)}
                  </span>
                </div>
                <div className="detail-item">
                  <span className="detail-item__label">From Bank</span>
                  <span className="detail-item__value">
                    {transaction.remitterBankName ?? '—'}
                  </span>
                </div>
                <div className="detail-item">
                  <span className="detail-item__label">To Bank</span>
                  <span className="detail-item__value">
                    {transaction.beneficiaryBankName ?? '—'}
                  </span>
                </div>
                <div className="detail-item">
                  <span className="detail-item__label">Created</span>
                  <span className="detail-item__value detail-item__value--mono">
                    {formatDateTime(transaction.createdAt)}
                  </span>
                </div>
                <div className="detail-item">
                  <span className="detail-item__label">Deadline</span>
                  <span className="detail-item__value detail-item__value--mono">
                    {formatDateTime(transaction.tatDeadline)}
                  </span>
                </div>
                <div className="detail-item">
                  <span className="detail-item__label">Resolved</span>
                  <span className="detail-item__value detail-item__value--mono">
                    {formatDateTime(transaction.resolvedAt)}
                  </span>
                </div>
                {transaction.declineCode && (
                  <div className="detail-item">
                    <span className="detail-item__label">Decline Code</span>
                    <span className="detail-item__value detail-item__value--mono">
                      {transaction.declineCode}
                    </span>
                  </div>
                )}
                {transaction.orderReference && (
                  <div className="detail-item">
                    <span className="detail-item__label">Order Ref</span>
                    <span className="detail-item__value detail-item__value--mono">
                      {transaction.orderReference}
                    </span>
                  </div>
                )}
                <div className="detail-item detail-item--full">
                  <span className="detail-item__label">Transaction ID</span>
                  <span className="detail-item__value detail-item__value--mono">
                    {transaction.txnId}
                  </span>
                </div>
                {transaction.gateway && (
                  <div className="detail-item">
                    <span className="detail-item__label">Gateway</span>
                    <span className="detail-item__value">
                      <span className={`gateway-badge gateway-badge--${transaction.gateway.toLowerCase()} ${transaction.connectionStatus === 'DISCONNECTED' ? 'gateway-badge--disconnected' : ''}`}>
                        <span className="gateway-badge__dot" />
                        {transaction.gateway}
                        {transaction.connectionStatus === 'DISCONNECTED' && ' (Disconnected)'}
                      </span>
                    </span>
                  </div>
                )}
              </div>

              {/* ── Complaint CTA ─────────────────────────── */}
              {showComplaintCTA && (
                <button
                  className="btn-primary btn-primary--danger btn-primary--full"
                  onClick={handleGenerateComplaint}
                  disabled={generatingComplaint}
                >
                  {generatingComplaint ? (
                    <>
                      <span className="spinner" style={{ width: 16, height: 16, borderWidth: 2 }} />
                      Generating...
                    </>
                  ) : (
                    <>📋 Generate Complaint Draft</>
                  )}
                </button>
              )}

              {/* ── Gateway auto-resolved badge ──────────── */}
              {isGatewayResolved && (
                <div className="gateway-resolved-banner" id="gateway-resolved-badge">
                  <span className="gateway-resolved-banner__icon">⚡</span>
                  <span>
                    Auto-resolved via gateway ({transaction.sourceGateway}) — no draft needed
                  </span>
                </div>
              )}

              {/* ── Provisional Refund CTA ────────────────── */}
              {showRefundCTA && (
                <button
                  className="btn-primary btn-primary--recovery btn-primary--full"
                  onClick={handleProvisionalRefund}
                  disabled={refundLoading}
                >
                  {refundLoading ? (
                    <>
                      <span className="spinner" style={{ width: 16, height: 16, borderWidth: 2 }} />
                      Tracking...
                    </>
                  ) : (
                    <>💸 I refunded the customer myself — track this as pending recovery</>
                  )}
                </button>
              )}
              {refundSuccess && (
                <div className="refund-success-banner">
                  <span className="refund-success-banner__icon">✅</span>
                  <span>
                    Recorded! We're tracking {formatINR(transaction?.amountInr ?? 0)} as
                    fronted by you — you'll be notified when the bank settles.
                  </span>
                </div>
              )}
              {refundError && (
                <div className="refund-error-banner">
                  <span className="refund-error-banner__icon">⚠️</span>
                  <span>{refundError}</span>
                </div>
              )}

              {/* ── State History Timeline ────────────────── */}
              <h3 className="drawer__section-title">State History</h3>
              {history.length === 0 ? (
                <p style={{ color: 'var(--text-muted)', fontSize: 14 }}>
                  No state transitions recorded yet.
                </p>
              ) : (
                <div className="timeline">
                  {history.map((step, idx) => {
                    const toDisplay = getStateDisplay(step.toState);
                    return (
                      <div key={idx} className="timeline-item">
                        <div className={`timeline-item__dot timeline-item__dot--${toDisplay.variant}`} />
                        <div className="timeline-item__states">
                          {step.fromState && (
                            <>
                              <span>{getTransitionLabel(step.fromState)}</span>
                              <span className="timeline-item__arrow">→</span>
                            </>
                          )}
                          <span>{getTransitionLabel(step.toState)}</span>
                        </div>
                        <div className="timeline-item__time">
                          {formatDateTime(step.transitionedAt)}
                        </div>
                        {step.reason && (
                          <div className="timeline-item__reason">
                            {(step.reason.includes('via Razorpay') || step.reason.includes('via gateway')) && (
                              <span className="timeline-item__badge timeline-item__badge--gateway">⚡ Gateway Action</span>
                            )}
                            {(step.reason.includes('Batch auto-reversal') || step.reason.includes('batch')) && (
                              <span className="timeline-item__badge timeline-item__badge--batch">📦 NPCI Batch</span>
                            )}
                            {step.reason}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </>
          ) : (
            <div className="empty-state">
              <div className="empty-state__icon">🔍</div>
              <p className="empty-state__text">Transaction not found</p>
            </div>
          )}
        </div>
      </div>

      {complaintText && (
        <ComplaintModal
          complaintText={complaintText}
          grievanceEmail={grievanceEmail}
          txnId={txnId}
          onClose={() => { setComplaintText(null); setGrievanceEmail(null); }}
        />
      )}
    </>
  );
}
