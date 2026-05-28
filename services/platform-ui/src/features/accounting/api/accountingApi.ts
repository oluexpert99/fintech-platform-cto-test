import { http } from "../../../shared/api/http";

export type JournalEntry = {
  journalEntryId: string;
  transactionId: string;
  account: string;
  coaAccount: string;
  side: string;
  amount: number;
  currency: string;
  postedAt: string;
};

export type ChartOfAccount = {
  id: string;
  name: string;
  type: string;
  normalSide: string;
  parentId: string | null;
  system: boolean;
  currency: string | null;
  createdAt: string;
};

export type TrialBalance = {
  asOf: string;
  currency: string;
  totals: { debits: number; credits: number; delta: number };
  byType: Record<string, { debits: number; credits: number; net: number }>;
};

type PagedResponse<T> = {
  data: T[];
  page: { nextCursor: string | null; hasMore: boolean; limit: number };
};

export async function listJournalEntries(filters?: {
  account?: string;
  transactionId?: string;
  limit?: number;
}) {
  const response = await http.get<PagedResponse<JournalEntry>>("/v1/journal-entries", { params: filters });
  return response.data;
}

export async function listChartOfAccounts() {
  const response = await http.get<ChartOfAccount[]>("/v1/chart-of-accounts");
  return response.data;
}

export async function getTrialBalance(params?: { asOf?: string; currency?: string }) {
  const response = await http.get<TrialBalance>("/v1/reports/trial-balance", { params });
  return response.data;
}
