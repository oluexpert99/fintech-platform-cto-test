import { useQuery } from "@tanstack/react-query";
import { http } from "../api/http";
import { getAccessToken } from "../auth/tokenStore";

type ServiceStatus = "up" | "down";

type StatusMap = {
  gateway: ServiceStatus;
  auth: ServiceStatus;
  accounts: ServiceStatus;
  transactions: ServiceStatus;
  accounting: ServiceStatus;
};

async function probe(path: string): Promise<ServiceStatus> {
  try {
    await http.get(path);
    return "up";
  } catch (error: unknown) {
    const status = (error as { response?: { status?: number } })?.response?.status;
    // For protected endpoints, 401/403 still prove reachability.
    if (status === 401 || status === 403) {
      return "up";
    }
    return "down";
  }
}

async function fetchStatuses(): Promise<StatusMap> {
  const [gateway, auth, accounts, transactions, accounting] = await Promise.all([
    probe("/actuator/health"),
    probe("/v1/users/me"),
    probe("/v1/accounts?page=0&size=1"),
    probe("/v1/transactions?limit=1"),
    probe("/v1/chart-of-accounts"),
  ]);
  return { gateway, auth, accounts, transactions, accounting };
}

function Dot({ status }: { status: ServiceStatus }) {
  return (
    <span
      aria-label={status}
      style={{
        width: 10,
        height: 10,
        borderRadius: "50%",
        display: "inline-block",
        backgroundColor: status === "up" ? "#16a34a" : "#dc2626",
      }}
    />
  );
}

export function StatusBanner() {
  const query = useQuery({
    queryKey: ["service-status"],
    queryFn: fetchStatuses,
    refetchInterval: 15000,
  });

  const tokenPresent = getAccessToken().length > 0;
  const statuses: StatusMap | undefined = query.data;

  return (
    <div className="status-banner">
      <span className="status-chip">
        <Dot status={tokenPresent ? "up" : "down"} /> token
      </span>
      <span className="status-chip">
        <Dot status={statuses?.gateway ?? "down"} /> gateway
      </span>
      <span className="status-chip">
        <Dot status={statuses?.auth ?? "down"} /> auth
      </span>
      <span className="status-chip">
        <Dot status={statuses?.accounts ?? "down"} /> accounts
      </span>
      <span className="status-chip">
        <Dot status={statuses?.transactions ?? "down"} /> transactions
      </span>
      <span className="status-chip">
        <Dot status={statuses?.accounting ?? "down"} /> accounting
      </span>
      {query.isFetching && <span className="status-note">checking...</span>}
    </div>
  );
}
