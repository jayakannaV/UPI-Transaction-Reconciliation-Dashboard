import { useState, useEffect } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { signup } from '../api/auth';
import { useAuth } from '../contexts/AuthContext';

export function Signup() {
  const [businessName, setBusinessName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  
  const navigate = useNavigate();
  const { token, setToken } = useAuth();

  useEffect(() => {
    if (token) navigate('/', { replace: true });
  }, [token, navigate]);

  const handleSignup = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!businessName.trim() || !email.trim() || !password.trim()) {
      setError('Please fill in all fields.');
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const response = await signup(businessName, email, password);
      setToken(response.token);
      // Redirect to onboarding flow after signup
      navigate('/onboarding');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Sign up failed. Please try again.');
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
              <h3 className="onboarding__form-title">Create an Account</h3>
              <p className="onboarding__form-subtitle">
                Sign up to automate your UPI transaction reconciliation.
              </p>
            </div>
          </div>

          <form onSubmit={handleSignup} className="onboarding__fields">
            <label className="onboarding__field">
              <span className="onboarding__field-label">Business Name</span>
              <input
                id="input-signup-business"
                type="text"
                className="onboarding__input"
                placeholder="Acme Corp"
                value={businessName}
                onChange={(e) => setBusinessName(e.target.value)}
                autoFocus
                required
              />
            </label>
            <label className="onboarding__field">
              <span className="onboarding__field-label">Email Address</span>
              <input
                id="input-signup-email"
                type="email"
                className="onboarding__input"
                placeholder="merchant@example.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
              />
            </label>
            <label className="onboarding__field">
              <span className="onboarding__field-label">Password</span>
              <input
                id="input-signup-password"
                type="password"
                className="onboarding__input"
                placeholder="••••••••••••••••"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                minLength={8}
              />
            </label>

            {error && (
              <div className="onboarding__error">
                <span>⚠</span> {error}
              </div>
            )}

            <button
              id="btn-signup-submit"
              type="submit"
              className="btn-primary btn-primary--full"
              disabled={loading || !businessName.trim() || !email.trim() || !password.trim()}
              style={{ marginTop: '1rem' }}
            >
              {loading ? (
                <>
                  <span className="spinner spinner--sm" />
                  Creating Account…
                </>
              ) : (
                'Sign Up'
              )}
            </button>
          </form>

          <p style={{ marginTop: '2rem', textAlign: 'center', color: 'var(--text-muted)' }}>
            Already have an account? <Link to="/login" style={{ color: 'var(--gold-400)', textDecoration: 'none' }}>Log in</Link>
          </p>
        </div>
      </div>
    </div>
  );
}
