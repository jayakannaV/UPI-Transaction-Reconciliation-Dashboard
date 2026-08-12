import { useState, useEffect } from 'react';
import {
  getConnections,
  createConnection,
  disconnectConnection,
  reconnectConnection,
  type ConnectionDto,
  type ConnectionCreateResponse
} from '../api/connections';

interface ConnectionsPageProps {
  onConnected: () => void;
}

type GatewayId = 'razorpay' | 'payu' | 'cashfree';

interface GatewayInfo {
  id: GatewayId;
  name: string;
  icon: string;
  description: string;
  isSandbox: boolean;
}

const GATEWAYS: GatewayInfo[] = [
  {
    id: 'razorpay',
    name: 'Razorpay',
    icon: '⚡',
    description: 'Accept UPI, cards, wallets & more. India\'s most popular payment gateway.',
    isSandbox: false,
  },
  {
    id: 'payu',
    name: 'PayU',
    icon: '🔷',
    description: 'Enterprise payment solutions with multi-currency support.',
    isSandbox: true,
  },
  {
    id: 'cashfree',
    name: 'Cashfree',
    icon: '💚',
    description: 'Fast payouts, payment gateway, and banking APIs.',
    isSandbox: true,
  },
];

type Step = 'list' | 'form' | 'success';

export function ConnectionsPage({ onConnected }: ConnectionsPageProps) {
  const [step, setStep] = useState<Step>('list');
  const [connections, setConnections] = useState<ConnectionDto[]>([]);
  const [loading, setLoading] = useState(true);

  // Form states
  const [selectedGateway, setSelectedGateway] = useState<GatewayInfo | null>(null);
  const [isReconnect, setIsReconnect] = useState(false);
  const [activeConnectionId, setActiveConnectionId] = useState<string | null>(null);
  const [apiKey, setApiKey] = useState('');
  const [apiSecret, setApiSecret] = useState('');
  const [formLoading, setFormLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Success states
  const [result, setResult] = useState<ConnectionCreateResponse | null>(null);
  const [copiedUrl, setCopiedUrl] = useState(false);
  const [copiedSecret, setCopiedSecret] = useState(false);

  // Disconnect modal states
  const [disconnectModalOpen, setDisconnectModalOpen] = useState(false);
  const [connectionToDisconnect, setConnectionToDisconnect] = useState<ConnectionDto | null>(null);
  const [disconnectLoading, setDisconnectLoading] = useState(false);

  const fetchConnections = async () => {
    try {
      const data = await getConnections();
      setConnections(data);
    } catch (err) {
      console.error('Failed to fetch connections', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchConnections();
  }, []);

  const hasAnyActive = connections.some(c => c.status === 'ACTIVE');
  const hasGatewayConnectedCookie = localStorage.getItem('gateway_connected') === 'true';

  const handleConnectClick = (gw: GatewayInfo, existingConn: ConnectionDto | undefined) => {
    setSelectedGateway(gw);
    setApiKey('');
    setApiSecret('');
    setError(null);
    if (existingConn && existingConn.status === 'DISCONNECTED') {
      setIsReconnect(true);
      setActiveConnectionId(existingConn.connectionId);
    } else {
      setIsReconnect(false);
      setActiveConnectionId(null);
    }
    setStep('form');
  };

  const handleDisconnectClick = (conn: ConnectionDto) => {
    setConnectionToDisconnect(conn);
    setDisconnectModalOpen(true);
  };

  const confirmDisconnect = async () => {
    if (!connectionToDisconnect) return;
    setDisconnectLoading(true);
    try {
      await disconnectConnection(connectionToDisconnect.connectionId);
      await fetchConnections();
      setDisconnectModalOpen(false);
      setConnectionToDisconnect(null);
    } catch (err) {
      console.error('Disconnect failed', err);
    } finally {
      setDisconnectLoading(false);
    }
  };

  const handleFormSubmit = async () => {
    if (!selectedGateway) return;
    if (!apiKey.trim() || !apiSecret.trim()) {
      setError('Both API Key and API Secret are required.');
      return;
    }

    setFormLoading(true);
    setError(null);

    try {
      let res: ConnectionCreateResponse;
      const payload = {
        gateway: selectedGateway.id,
        apiKey: apiKey.trim(),
        apiSecret: apiSecret.trim(),
      };
      
      if (isReconnect && activeConnectionId) {
        res = await reconnectConnection(activeConnectionId, payload);
      } else {
        res = await createConnection(payload);
      }
      
      setResult(res);
      await fetchConnections();
      
      // Update global state if it's the first connection
      if (!hasGatewayConnectedCookie) {
        localStorage.setItem('gateway_connected', 'true');
      }
      
      setStep('success');
    } catch (err: any) {
      // Check if it's a 409 error
      if (err.message && err.message.includes('409')) {
        setError('An active connection to this gateway already exists.');
      } else {
        setError(err instanceof Error ? err.message : 'Connection failed. Please try again.');
      }
    } finally {
      setFormLoading(false);
    }
  };

  const copyToClipboard = async (text: string, setCopied: (v: boolean) => void) => {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      const ta = document.createElement('textarea');
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
    }
    setCopied(true);
    setTimeout(() => setCopied(false), 2500);
  };

  const handleFinishSuccess = () => {
    setStep('list');
    setResult(null);
    setSelectedGateway(null);
    if (!hasGatewayConnectedCookie) {
      onConnected();
    }
  };

  if (loading) {
    return (
      <div className="onboarding">
        <div className="loading-spinner">
          <div className="spinner"></div>
        </div>
      </div>
    );
  }

  return (
    <div className="onboarding">
      {/* ── Step 1: List Gateways ──────────────────────── */}
      {step === 'list' && (
        <div className="connections-page">
          <div className="onboarding__header">
            <h2 className="onboarding__title">Manage Connections</h2>
            <p className="onboarding__subtitle">
              Link your payment gateways to automatically track and recover stuck UPI payments.
            </p>
          </div>
          
          <div className="onboarding__cards">
            {GATEWAYS.map((gw) => {
              // Find if we have an active or disconnected connection for this gateway
              const activeConn = connections.find(c => c.gateway.toLowerCase() === gw.id && c.status === 'ACTIVE');
              const disconnectedConn = connections.find(c => c.gateway.toLowerCase() === gw.id && c.status === 'DISCONNECTED');
              const isConnected = !!activeConn;

              return (
                <div
                  key={gw.id}
                  id={`gateway-card-${gw.id}`}
                  className={`gateway-card ${isConnected ? 'conn-card--active' : ''}`}
                  style={isConnected ? { cursor: 'default' } : undefined}
                >
                  <div className="gateway-card__icon">{gw.icon}</div>
                  
                  <div className="gateway-card__content">
                    <div className="gateway-card__name-row">
                      <span className="gateway-card__name">{gw.name}</span>
                      {isConnected ? (
                        <span className="gateway-card__connected-badge">Connected ✓</span>
                      ) : gw.isSandbox ? (
                        <span className="gateway-card__sandbox-badge">Sandbox</span>
                      ) : null}
                    </div>
                    
                    <p className="gateway-card__desc">{gw.description}</p>
                    
                    {isConnected && activeConn?.connectedAt && (
                      <div className="conn-card__status">
                        Connected on {new Date(activeConn.connectedAt).toLocaleDateString()}
                      </div>
                    )}
                  </div>
                  
                  <div className="conn-card__actions">
                    {isConnected ? (
                      <button 
                        className="btn-secondary btn-outline-danger"
                        onClick={(e) => { e.stopPropagation(); handleDisconnectClick(activeConn); }}
                      >
                        Disconnect
                      </button>
                    ) : (
                      <button 
                        className="btn-secondary"
                        onClick={(e) => { e.stopPropagation(); handleConnectClick(gw, disconnectedConn); }}
                      >
                        {disconnectedConn ? 'Reconnect' : 'Connect'}
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>

          {(hasAnyActive || hasGatewayConnectedCookie) && (
            <button
              id="btn-skip-to-dashboard"
              className="btn-primary btn-primary--full"
              style={{ marginTop: '1.5rem' }}
              onClick={onConnected}
            >
              Go to Dashboard →
            </button>
          )}
        </div>
      )}

      {/* ── Step 2: Form ──────────────────────── */}
      {step === 'form' && selectedGateway && (
        <div className="onboarding__form-wrapper">
          <button className="onboarding__back" onClick={() => setStep('list')}>
            ← Back to gateways
          </button>
          <div className="onboarding__form-card">
            <div className="onboarding__form-header">
              <span className="onboarding__form-icon">{selectedGateway.icon}</span>
              <div>
                <div className="onboarding__form-title-row">
                  <h3 className="onboarding__form-title">
                    {isReconnect ? 'Reconnect' : 'Connect'} {selectedGateway.name}
                  </h3>
                  {selectedGateway.isSandbox && (
                    <span className="gateway-card__sandbox-badge">Sandbox mode</span>
                  )}
                </div>
                <p className="onboarding__form-subtitle">
                  Enter your {selectedGateway.name} test-mode API credentials below.
                </p>
              </div>
            </div>

            <div className="onboarding__fields">
              <label className="onboarding__field">
                <span className="onboarding__field-label">API Key</span>
                <input
                  id="input-api-key"
                  type="text"
                  className="onboarding__input"
                  placeholder={`${selectedGateway.id === 'razorpay' ? 'rzp_test_' : ''}...`}
                  value={apiKey}
                  onChange={(e) => setApiKey(e.target.value)}
                  autoFocus
                />
              </label>
              <label className="onboarding__field">
                <span className="onboarding__field-label">API Secret</span>
                <input
                  id="input-api-secret"
                  type="password"
                  className="onboarding__input"
                  placeholder="••••••••••••••••"
                  value={apiSecret}
                  onChange={(e) => setApiSecret(e.target.value)}
                />
              </label>
            </div>

            {error && (
              <div className="onboarding__error">
                <span>⚠</span> {error}
              </div>
            )}

            <button
              id="btn-connect-gateway"
              className="btn-primary btn-primary--full"
              onClick={handleFormSubmit}
              disabled={formLoading || !apiKey.trim() || !apiSecret.trim()}
            >
              {formLoading ? (
                <>
                  <span className="spinner spinner--sm" />
                  {isReconnect ? 'Reconnecting…' : 'Connecting…'}
                </>
              ) : (
                <>🔗 {isReconnect ? 'Reconnect' : 'Connect'} {selectedGateway.name}</>
              )}
            </button>
          </div>
        </div>
      )}

      {/* ── Step 3: Success ──────────────────────── */}
      {step === 'success' && selectedGateway && result && (
        <div className="onboarding__success-wrapper">
          <div className="onboarding__success-card">
            <div className="onboarding__success-icon">✓</div>
            <h3 className="onboarding__success-title">
              {selectedGateway.name} Connected!
            </h3>
            <p className="onboarding__success-subtitle">
              Copy both values below into your gateway's webhook settings to start
              receiving signed transaction events.
            </p>

            <div className="onboarding__webhook-section">
              <span className="onboarding__field-label">Webhook URL</span>
              <div className="onboarding__webhook-url-row">
                <code className="onboarding__webhook-url">
                  {window.location.origin}{result.webhookUrl}
                </code>
                <button
                  id="btn-copy-webhook-url"
                  className="btn-secondary"
                  onClick={() => copyToClipboard(window.location.origin + result.webhookUrl, setCopiedUrl)}
                >
                  {copiedUrl ? '✓ Copied!' : '📋 Copy'}
                </button>
              </div>
            </div>

            <div className="onboarding__webhook-section">
              <span className="onboarding__field-label">Webhook Secret</span>
              <div className="onboarding__webhook-url-row">
                <code className="onboarding__webhook-url">
                  {result.webhookSecret}
                </code>
                <button
                  id="btn-copy-webhook-secret"
                  className="btn-secondary"
                  onClick={() => copyToClipboard(result.webhookSecret, setCopiedSecret)}
                >
                  {copiedSecret ? '✓ Copied!' : '📋 Copy'}
                </button>
              </div>
            </div>

            <p className="onboarding__webhook-instructions">
              Paste the Webhook URL into {selectedGateway.name} Dashboard → Settings → Webhooks,
              and paste the Webhook Secret into the same form's "Secret" field.
            </p>

            <button
              id="btn-go-to-dashboard"
              className="btn-primary btn-primary--full"
              onClick={handleFinishSuccess}
            >
              {hasGatewayConnectedCookie ? 'Back to Connections' : 'Go to Dashboard →'}
            </button>
          </div>
        </div>
      )}

      {/* Disconnect Modal */}
      {disconnectModalOpen && connectionToDisconnect && (
        <div className="modal-overlay" onClick={() => !disconnectLoading && setDisconnectModalOpen(false)}>
          <div className="modal" style={{ width: '400px' }} onClick={e => e.stopPropagation()}>
            <div className="modal__header">
              <h3 className="modal__title">Disconnect {connectionToDisconnect.gateway}?</h3>
              <button className="modal__close" onClick={() => setDisconnectModalOpen(false)} disabled={disconnectLoading}>×</button>
            </div>
            <div className="modal__body">
              <p style={{ fontSize: '14px', color: 'var(--text-secondary)', lineHeight: '1.5' }}>
                Disconnecting will stop new transactions from {connectionToDisconnect.gateway} appearing in your dashboard. Existing transaction history is kept.
                <br /><br />
                Continue?
              </p>
            </div>
            <div className="modal__actions">
              <button 
                className="btn-secondary" 
                onClick={() => setDisconnectModalOpen(false)}
                disabled={disconnectLoading}
              >
                Cancel
              </button>
              <button 
                className="btn-primary btn-primary--danger" 
                onClick={confirmDisconnect}
                disabled={disconnectLoading}
              >
                {disconnectLoading ? (
                  <><span className="spinner spinner--sm" /> Disconnecting...</>
                ) : (
                  'Disconnect'
                )}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
