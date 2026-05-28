import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import type { FormEvent } from "react";
import {
  createReversal,
  createTransfer,
  getTransaction,
  listTransactions,
} from "../api/transactionsApi";
import { ErrorBox } from "../../../shared/ui/ErrorBox";
import { Field } from "../../../shared/ui/Field";
import { ConfirmDialog } from "../../../shared/ui/ConfirmDialog";
import { JsonDisclosure } from "../../../shared/ui/JsonDisclosure";
import { EmptyState } from "../../../shared/ui/EmptyState";
import { useToast } from "../../../shared/ui/Toast";
import { formatMoney, parseAmountToMinor } from "../../../shared/money";

export function TransactionsPage() {
  const queryClient = useQueryClient();
  const { show } = useToast();

  const [transferForm, setTransferForm] = useState({
    sourceAccount: "",
    destinationAccount: "",
    amount: "",
    currency: "USD",
    description: "",
  });
  const [reversalForm, setReversalForm] = useState({
    correctsTransactionId: "",
    reason: "",
    approverId: "",
  });
  const [lookupId, setLookupId] = useState("");
  const [pendingTransfer, setPendingTransfer] = useState<null | {
    minor: number;
    sourceAccount: string;
    destinationAccount: string;
    currency: string;
    description?: string;
  }>(null);
  const [pendingReversal, setPendingReversal] = useState<null | {
    correctsTransactionId: string;
    reason: string;
    approverId?: string;
  }>(null);
  const [amountError, setAmountError] = useState<string | null>(null);

  const listQuery = useQuery({ queryKey: ["transactions"], queryFn: () => listTransactions(undefined, 20) });
  const lookupQuery = useQuery({
    queryKey: ["transaction", lookupId],
    queryFn: () => getTransaction(lookupId),
    enabled: lookupId.length > 0,
  });

  const transferMutation = useMutation({
    mutationFn: createTransfer,
    onSuccess: (tx) => {
      show("success", `Transfer ${tx.transactionId} created.`);
      setLookupId(tx.transactionId);
      setPendingTransfer(null);
      setTransferForm((f) => ({ ...f, amount: "", description: "" }));
      queryClient.invalidateQueries({ queryKey: ["transactions"] });
      queryClient.invalidateQueries({ queryKey: ["accounts"] });
    },
  });
  const reversalMutation = useMutation({
    mutationFn: createReversal,
    onSuccess: (tx) => {
      show("success", `Reversal ${tx.transactionId} created.`);
      setLookupId(tx.transactionId);
      setPendingReversal(null);
      queryClient.invalidateQueries({ queryKey: ["transactions"] });
    },
  });

  const reviewTransfer = (event: FormEvent) => {
    event.preventDefault();
    setAmountError(null);
    try {
      const minor = parseAmountToMinor(transferForm.amount, transferForm.currency);
      setPendingTransfer({
        minor,
        sourceAccount: transferForm.sourceAccount.trim(),
        destinationAccount: transferForm.destinationAccount.trim(),
        currency: transferForm.currency.toUpperCase(),
        description: transferForm.description.trim() || undefined,
      });
    } catch (err) {
      setAmountError(err instanceof Error ? err.message : "Invalid amount");
    }
  };

  const reviewReversal = (event: FormEvent) => {
    event.preventDefault();
    setPendingReversal({
      correctsTransactionId: reversalForm.correctsTransactionId.trim(),
      reason: reversalForm.reason.trim(),
      approverId: reversalForm.approverId.trim() || undefined,
    });
  };

  return (
    <>
      <section className="card">
        <h2>New transfer</h2>
        <form className="form-grid" onSubmit={reviewTransfer}>
          <Field
            label="Source account"
            required
            placeholder="ACC-…"
            value={transferForm.sourceAccount}
            onChange={(e) => setTransferForm((f) => ({ ...f, sourceAccount: e.target.value }))}
          />
          <Field
            label="Destination account"
            required
            placeholder="ACC-…"
            value={transferForm.destinationAccount}
            onChange={(e) => setTransferForm((f) => ({ ...f, destinationAccount: e.target.value }))}
          />
          <Field
            label="Amount"
            required
            inputMode="decimal"
            placeholder="0.00"
            value={transferForm.amount}
            onChange={(e) => setTransferForm((f) => ({ ...f, amount: e.target.value }))}
            hint={amountError ?? undefined}
          />
          <Field
            label="Currency"
            required
            maxLength={3}
            value={transferForm.currency}
            onChange={(e) => setTransferForm((f) => ({ ...f, currency: e.target.value.toUpperCase() }))}
          />
          <Field
            label="Description"
            value={transferForm.description}
            onChange={(e) => setTransferForm((f) => ({ ...f, description: e.target.value }))}
          />
          <div className="form-grid__actions">
            <button type="submit" className="btn btn--primary">Review transfer…</button>
          </div>
        </form>
        {amountError && <ErrorBox title="Check amount" message={amountError} />}
        {transferMutation.isError && <ErrorBox error={transferMutation.error} title="Transfer failed" />}
      </section>

      <section className="card">
        <h2>Reversal</h2>
        <form className="form-grid" onSubmit={reviewReversal}>
          <Field
            label="Corrects transaction"
            required
            placeholder="TX-…"
            value={reversalForm.correctsTransactionId}
            onChange={(e) => setReversalForm((f) => ({ ...f, correctsTransactionId: e.target.value }))}
          />
          <Field
            label="Reason"
            required
            value={reversalForm.reason}
            onChange={(e) => setReversalForm((f) => ({ ...f, reason: e.target.value }))}
          />
          <Field
            label="Approver ID"
            value={reversalForm.approverId}
            onChange={(e) => setReversalForm((f) => ({ ...f, approverId: e.target.value }))}
          />
          <div className="form-grid__actions">
            <button type="submit" className="btn btn--primary">Review reversal…</button>
          </div>
        </form>
        {reversalMutation.isError && <ErrorBox error={reversalMutation.error} title="Reversal failed" />}
      </section>

      <section className="card">
        <h2>Lookup transaction</h2>
        <div className="row">
          <Field
            label="Transaction ID"
            placeholder="TX-…"
            value={lookupId}
            onChange={(e) => setLookupId(e.target.value)}
          />
        </div>
        {lookupQuery.isError && <ErrorBox error={lookupQuery.error} title="Transaction lookup failed" />}
        {lookupQuery.data && (
          <>
            <dl className="kv">
              <dt>ID</dt><dd><code>{lookupQuery.data.transactionId}</code></dd>
              <dt>Type</dt><dd>{lookupQuery.data.type}</dd>
              <dt>Status</dt><dd>{lookupQuery.data.status}</dd>
              <dt>Source</dt><dd>{lookupQuery.data.sourceAccount || "—"}</dd>
              <dt>Destination</dt><dd>{lookupQuery.data.destinationAccount || "—"}</dd>
              <dt>Amount</dt>
              <dd className="num">{formatMoney(lookupQuery.data.amount, lookupQuery.data.currency)}</dd>
              <dt>Description</dt><dd>{lookupQuery.data.description || "—"}</dd>
              <dt>Created</dt><dd>{lookupQuery.data.createdAt || "—"}</dd>
              <dt>Completed</dt><dd>{lookupQuery.data.completedAt || "—"}</dd>
              {lookupQuery.data.correctsTransactionId && (
                <>
                  <dt>Corrects</dt>
                  <dd>
                    <button
                      type="button"
                      className="btn btn--link"
                      onClick={() => setLookupId(lookupQuery.data!.correctsTransactionId!)}
                    >
                      <code>{lookupQuery.data.correctsTransactionId}</code>
                    </button>
                  </dd>
                </>
              )}
            </dl>
            <JsonDisclosure data={lookupQuery.data} />
          </>
        )}
      </section>

      <section className="card">
        <h2>Recent transactions</h2>
        {listQuery.isLoading && <p>Loading…</p>}
        {listQuery.isError && <ErrorBox error={listQuery.error} title="Could not load transactions" />}
        {listQuery.data && listQuery.data.data.length === 0 && (
          <EmptyState title="No transactions yet" />
        )}
        {listQuery.data && listQuery.data.data.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>ID</th>
                <th>Type</th>
                <th>Status</th>
                <th className="num">Amount</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {listQuery.data.data.map((tx) => (
                <tr key={tx.transactionId}>
                  <td><code>{tx.transactionId}</code></td>
                  <td>{tx.type}</td>
                  <td><span className={`pill pill--${tx.status.toLowerCase()}`}>{tx.status}</span></td>
                  <td className="num">{formatMoney(tx.amount, tx.currency)}</td>
                  <td>
                    <button type="button" className="btn btn--ghost" onClick={() => setLookupId(tx.transactionId)}>
                      View
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <ConfirmDialog
        open={pendingTransfer !== null}
        title="Confirm transfer"
        confirmLabel="Send transfer"
        busy={transferMutation.isPending}
        onCancel={() => setPendingTransfer(null)}
        onConfirm={() => {
          if (!pendingTransfer) return;
          transferMutation.mutate({
            sourceAccount: pendingTransfer.sourceAccount,
            destinationAccount: pendingTransfer.destinationAccount,
            amount: pendingTransfer.minor,
            currency: pendingTransfer.currency,
            description: pendingTransfer.description,
          });
        }}
      >
        {pendingTransfer && (
          <dl className="kv">
            <dt>From</dt><dd><code>{pendingTransfer.sourceAccount}</code></dd>
            <dt>To</dt><dd><code>{pendingTransfer.destinationAccount}</code></dd>
            <dt>Amount</dt>
            <dd className="num strong">{formatMoney(pendingTransfer.minor, pendingTransfer.currency)}</dd>
            {pendingTransfer.description && (<>
              <dt>Description</dt><dd>{pendingTransfer.description}</dd>
            </>)}
          </dl>
        )}
      </ConfirmDialog>

      <ConfirmDialog
        open={pendingReversal !== null}
        title="Confirm reversal"
        confirmLabel="Send reversal"
        busy={reversalMutation.isPending}
        onCancel={() => setPendingReversal(null)}
        onConfirm={() => {
          if (!pendingReversal) return;
          reversalMutation.mutate(pendingReversal);
        }}
      >
        {pendingReversal && (
          <dl className="kv">
            <dt>Corrects</dt><dd><code>{pendingReversal.correctsTransactionId}</code></dd>
            <dt>Reason</dt><dd>{pendingReversal.reason}</dd>
            {pendingReversal.approverId && (<>
              <dt>Approver</dt><dd>{pendingReversal.approverId}</dd>
            </>)}
          </dl>
        )}
      </ConfirmDialog>
    </>
  );
}
