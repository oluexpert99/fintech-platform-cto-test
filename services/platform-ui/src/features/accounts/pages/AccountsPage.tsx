import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { getAccount, getBalance, listAccounts, openAccount, patchAccount } from "../api/accountsApi";
import { ErrorBox } from "../../../shared/ui/ErrorBox";
import { Field } from "../../../shared/ui/Field";
import { JsonDisclosure } from "../../../shared/ui/JsonDisclosure";
import { EmptyState } from "../../../shared/ui/EmptyState";
import { useToast } from "../../../shared/ui/Toast";
import { formatMoney } from "../../../shared/money";

type AccountStatus = "ACTIVE" | "FROZEN" | "CLOSED";

export function AccountsPage() {
  const queryClient = useQueryClient();
  const { show } = useToast();

  const [openForm, setOpenForm] = useState<{ label: string; type: "CHECKING" | "SAVINGS"; currency: string }>({
    label: "Main wallet",
    type: "CHECKING",
    currency: "USD",
  });
  const [selectedAccountId, setSelectedAccountId] = useState("");
  const [patchForm, setPatchForm] = useState<{ label: string; status: "" | AccountStatus; reason: string; approverId: string }>(
    { label: "", status: "", reason: "", approverId: "" },
  );

  const accountsQuery = useQuery({ queryKey: ["accounts"], queryFn: listAccounts });
  const accountQuery = useQuery({
    queryKey: ["account", selectedAccountId],
    queryFn: () => getAccount(selectedAccountId),
    enabled: selectedAccountId.length > 0,
  });
  const balanceQuery = useQuery({
    queryKey: ["balance", selectedAccountId],
    queryFn: () => getBalance(selectedAccountId),
    enabled: selectedAccountId.length > 0,
  });

  useEffect(() => {
    if (accountQuery.data) {
      setPatchForm({ label: accountQuery.data.label, status: "", reason: "", approverId: "" });
    }
  }, [accountQuery.data]);

  const openMutation = useMutation({
    mutationFn: openAccount,
    onSuccess: (account) => {
      queryClient.invalidateQueries({ queryKey: ["accounts"] });
      setSelectedAccountId(account.id);
      show("success", `Opened account ${account.id}.`);
    },
  });

  const patchMutation = useMutation({
    mutationFn: (args: {
      accountId: string;
      payload: { label?: string; status?: AccountStatus; reason?: string; approverId?: string };
      ifMatch?: number;
    }) => patchAccount(args.accountId, args.payload, args.ifMatch),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["accounts"] });
      queryClient.invalidateQueries({ queryKey: ["account", selectedAccountId] });
      queryClient.invalidateQueries({ queryKey: ["balance", selectedAccountId] });
      show("success", "Account updated.");
    },
  });

  const onSubmitOpen = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    openMutation.mutate({ ...openForm, currency: openForm.currency.toUpperCase() });
  };

  const onSubmitPatch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!selectedAccountId) return;
    patchMutation.mutate({
      accountId: selectedAccountId,
      payload: {
        label: patchForm.label || undefined,
        status: (patchForm.status || undefined) as AccountStatus | undefined,
        reason: patchForm.reason || undefined,
        approverId: patchForm.approverId || undefined,
      },
      ifMatch: accountQuery.data?.version,
    });
  };

  return (
    <>
      <section className="card">
        <h2>Open account</h2>
        <form className="form-grid" onSubmit={onSubmitOpen}>
          <Field
            label="Account label"
            required
            value={openForm.label}
            onChange={(e) => setOpenForm((f) => ({ ...f, label: e.target.value }))}
          />
          <Field label="Account type">
            <select
              value={openForm.type}
              onChange={(e) => setOpenForm((f) => ({ ...f, type: e.target.value as "CHECKING" | "SAVINGS" }))}
            >
              <option value="CHECKING">Checking</option>
              <option value="SAVINGS">Savings</option>
            </select>
          </Field>
          <Field
            label="Currency"
            required
            maxLength={3}
            value={openForm.currency}
            onChange={(e) => setOpenForm((f) => ({ ...f, currency: e.target.value.toUpperCase() }))}
          />
          <div className="form-grid__actions">
            <button type="submit" className="btn btn--primary" disabled={openMutation.isPending}>
              {openMutation.isPending ? "Opening…" : "Open account"}
            </button>
          </div>
        </form>
        {openMutation.isError && <ErrorBox error={openMutation.error} title="Open failed" />}
      </section>

      <section className="card">
        <div className="card__header">
          <h2>My accounts</h2>
        </div>
        {accountsQuery.isLoading && <p>Loading…</p>}
        {accountsQuery.isError && <ErrorBox error={accountsQuery.error} title="Failed to load accounts" />}
        {accountsQuery.data && accountsQuery.data.data.length === 0 && (
          <EmptyState title="No accounts yet">Open your first account above.</EmptyState>
        )}
        {accountsQuery.data && accountsQuery.data.data.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>ID</th>
                <th>Label</th>
                <th>Status</th>
                <th>Balance</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {accountsQuery.data.data.map((account) => (
                <tr key={account.id} className={selectedAccountId === account.id ? "row--selected" : ""}>
                  <td><code>{account.id}</code></td>
                  <td>{account.label}</td>
                  <td><span className={`pill pill--${account.status.toLowerCase()}`}>{account.status}</span></td>
                  <td className="num">{formatMoney(account.balance, account.currency)}</td>
                  <td>
                    <button type="button" className="btn btn--ghost" onClick={() => setSelectedAccountId(account.id)}>
                      Manage
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {selectedAccountId && (
        <section className="card">
          <div className="card__header">
            <h2>Manage account</h2>
            <code className="muted">{selectedAccountId}</code>
          </div>
          {accountQuery.isError && <ErrorBox error={accountQuery.error} title="Could not load account" />}
          {accountQuery.data && (
            <dl className="kv">
              <dt>Label</dt><dd>{accountQuery.data.label}</dd>
              <dt>Type</dt><dd>{accountQuery.data.type}</dd>
              <dt>Status</dt><dd>{accountQuery.data.status}</dd>
              <dt>Currency</dt><dd>{accountQuery.data.currency}</dd>
              <dt>Balance</dt>
              <dd className="num">
                {balanceQuery.data
                  ? formatMoney(balanceQuery.data.balance, balanceQuery.data.currency)
                  : formatMoney(accountQuery.data.balance, accountQuery.data.currency)}
              </dd>
              <dt>Version</dt><dd>{accountQuery.data.version}</dd>
            </dl>
          )}
          <form className="form-grid" onSubmit={onSubmitPatch}>
            <Field
              label="New label"
              value={patchForm.label}
              onChange={(e) => setPatchForm((f) => ({ ...f, label: e.target.value }))}
            />
            <Field label="Status">
              <select
                value={patchForm.status}
                onChange={(e) => setPatchForm((f) => ({ ...f, status: e.target.value as "" | AccountStatus }))}
              >
                <option value="">No change</option>
                <option value="ACTIVE">Active</option>
                <option value="FROZEN">Frozen</option>
                <option value="CLOSED">Closed</option>
              </select>
            </Field>
            <Field
              label="Reason"
              hint="Required when changing status."
              value={patchForm.reason}
              onChange={(e) => setPatchForm((f) => ({ ...f, reason: e.target.value }))}
            />
            <Field
              label="Approver ID"
              value={patchForm.approverId}
              onChange={(e) => setPatchForm((f) => ({ ...f, approverId: e.target.value }))}
            />
            <div className="form-grid__actions">
              <button
                type="submit"
                className="btn btn--primary"
                disabled={patchMutation.isPending || !accountQuery.data}
              >
                {patchMutation.isPending ? "Saving…" : "Save changes"}
              </button>
              <button type="button" className="btn btn--ghost" onClick={() => setSelectedAccountId("")}>
                Close
              </button>
            </div>
          </form>
          {patchMutation.isError && <ErrorBox error={patchMutation.error} title="Update failed" />}
          {accountQuery.data && <JsonDisclosure data={accountQuery.data} />}
        </section>
      )}
    </>
  );
}
