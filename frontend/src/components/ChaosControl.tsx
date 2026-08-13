import { useState, useEffect, useCallback } from 'react';
import { getTransactions } from '../api/transactions';
import { getBankScorecard } from '../api/banks';
import { getConnections } from '../api/connections';
import {
  seedDemoData,
  fireDuplicate,
  forceBreach,
  createDeemedApproved,
  triggerAnomaly,
  simulateRazorpayPayment,
  simulateMissedWebhook
} from '../api/chaos';
import type { TransactionDto, BankScorecardDto } from '../api/types';
import type { ConnectionDto } from '../api/connections';
import { useWebSocket } from '../hooks/useWebSocket';

export function ChaosControl() {
  const { liveMessages } = useWebSocket();
  const [transactions, setTransactions] = useState<TransactionDto[]>([]);
  const [banks, setBanks] = useState<BankScorecardDto[]>([]);
  const [connections, setConnections] = useState<ConnectionDto[]>([]);
  
  const [selectedBankId, setSelectedBankId] = useState<string>('');
  const [loadingAction, setLoadingAction] = useState<string | null>(null);
  const [message, setMessage] = useState<{ text: string; type: 'success' | 'error' } | null>(null);

  const fetchData = useCallback(async () => {
    try {
      const [txnsRes, banksRes, connsRes] = await Promise.all([
        getTransactions({ page: 0 }),
        getBankScorecard(),
        getConnections()
      ]);
      setTransactions(txnsRes.content);
      setBanks(banksRes);
      setConnections(connsRes);
      
      if (banksRes.length > 0 && !selectedBankId) {
        setSelectedBankId(banksRes[0].bankId);
      }
    } catch (err) {
      console.error('Failed to fetch chaos control data:', err);
    }
  }, [selectedBankId]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // Refresh data when live messages arrive to keep list updated
  useEffect(() => {
    if (liveMessages.length === 0) return;
    const timer = setTimeout(() => fetchData(), 500);
    return () => clearTimeout(timer);
  }, [liveMessages.length, fetchData]);

  const showMessage = (text: string, type: 'success' | 'error' = 'success') => {
    setMessage({ text, type });
    setTimeout(() => setMessage(null), 5000);
  };

  const wrapAction = async (actionId: string, actionFn: () => Promise<any>, successMsg: string) => {
    setLoadingAction(actionId);
    try {
      await actionFn();
      showMessage(successMsg, 'success');
      fetchData(); // Refresh immediately
    } catch (err: any) {
      showMessage(err.message || 'Action failed', 'error');
    } finally {
      setLoadingAction(null);
    }
  };

  const handleSeedDemoData = () => 
    wrapAction('seed', seedDemoData, 'Demo data seeded successfully!');

  const handleCreateDeemedApproved = () => 
    wrapAction('deemed', createDeemedApproved, 'Deemed approved transaction created!');

  const handleSimulateRazorpay = () => 
    wrapAction('simulate_razorpay', simulateRazorpayPayment, 'Razorpay payment simulated!');

  const handleSimulateMissedWebhook = () => 
    wrapAction('simulate_missed_webhook', simulateMissedWebhook, 'Staged — this will resolve via a real Razorpay API call on the next check cycle.');

  const handleFireDuplicate = (txnId: string) => 
    wrapAction(`duplicate_${txnId}`, () => fireDuplicate(txnId), 'Duplicate fired!');

  const handleForceBreach = (txnId: string) => 
    wrapAction(`breach_${txnId}`, () => forceBreach(txnId), 'Forced TAT breach!');

  const handleTriggerAnomaly = () => {
    if (!selectedBankId) return;
    wrapAction('anomaly', () => triggerAnomaly(selectedBankId), 'Anomaly burst triggered!');
  };

  const isRazorpayActive = connections.some(c => c.gateway.toLowerCase() === 'razorpay' && c.status === 'ACTIVE');

  return (
    <div style={{ padding: '2rem', maxWidth: '1000px', margin: '0 auto', fontFamily: 'system-ui, sans-serif' }}>
      <h1 style={{ borderBottom: '2px solid #ccc', paddingBottom: '0.5rem', marginBottom: '1.5rem' }}>Chaos Control Panel</h1>
      
      {message && (
        <div style={{ 
          padding: '1rem', 
          marginBottom: '1rem', 
          backgroundColor: message.type === 'error' ? '#fee2e2' : '#dcfce7',
          color: message.type === 'error' ? '#991b1b' : '#166534',
          borderRadius: '4px',
          border: `1px solid ${message.type === 'error' ? '#f87171' : '#86efac'}`
        }}>
          {message.text}
        </div>
      )}

      {/* Global Actions Section */}
      <section style={{ marginBottom: '2rem', padding: '1.5rem', backgroundColor: '#f3f4f6', borderRadius: '8px' }}>
        <h2 style={{ marginTop: 0, fontSize: '1.25rem', marginBottom: '1rem' }}>Global Actions</h2>
        
        <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
          <button 
            onClick={handleSeedDemoData}
            disabled={loadingAction === 'seed'}
            style={btnStyle}
          >
            {loadingAction === 'seed' ? 'Seeding...' : 'Seed Demo Data'}
          </button>

          <button 
            onClick={handleCreateDeemedApproved}
            disabled={loadingAction === 'deemed'}
            style={btnStyle}
          >
            {loadingAction === 'deemed' ? 'Creating...' : 'Create Deemed Approved Transaction'}
          </button>

          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
            <button 
              onClick={handleSimulateRazorpay}
              disabled={loadingAction === 'simulate_razorpay' || !isRazorpayActive}
              style={{ ...btnStyle, opacity: !isRazorpayActive ? 0.5 : 1 }}
            >
              {loadingAction === 'simulate_razorpay' ? 'Simulating...' : 'Simulate Razorpay Payment'}
            </button>
            {!isRazorpayActive && (
              <span style={{ fontSize: '0.75rem', color: '#6b7280' }}>Connect Razorpay to enable</span>
            )}
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
            <button 
              onClick={handleSimulateMissedWebhook}
              disabled={loadingAction === 'simulate_missed_webhook' || !isRazorpayActive}
              style={{ ...btnStyle, opacity: !isRazorpayActive ? 0.5 : 1 }}
            >
              {loadingAction === 'simulate_missed_webhook' ? 'Staging...' : 'Simulate Missed Webhook (Real Gateway)'}
            </button>
            {!isRazorpayActive && (
              <span style={{ fontSize: '0.75rem', color: '#6b7280' }}>Connect Razorpay to enable</span>
            )}
          </div>
        </div>
      </section>

      {/* Trigger Anomaly Section */}
      <section style={{ marginBottom: '2rem', padding: '1.5rem', backgroundColor: '#fdf3c7', borderRadius: '8px' }}>
        <h2 style={{ marginTop: 0, fontSize: '1.25rem', marginBottom: '1rem' }}>Trigger Anomaly</h2>
        <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
          <select 
            value={selectedBankId} 
            onChange={e => setSelectedBankId(e.target.value)}
            style={{ padding: '0.5rem', borderRadius: '4px', border: '1px solid #ccc', flex: 1, maxWidth: '300px' }}
          >
            <option value="" disabled>Select a bank</option>
            {banks.map(b => (
              <option key={b.bankId} value={b.bankId}>{b.bankName}</option>
            ))}
          </select>
          <button 
            onClick={handleTriggerAnomaly}
            disabled={loadingAction === 'anomaly' || !selectedBankId}
            style={{ ...btnStyle, backgroundColor: '#ca8a04', color: 'white' }}
          >
            {loadingAction === 'anomaly' ? 'Triggering...' : 'Fire Anomaly Burst'}
          </button>
        </div>
      </section>

      {/* Recent Transactions Section */}
      <section style={{ marginBottom: '2rem' }}>
        <h2 style={{ fontSize: '1.25rem', marginBottom: '1rem', borderBottom: '1px solid #eee', paddingBottom: '0.5rem' }}>Recent Transactions</h2>
        
        {transactions.length === 0 ? (
          <p style={{ color: '#6b7280' }}>No transactions found.</p>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.9rem' }}>
            <thead>
              <tr style={{ backgroundColor: '#f9fafb', textAlign: 'left', borderBottom: '2px solid #e5e7eb' }}>
                <th style={thStyle}>ID</th>
                <th style={thStyle}>State</th>
                <th style={thStyle}>Gateway</th>
                <th style={thStyle}>Amount</th>
                <th style={thStyle}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {transactions.map(txn => (
                <tr key={txn.txnId} style={{ borderBottom: '1px solid #e5e7eb' }}>
                  <td style={tdStyle}>
                    <div style={{ fontFamily: 'monospace', fontSize: '0.8rem' }}>{txn.txnId}</div>
                  </td>
                  <td style={tdStyle}>
                    <span style={{ 
                      padding: '0.2rem 0.5rem', 
                      borderRadius: '9999px', 
                      backgroundColor: '#e5e7eb', 
                      fontSize: '0.75rem',
                      fontWeight: 600
                    }}>
                      {txn.state}
                    </span>
                  </td>
                  <td style={tdStyle}>{txn.sourceGateway}</td>
                  <td style={tdStyle}>₹{txn.amountInr}</td>
                  <td style={tdStyle}>
                    <div style={{ display: 'flex', gap: '0.5rem' }}>
                      <button 
                        onClick={() => handleFireDuplicate(txn.txnId)}
                        disabled={loadingAction === `duplicate_${txn.txnId}`}
                        style={smallBtnStyle}
                      >
                        Fire Duplicate
                      </button>
                      
                      {txn.state === 'PENDING_RECONCILIATION' && (
                        <button 
                          onClick={() => handleForceBreach(txn.txnId)}
                          disabled={loadingAction === `breach_${txn.txnId}`}
                          style={{ ...smallBtnStyle, backgroundColor: '#ef4444', color: 'white', borderColor: '#dc2626' }}
                        >
                          Force TAT Breach
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}

const btnStyle = {
  padding: '0.5rem 1rem',
  backgroundColor: '#ffffff',
  border: '1px solid #d1d5db',
  borderRadius: '4px',
  cursor: 'pointer',
  fontWeight: 500,
  fontSize: '0.9rem',
};

const smallBtnStyle = {
  padding: '0.25rem 0.5rem',
  backgroundColor: '#ffffff',
  border: '1px solid #d1d5db',
  borderRadius: '4px',
  cursor: 'pointer',
  fontSize: '0.8rem',
};

const thStyle = { padding: '0.75rem 0.5rem' };
const tdStyle = { padding: '0.75rem 0.5rem' };
