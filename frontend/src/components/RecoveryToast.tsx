import { useEffect, useRef } from 'react';
import type { ProvisionalRefundRecoveredMessage } from '../api/types';
import { formatINR, formatDateTime } from '../utils/format';

interface RecoveryToastProps {
  recoveryEvents: ProvisionalRefundRecoveredMessage[];
  onDismiss: (index: number) => void;
}

/**
 * Fixed bottom-right toast stack that shows when a provisional refund
 * the merchant fronted has been recovered by the bank.
 */
export function RecoveryToast({ recoveryEvents, onDismiss }: RecoveryToastProps) {
  if (recoveryEvents.length === 0) return null;

  return (
    <div className="recovery-toast-container" aria-live="polite">
      {recoveryEvents.map((event, idx) => (
        <RecoveryToastItem
          key={`${event.provisionalRefundId}-${idx}`}
          event={event}
          onDismiss={() => onDismiss(idx)}
        />
      ))}
    </div>
  );
}

function RecoveryToastItem({
  event,
  onDismiss,
}: {
  event: ProvisionalRefundRecoveredMessage;
  onDismiss: () => void;
}) {
  const timerRef = useRef<ReturnType<typeof setTimeout>>();

  useEffect(() => {
    timerRef.current = setTimeout(onDismiss, 8000);
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [onDismiss]);

  return (
    <div className="recovery-toast">
      <div className="recovery-toast__icon">✅</div>
      <div className="recovery-toast__content">
        <p className="recovery-toast__title">Recovery Complete</p>
        <p className="recovery-toast__message">
          You fronted {formatINR(event.amountRecovered)} on{' '}
          {formatDateTime(event.timestamp)} — the bank has now settled this,
          you're covered.
        </p>
      </div>
      <button
        className="recovery-toast__close"
        onClick={onDismiss}
        aria-label="Dismiss"
      >
        ✕
      </button>
      <div className="recovery-toast__progress" />
    </div>
  );
}
