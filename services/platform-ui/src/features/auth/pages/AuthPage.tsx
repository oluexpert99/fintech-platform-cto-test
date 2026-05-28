import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import type { FormEvent } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import {
  listSessions,
  login,
  logoutCurrent,
  me,
  registerUser,
  revokeSession,
} from "../api/authApi";
import { clearTokens, setAccessToken, setRefreshToken } from "../../../shared/auth/tokenStore";
import { useIsAuthenticated } from "../../../shared/auth/useAuth";
import { ErrorBox } from "../../../shared/ui/ErrorBox";
import { Field } from "../../../shared/ui/Field";
import { JsonDisclosure } from "../../../shared/ui/JsonDisclosure";
import { useToast } from "../../../shared/ui/Toast";

export function AuthPage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const location = useLocation();
  const { show } = useToast();
  const authed = useIsAuthenticated();

  const [registerForm, setRegisterForm] = useState({
    email: "",
    password: "",
    fullName: "",
    phone: "",
  });
  const [loginForm, setLoginForm] = useState({
    email: "",
    password: "",
    otp: "",
    deviceLabel: "platform-ui",
  });

  const meQuery = useQuery({
    queryKey: ["me"],
    queryFn: me,
    enabled: authed,
    retry: false,
  });
  const sessionsQuery = useQuery({
    queryKey: ["sessions"],
    queryFn: () => listSessions(25),
    enabled: authed,
    retry: false,
  });

  const registerMutation = useMutation({
    mutationFn: registerUser,
    onSuccess: () => show("success", "Account registered. You can sign in now."),
  });

  const loginMutation = useMutation({
    mutationFn: login,
    onSuccess: (data) => {
      setAccessToken(data.accessToken);
      setRefreshToken(data.refreshToken);
      queryClient.invalidateQueries({ queryKey: ["me"] });
      queryClient.invalidateQueries({ queryKey: ["sessions"] });
      show("success", "Signed in.");
      const from = (location.state as { from?: string } | null)?.from;
      navigate(from && from !== "/auth" ? from : "/accounts", { replace: true });
    },
  });

  const logoutMutation = useMutation({
    mutationFn: logoutCurrent,
    onSuccess: () => {
      clearTokens();
      queryClient.clear();
      show("info", "Signed out.");
    },
  });

  const revokeMutation = useMutation({
    mutationFn: revokeSession,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["sessions"] });
      show("info", "Session revoked.");
    },
  });

  const submitRegister = (event: FormEvent) => {
    event.preventDefault();
    registerMutation.mutate(registerForm);
  };
  const submitLogin = (event: FormEvent) => {
    event.preventDefault();
    loginMutation.mutate({
      email: loginForm.email,
      password: loginForm.password,
      otp: loginForm.otp || undefined,
      deviceLabel: loginForm.deviceLabel || undefined,
    });
  };

  return (
    <>
      {!authed && (
        <section className="card">
          <h2>Sign in</h2>
          <form className="form-grid" onSubmit={submitLogin}>
            <Field
              label="Email"
              type="email"
              autoComplete="email"
              required
              value={loginForm.email}
              onChange={(e) => setLoginForm((f) => ({ ...f, email: e.target.value }))}
            />
            <Field
              label="Password"
              type="password"
              autoComplete="current-password"
              required
              value={loginForm.password}
              onChange={(e) => setLoginForm((f) => ({ ...f, password: e.target.value }))}
            />
            <Field
              label="One-time code"
              hint="Optional. Required when MFA is enabled."
              inputMode="numeric"
              autoComplete="one-time-code"
              value={loginForm.otp}
              onChange={(e) => setLoginForm((f) => ({ ...f, otp: e.target.value }))}
            />
            <Field
              label="Device label"
              value={loginForm.deviceLabel}
              onChange={(e) => setLoginForm((f) => ({ ...f, deviceLabel: e.target.value }))}
            />
            <div className="form-grid__actions">
              <button type="submit" className="btn btn--primary" disabled={loginMutation.isPending}>
                {loginMutation.isPending ? "Signing in…" : "Sign in"}
              </button>
            </div>
          </form>
          {loginMutation.isError && <ErrorBox error={loginMutation.error} title="Sign-in failed" />}
        </section>
      )}

      {!authed && (
        <section className="card">
          <h2>Create account</h2>
          <form className="form-grid" onSubmit={submitRegister}>
            <Field
              label="Email"
              type="email"
              autoComplete="email"
              required
              value={registerForm.email}
              onChange={(e) => setRegisterForm((f) => ({ ...f, email: e.target.value }))}
            />
            <Field
              label="Password"
              type="password"
              autoComplete="new-password"
              minLength={12}
              required
              hint="At least 12 characters."
              value={registerForm.password}
              onChange={(e) => setRegisterForm((f) => ({ ...f, password: e.target.value }))}
            />
            <Field
              label="Full name"
              autoComplete="name"
              required
              value={registerForm.fullName}
              onChange={(e) => setRegisterForm((f) => ({ ...f, fullName: e.target.value }))}
            />
            <Field
              label="Phone"
              type="tel"
              autoComplete="tel"
              placeholder="+234…"
              value={registerForm.phone}
              onChange={(e) => setRegisterForm((f) => ({ ...f, phone: e.target.value }))}
            />
            <div className="form-grid__actions">
              <button type="submit" className="btn btn--primary" disabled={registerMutation.isPending}>
                {registerMutation.isPending ? "Creating…" : "Create account"}
              </button>
            </div>
          </form>
          {registerMutation.isError && <ErrorBox error={registerMutation.error} title="Registration failed" />}
        </section>
      )}

      {authed && (
        <section className="card">
          <div className="card__header">
            <h2>Signed in</h2>
            <button
              type="button"
              className="btn btn--ghost"
              onClick={() => logoutMutation.mutate()}
              disabled={logoutMutation.isPending}
            >
              Sign out
            </button>
          </div>
          {meQuery.isLoading && <p>Loading…</p>}
          {meQuery.isError && <ErrorBox error={meQuery.error} title="Could not load profile" />}
          {meQuery.data && (
            <>
              <dl className="kv">
                <dt>Email</dt><dd>{meQuery.data.email}</dd>
                <dt>Name</dt><dd>{meQuery.data.fullName}</dd>
                <dt>Phone</dt><dd>{meQuery.data.phone || "—"}</dd>
                <dt>Status</dt><dd>{meQuery.data.status}</dd>
                <dt>KYC level</dt><dd>{meQuery.data.kycLevel}</dd>
                <dt>MFA</dt><dd>{meQuery.data.mfaEnabled ? "Enabled" : "Disabled"}</dd>
              </dl>
              <JsonDisclosure data={meQuery.data} />
            </>
          )}
        </section>
      )}

      {authed && (
        <section className="card">
          <h2>Active sessions</h2>
          {sessionsQuery.isLoading && <p>Loading…</p>}
          {sessionsQuery.isError && <ErrorBox error={sessionsQuery.error} title="Could not load sessions" />}
          {sessionsQuery.data && (
            <table>
              <thead>
                <tr>
                  <th>Session</th>
                  <th>Device</th>
                  <th>Current</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {sessionsQuery.data.data.map((session) => (
                  <tr key={session.sessionId}>
                    <td><code>{session.sessionId}</code></td>
                    <td>{session.deviceLabel || "—"}</td>
                    <td>{session.current ? "Yes" : "No"}</td>
                    <td>
                      {!session.current && (
                        <button
                          type="button"
                          className="btn btn--ghost"
                          onClick={() => revokeMutation.mutate(session.sessionId)}
                          disabled={revokeMutation.isPending}
                        >
                          Revoke
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}
    </>
  );
}
