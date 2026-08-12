import { useState, useEffect, useCallback } from 'react';
import { Routes, Route, Navigate, useNavigate, useLocation } from 'react-router-dom';
import { useWebSocket } from './hooks/useWebSocket';
import { HeroCounter } from './components/HeroCounter';
import { AnomalyBanner } from './components/AnomalyBanner';
import { LiveFeed } from './components/LiveFeed';
import { BankScorecard } from './components/BankScorecard';
import { TransactionDetail } from './components/TransactionDetail';
import { ConnectionsPage } from './components/ConnectionsPage';
import { RecoveryToast } from './components/RecoveryToast';
import { ProvisionalSummaryCard } from './components/ProvisionalSummaryCard';
import { getTransactions } from './api/transactions';
import type { TransactionDto, PageResponse } from './api/types';
import { useAuth } from './contexts/AuthContext';
import { Login } from './components/Login';
import { Signup } from './components/Signup';

// ProtectedRoute component
function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { token, loading } = useAuth();
  if (loading) return null;
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

function App() {
  const { status, liveMessages, anomalies, dismissAnomaly, recoveryEvents, dismissRecovery } = useWebSocket();
  const [selectedTxnId, setSelectedTxnId] = useState<string | null>(null);
  const [heroData, setHeroData] = useState({
    totalRecovered: 0,
    totalPenaltyTxns: 0,
    totalTxns: 0,
  });

  const { token, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const handleGatewayConnected = () => {
    localStorage.setItem('gateway_connected', 'true');
    navigate('/');
  };

  // Compute hero data from all RESOLVED_REFUNDED transactions
  const fetchHeroData = useCallback(async () => {
    if (!token) return;
    try {
      // Get total count of all transactions
      const allTxns: PageResponse<TransactionDto> = await getTransactions({ page: 0 });
      
      // Get resolved/refunded transactions to sum penalties
      const resolvedTxns: PageResponse<TransactionDto> = await getTransactions({
        state: 'RESOLVED_REFUNDED',
        page: 0,
      });

      let totalRecovered = 0;
      let totalPenaltyTxns = resolvedTxns.totalElements;

      // Sum up penalties from the first page
      for (const txn of resolvedTxns.content) {
        totalRecovered += txn.penaltyAmountInr ?? 0;
      }

      // If there are more pages, fetch them too
      for (let p = 1; p < resolvedTxns.totalPages; p++) {
        const page = await getTransactions({ state: 'RESOLVED_REFUNDED', page: p });
        for (const txn of page.content) {
          totalRecovered += txn.penaltyAmountInr ?? 0;
        }
      }

      setHeroData({
        totalRecovered,
        totalPenaltyTxns,
        totalTxns: allTxns.totalElements,
      });
    } catch (err) {
      console.error('Failed to compute hero data:', err);
    }
  }, [token]);

  useEffect(() => {
    if (location.pathname === '/' && token) {
      fetchHeroData();
    }
  }, [fetchHeroData, location.pathname, token]);

  // Refresh hero when new WS messages arrive (debounced)
  useEffect(() => {
    if (liveMessages.length === 0 || location.pathname !== '/' || !token) return;
    const timer = setTimeout(() => fetchHeroData(), 1500);
    return () => clearTimeout(timer);
  }, [liveMessages.length, fetchHeroData, location.pathname, token]);

  return (
    <div className="app-container">
      {/* ── Header ─────────────────────────────────────── */}
      <header className="app-header">
        <div className="app-header__logo">
          <div className="app-header__icon">₹</div>
          <div>
            <div className="app-header__title">UPI Recovery Dashboard</div>
            <div className="app-header__subtitle">Automated refund tracking & complaint filing</div>
          </div>
        </div>
        <div className="app-header__nav">
          {token && localStorage.getItem('gateway_connected') === 'true' && (
            <>
              <button
                className={`nav-link ${location.pathname === '/' ? 'nav-link--active' : ''}`}
                onClick={() => navigate('/')}
              >
                Dashboard
              </button>
              <button
                className={`nav-link ${location.pathname === '/connections' ? 'nav-link--active' : ''}`}
                onClick={() => navigate('/connections')}
              >
                Connections
              </button>
            </>
          )}
          {token && (
            <>
              <div className="app-header__status">
                <span className={`status-dot ${status !== 'connected' ? 'status-dot--disconnected' : ''}`} />
                {status === 'connected'
                  ? 'Live'
                  : status === 'connecting'
                  ? 'Connecting…'
                  : 'Disconnected'}
              </div>
              <button
                className="nav-link"
                onClick={logout}
                style={{ color: 'var(--danger)', marginLeft: '1rem' }}
              >
                Log out
              </button>
            </>
          )}
        </div>
      </header>

      {/* ── Routes ─────────────────────────────── */}
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/signup" element={<Signup />} />

        <Route path="/connections" element={
          <ProtectedRoute>
            <ConnectionsPage onConnected={handleGatewayConnected} />
          </ProtectedRoute>
        } />
        
        <Route path="/" element={
          <ProtectedRoute>
            {localStorage.getItem('gateway_connected') === 'true' ? (
              <>
                {/* ── Dashboard View ─────── */}
                <AnomalyBanner anomalies={anomalies} onDismiss={dismissAnomaly} />

                <HeroCounter
                  totalRecovered={heroData.totalRecovered}
                  totalPenaltyTransactions={heroData.totalPenaltyTxns}
                  totalTransactions={heroData.totalTxns}
                />

                <ProvisionalSummaryCard liveMessageCount={liveMessages.length} />

                <div className="main-grid">
                  <LiveFeed
                    liveMessages={liveMessages}
                    onSelectTransaction={setSelectedTxnId}
                  />
                  <BankScorecard liveMessages={liveMessages} />
                </div>

                {selectedTxnId && (
                  <TransactionDetail
                    txnId={selectedTxnId}
                    onClose={() => setSelectedTxnId(null)}
                  />
                )}

                <RecoveryToast
                  recoveryEvents={recoveryEvents}
                  onDismiss={dismissRecovery}
                />
              </>
            ) : (
              <Navigate to="/connections" replace />
            )}
          </ProtectedRoute>
        } />
      </Routes>
    </div>
  );
}

export default App;

