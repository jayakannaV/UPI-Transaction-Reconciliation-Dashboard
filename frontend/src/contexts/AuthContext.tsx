import { createContext, useContext, useState, useEffect, type ReactNode } from 'react';
import { getMe, type MeResponse } from '../api/auth';

interface AuthContextType {
  token: string | null;
  user: MeResponse | null;
  loading: boolean;
  setToken: (token: string) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState<string | null>(localStorage.getItem('auth_token'));
  const [user, setUser] = useState<MeResponse | null>(null);
  const [loading, setLoading] = useState(true);

  const setToken = (newToken: string) => {
    localStorage.setItem('auth_token', newToken);
    setTokenState(newToken);
  };

  const logout = () => {
    localStorage.removeItem('auth_token');
    setTokenState(null);
    setUser(null);
  };

  useEffect(() => {
    const handleUnauthorized = () => {
      logout();
    };
    window.addEventListener('unauthorized', handleUnauthorized);
    return () => window.removeEventListener('unauthorized', handleUnauthorized);
  }, []);

  useEffect(() => {
    async function validateToken() {
      if (!token) {
        setLoading(false);
        return;
      }
      
      try {
        const me = await getMe();
        setUser(me);
      } catch (err) {
        console.error('Failed to validate token on load', err);
        // Do not call logout() directly here if apiFetch already dispatched 'unauthorized'
        // But if getMe failed for some other reason, we might want to logout. 
        // apiFetch throws error and clears localStorage on 401 anyway.
      } finally {
        setLoading(false);
      }
    }

    validateToken();
  }, [token]);

  return (
    <AuthContext.Provider value={{ token, user, loading, setToken, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
