import { useState } from 'react';

interface ComplaintModalProps {
  complaintText: string;
  grievanceEmail?: string | null;
  txnId?: string;
  onClose: () => void;
}

export function ComplaintModal({ complaintText, grievanceEmail, txnId, onClose }: ComplaintModalProps) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(complaintText);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Fallback for non-secure contexts
      const ta = document.createElement('textarea');
      ta.value = complaintText;
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  const handleOpenEmail = () => {
    if (!grievanceEmail) return;

    const subject = txnId
      ? `UPI TAT Compliance Complaint — Transaction ${txnId}`
      : 'UPI TAT Compliance Complaint';

    const mailtoUrl =
      `mailto:${encodeURIComponent(grievanceEmail)}` +
      `?subject=${encodeURIComponent(subject)}` +
      `&body=${encodeURIComponent(complaintText)}`;

    window.location.href = mailtoUrl;
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-label="Generated complaint draft">
        <div className="modal__header">
          <h3 className="modal__title">📋 RBI Complaint Draft</h3>
          <button className="modal__close" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>
        <div className="modal__body">
          <div className="complaint-disclaimer">
            ℹ️ This is a pre-filled draft based on RBI's compensation framework
            — review it, then send it yourself through your bank's grievance
            portal or the RBI Ombudsman's process.
          </div>
          <div className="complaint-text">{complaintText}</div>
        </div>
        <div className="modal__actions">
          <button className="btn-secondary" onClick={onClose}>
            Close
          </button>
          <button className="btn-primary" onClick={handleCopy}>
            {copied ? '✓ Copied!' : '📋 Copy Draft'}
          </button>
          {grievanceEmail && (
            <button
              className="btn-email-draft"
              onClick={handleOpenEmail}
              title={`Opens your email client with draft addressed to ${grievanceEmail}`}
            >
              ✉️ Open Email Draft
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
