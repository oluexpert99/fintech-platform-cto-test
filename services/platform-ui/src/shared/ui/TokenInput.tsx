import { useState } from "react";
import { clearTokens, getAccessToken, getRefreshToken, setAccessToken, setRefreshToken } from "../auth/tokenStore";
import { Field } from "./Field";
import { useToast } from "./Toast";

export function TokenInput() {
  const [access, setAccess] = useState(() => getAccessToken());
  const [refresh, setRefresh] = useState(() => getRefreshToken());
  const { show } = useToast();

  const save = () => {
    setAccessToken(access.trim());
    setRefreshToken(refresh.trim());
    show("success", "Tokens saved.");
  };

  const clear = () => {
    clearTokens();
    setAccess("");
    setRefresh("");
    show("info", "Tokens cleared.");
  };

  return (
    <div className="form-grid">
      <Field
        label="Access token"
        type="password"
        autoComplete="off"
        value={access}
        onChange={(e) => setAccess(e.target.value)}
      />
      <Field
        label="Refresh token"
        type="password"
        autoComplete="off"
        value={refresh}
        onChange={(e) => setRefresh(e.target.value)}
      />
      <div className="form-grid__actions">
        <button type="button" className="btn btn--primary" onClick={save}>Save</button>
        <button type="button" className="btn btn--ghost" onClick={clear}>Clear</button>
      </div>
    </div>
  );
}
