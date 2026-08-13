import { useEffect, useState, useCallback } from 'react';
import { getProvisionalSummary } from '../api/merchants';
import type { ProvisionalSummaryDto } from '../api/types';


interface ProvisionalSummaryCardProps {
  /** Number of live WS messages — used to trigger a debounced refresh */
  liveMessageCount: number;
}

/**
 * Dashboard card showing total amount the merchant has fronted to customers
 * that is still awaiting bank recovery. Only renders when count > 0.
 *
 * Visually distinct from the gold penalty counter — uses the info (blue)
 * palette to communicate "in-progress / awaiting" status.
 */
export function ProvisionalSummaryCard({ liveMessageCount }: ProvisionalSummaryCardProps) {
  const [summary, setSummary] = useState<ProvisionalSummaryDto | null>(null);

  const fetchSummary = useCallback(async () => {
    try {
      const data = await getProvisionalSummary();
      setSummary(data);
    } catch (err) {
      console.error('Failed to fetch provisional summary:', err);
    }
  }, []);

  // Initial fetch
  useEffect(() => {
    fetchSummary();
  }, [fetchSummary]);

  // Debounced refresh when new WS messages arrive
  useEffect(() => {
    if (liveMessageCount === 0) return;
    const timer = setTimeout(() => fetchSummary(), 2000);
    return () => clearTimeout(timer);
  }, [liveMessageCount, fetchSummary]);

  if (!summary || summary.count === 0) return null;

  const formatted = new Intl.NumberFormat('en-IN').format(summary.total_pending_recovery);

  return (
    <section className="provisional-card" aria-label="Provisional recovery summary">
      <div className="provisional-card__badge">
        <span className="provisional-card__badge-dot" />
        Awaiting Bank Recovery
      </div>
      <p className="provisional-card__amount" aria-live="polite">
        <span className="provisional-card__currency">₹</span>
        {formatted}
      </p>
      <p className="provisional-card__detail">
        currently fronted to customers, awaiting bank recovery
      </p>
      <div className="provisional-card__meta">
        <span className="provisional-card__count">
          {summary.count} transaction{summary.count !== 1 ? 's' : ''}
        </span>
        <span className="provisional-card__separator">·</span>
        <span className="provisional-card__status">Recovery pending from bank</span>
      </div>
    </section>
  );
}
