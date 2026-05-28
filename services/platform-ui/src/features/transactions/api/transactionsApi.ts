import { http } from "../../../shared/api/http";

export type TransactionResponse = {
  transactionId: string;
  type: "TRANSFER" | "REVERSAL";
  status: string;
  sourceAccount?: string;
  destinationAccount?: string;
  amount?: number;
  currency?: string;
  description?: string;
  journalLineIds?: string[];
  correctsTransactionId?: string;
  reason?: string;
  approverId?: string;
  createdAt?: string;
  completedAt?: string;
};

type PagedResponse<T> = {
  data: T[];
  page: { nextCursor: string | null; hasMore: boolean; limit: number };
};

export async function createTransfer(payload: {
  sourceAccount: string;
  destinationAccount: string;
  amount: number;
  currency: string;
  description?: string;
}) {
  const response = await http.post<TransactionResponse>(
    "/v1/transactions",
    { type: "TRANSFER", ...payload },
    { headers: { "Idempotency-Key": crypto.randomUUID() } },
  );
  return response.data;
}

export async function createReversal(payload: {
  correctsTransactionId: string;
  reason: string;
  approverId?: string;
}) {
  const response = await http.post<TransactionResponse>(
    "/v1/transactions",
    { type: "REVERSAL", ...payload },
    { headers: { "Idempotency-Key": crypto.randomUUID() } },
  );
  return response.data;
}

export async function getTransaction(transactionId: string) {
  const response = await http.get<TransactionResponse>(`/v1/transactions/${transactionId}`);
  return response.data;
}

export async function listTransactions(cursor?: string, limit = 20) {
  const response = await http.get<PagedResponse<TransactionResponse>>("/v1/transactions", {
    params: { cursor, limit },
  });
  return response.data;
}
