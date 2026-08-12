import { useState, useEffect } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { login } from '../api/auth';
import { useAuth } from '../contexts/AuthContext';

export function Login() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  
  const navigate = useNavigate();
  const { token, setToken } = useAuth();

  useEffect(() => {
    if (token) navigate('/', { replace: true });
  }, [token, navigate]);

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email.trim() || !password.trim()) {
      setError('Please enter both email and password.');
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const response = await login(email, password);
      setToken(response.token);
      navigate('/');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed. Please check your credentials.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="onboarding">
      <div className="onboarding__form-wrapper" style={{ marginTop: '4rem' }}>
        <div className="onboarding__form-card">
          <div className="onboarding__form-header">
            <div>
              <h3 className="onboarding__form-title">Welcome Back</h3>
              <p className="onboarding__form-subtitle">
                Log in to access your UPI Reconciliation Dashboard.
              </p>
            </div>
          </div>

          <form onSubmit={handleLogin} className="onboarding__fields">
            <label className="onboarding__field">
              <span className="onboarding__field-label">Email Address</span>
              <input
                id="input-login-email"
                type="email"
                className="onboarding__input"
                placeholder="merchant@example.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoFocus
                required
              />
            </label>
            <label className="onboarding__field">
              <span className="onboarding__field-label">Password</span>
              <input
                id="input-login-password"
                type="password"
                className="onboarding__input"
                placeholder="••••••••••••••••"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            </label>

            {error && (
              <div className="onboarding__error">
                <span>⚠</span> {error}
              </div>
            )}

            <button
              id="btn-login-submit"
              type="submit"
              className="btn-primary btn-primary--full"
              disabled={loading || !email.trim() || !password.trim()}
              style={{ marginTop: '1rem' }}
            >
              {loading ? (
                <>
                  <span className="spinner spinner--sm" />
                  Logging in…
                </>
              ) : (
                'Log In'
              )}
            </button>
          </form>

          <p style={{ marginTop: '2rem', textAlign: 'center', color: 'var(--text-muted)' }}>
            Don't have an account? <Link to="/signup" style={{ color: 'var(--gold-400)', textDecoration: 'none' }}>Sign up</Link>
          </p>
        </div>
      </div>
    </div>
  );
}
