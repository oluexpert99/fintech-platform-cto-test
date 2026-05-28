import { http } from "../../../shared/api/http";

export type Account = {
  id: string;
  ownerUserId: string;
  currency: string;
  type: "CHECKING" | "SAVINGS";
  label: string;
  balance: number;
  status: "ACTIVE" | "FROZEN" | "CLOSED";
  statusReason: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
};

type PagedResponse<T> = {
  data: T[];
  page: number;
  size: number;
  total: number;
};

export async function listAccounts() {
  const response = await http.get<PagedResponse<Account>>("/v1/accounts");
  return response.data;
}

export async function openAccount(payload: {
  currency: string;
  type: "CHECKING" | "SAVINGS";
  label: string;
}) {
  const response = await http.post<Account>("/v1/accounts", payload, {
    headers: { "Idempotency-Key": crypto.randomUUID() },
  });
  return response.data;
}

export async function getAccount(accountId: string) {
  const response = await http.get<Account>(`/v1/accounts/${accountId}`);
  return response.data;
}

export async function getBalance(accountId: string) {
  const response = await http.get<{ accountId: string; balance: number; currency: string; updatedAt: string }>(
    `/v1/accounts/${accountId}/balance`,
  );
  return response.data;
}

export async function patchAccount(accountId: string, payload: {
  label?: string;
  status?: "ACTIVE" | "FROZEN" | "CLOSED";
  reason?: string;
  approverId?: string;
}, ifMatch?: number) {
  const response = await http.patch<Account>(`/v1/accounts/${accountId}`, payload, {
    headers: {
      "Idempotency-Key": crypto.randomUUID(),
      ...(typeof ifMatch === "number" ? { "If-Match": ifMatch } : {}),
    },
  });
  return response.data;
}
