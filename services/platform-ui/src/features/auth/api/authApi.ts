import { http } from "../../../shared/api/http";

export type RegisterUserRequest = {
  email: string;
  password: string;
  fullName: string;
  phone: string;
};

export type CreateSessionRequest = {
  email: string;
  password: string;
  otp?: string;
  deviceLabel?: string;
};

export type SessionResponse = {
  sessionId: string;
  userId: string;
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  refreshExpiresIn: number;
  scope: string;
  deviceLabel?: string;
  current?: boolean;
  createdAt?: string;
  lastSeenAt?: string;
};

export type UserResponse = {
  userId: string;
  email: string;
  fullName: string;
  phone: string;
  status: string;
  kycLevel: string;
  mfaEnabled: boolean;
  createdAt: string;
  updatedAt: string;
  version: number;
};

export type TokenResponse = {
  access_token: string;
  refresh_token: string;
  token_type: string;
  expires_in: number;
  refresh_expires_in: number;
  scope: string;
};

export type PagedSessions = {
  data: SessionResponse[];
  page: { nextCursor: string | null; hasMore: boolean; limit: number };
};

export async function registerUser(payload: RegisterUserRequest) {
  const response = await http.post<UserResponse>("/v1/users", payload, {
    headers: { "Idempotency-Key": crypto.randomUUID() },
  });
  return response.data;
}

export async function login(payload: CreateSessionRequest) {
  const response = await http.post<SessionResponse>("/v1/sessions", payload);
  return response.data;
}

export async function me() {
  const response = await http.get<UserResponse>("/v1/users/me");
  return response.data;
}

export async function refreshToken(refreshTokenValue: string) {
  const body = new URLSearchParams({
    grant_type: "refresh_token",
    refresh_token: refreshTokenValue,
  });
  const response = await http.post<TokenResponse>("/v1/oauth/token", body, {
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
  });
  return response.data;
}

export async function logoutCurrent() {
  await http.delete("/v1/sessions/current");
}

export async function listSessions(limit = 25) {
  const response = await http.get<PagedSessions>("/v1/sessions", { params: { limit } });
  return response.data;
}

export async function revokeSession(sessionId: string) {
  await http.delete(`/v1/sessions/${sessionId}`);
}
