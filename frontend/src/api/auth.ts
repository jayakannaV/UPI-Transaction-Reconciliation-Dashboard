import { apiFetch } from './client';

export interface AuthResponse {
  token: string;
}

export interface MeResponse {
  id: string;
  businessName: string;
  email: string;
}

export async function login(email: string, password: string):Promise<AuthResponse> {
  return apiFetch<AuthResponse>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
}

export async function signup(businessName: string, email: string, password: string):Promise<AuthResponse> {
  return apiFetch<AuthResponse>('/auth/signup', {
    method: 'POST',
    body: JSON.stringify({ businessName, email, password }),
  });
}

export async function getMe():Promise<MeResponse> {
  return apiFetch<MeResponse>('/auth/me');
}
