import axios, { AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from "axios";
import { clearTokens, getAccessToken, getRefreshToken, setAccessToken, setRefreshToken } from "../auth/tokenStore";

const baseURL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

export const http = axios.create({
  baseURL,
  timeout: 10000,
});

type RetryConfig = InternalAxiosRequestConfig & { _retried?: boolean };

http.interceptors.request.use((config) => {
  const token = getAccessToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

let refreshInflight: Promise<string | null> | null = null;

async function performRefresh(): Promise<string | null> {
  const refresh = getRefreshToken();
  if (!refresh) return null;
  try {
    const body = new URLSearchParams({ grant_type: "refresh_token", refresh_token: refresh });
    const response = await axios.post<{ access_token: string; refresh_token: string }>(
      `${baseURL}/v1/oauth/token`,
      body,
      { headers: { "Content-Type": "application/x-www-form-urlencoded" }, timeout: 10000 },
    );
    setAccessToken(response.data.access_token);
    if (response.data.refresh_token) setRefreshToken(response.data.refresh_token);
    return response.data.access_token;
  } catch {
    clearTokens();
    return null;
  }
}

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config as RetryConfig | undefined;
    const status = error.response?.status;
    const url = original?.url ?? "";
    const isAuthEndpoint = url.includes("/oauth/token") || url.includes("/v1/sessions");

    if (status === 401 && original && !original._retried && !isAuthEndpoint && getRefreshToken()) {
      original._retried = true;
      if (!refreshInflight) {
        refreshInflight = performRefresh().finally(() => {
          refreshInflight = null;
        });
      }
      const newToken = await refreshInflight;
      if (newToken) {
        original.headers = original.headers ?? {};
        (original.headers as Record<string, string>).Authorization = `Bearer ${newToken}`;
        return http.request(original as AxiosRequestConfig);
      }
    }

    return Promise.reject(error);
  },
);
