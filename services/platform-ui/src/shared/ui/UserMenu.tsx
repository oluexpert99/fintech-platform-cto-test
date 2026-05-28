import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { logoutCurrent, me } from "../../features/auth/api/authApi";
import { clearTokens } from "../auth/tokenStore";
import { useIsAuthenticated } from "../auth/useAuth";
import { useToast } from "./Toast";

export function UserMenu() {
  const authed = useIsAuthenticated();
  const queryClient = useQueryClient();
  const { show } = useToast();

  const meQuery = useQuery({ queryKey: ["me"], queryFn: me, enabled: authed, retry: false });
  const logoutMutation = useMutation({
    mutationFn: logoutCurrent,
    onSuccess: () => {
      clearTokens();
      queryClient.clear();
      show("info", "Signed out.");
    },
    onError: () => {
      clearTokens();
      queryClient.clear();
    },
  });

  if (!authed) {
    return (
      <Link to="/auth" className="btn btn--primary btn--sm">Sign in</Link>
    );
  }

  return (
    <div className="user-menu">
      <span className="user-menu__email">{meQuery.data?.email ?? "signed in"}</span>
      <button
        type="button"
        className="btn btn--ghost btn--sm"
        onClick={() => logoutMutation.mutate()}
        disabled={logoutMutation.isPending}
      >
        Sign out
      </button>
    </div>
  );
}
