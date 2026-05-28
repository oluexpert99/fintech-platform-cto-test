import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import type { FormEvent } from "react";
import { getTrialBalance, listChartOfAccounts, listJournalEntries } from "../api/accountingApi";
import { ErrorBox } from "../../../shared/ui/ErrorBox";
import { Field } from "../../../shared/ui/Field";
import { JsonDisclosure } from "../../../shared/ui/JsonDisclosure";
import { EmptyState } from "../../../shared/ui/EmptyState";
import { formatMoney } from "../../../shared/money";

export function AccountingPage() {
  const [journalDraft, setJournalDraft] = useState({ account: "", transactionId: "", limit: 20 });
  const [journalFilter, setJournalFilter] = useState(journalDraft);
  const [tbDraft, setTbDraft] = useState({ asOf: "", currency: "" });
  const [tbFilter, setTbFilter] = useState(tbDraft);

  const coaQuery = useQuery({ queryKey: ["coa"], queryFn: listChartOfAccounts });
  const journalQuery = useQuery({
    queryKey: ["journal", journalFilter.account, journalFilter.transactionId, journalFilter.limit],
    queryFn: () =>
      listJournalEntries({
        account: journalFilter.account || undefined,
        transactionId: journalFilter.transactionId || undefined,
        limit: journalFilter.limit,
      }),
  });
  const tbQuery = useQuery({
    queryKey: ["trial-balance", tbFilter.asOf, tbFilter.currency],
    queryFn: () =>
      getTrialBalance({
        asOf: tbFilter.asOf || undefined,
        currency: tbFilter.currency || undefined,
      }),
  });

  const runTb = (event: FormEvent) => {
    event.preventDefault();
    setTbFilter(tbDraft);
  };
  const runJournal = (event: FormEvent) => {
    event.preventDefault();
    setJournalFilter(journalDraft);
  };

  return (
    <>
      <section className="card">
        <h2>Trial balance</h2>
        <form className="form-grid" onSubmit={runTb}>
          <Field
            label="As of"
            type="datetime-local"
            value={tbDraft.asOf}
            onChange={(e) => setTbDraft((v) => ({ ...v, asOf: e.target.value }))}
            hint="Leave empty for now."
          />
          <Field
            label="Currency"
            maxLength={3}
            value={tbDraft.currency}
            onChange={(e) => setTbDraft((v) => ({ ...v, currency: e.target.value.toUpperCase() }))}
          />
          <div className="form-grid__actions">
            <button type="submit" className="btn btn--primary">Run report</button>
          </div>
        </form>
        {tbQuery.isError && <ErrorBox error={tbQuery.error} title="Trial balance query failed" />}
        {tbQuery.data && (
          <>
            <dl className="kv">
              <dt>As of</dt><dd>{tbQuery.data.asOf}</dd>
              <dt>Currency</dt><dd>{tbQuery.data.currency}</dd>
              <dt>Debits</dt><dd className="num">{formatMoney(tbQuery.data.totals.debits, tbQuery.data.currency)}</dd>
              <dt>Credits</dt><dd className="num">{formatMoney(tbQuery.data.totals.credits, tbQuery.data.currency)}</dd>
              <dt>Delta</dt>
              <dd className={`num ${tbQuery.data.totals.delta === 0 ? "ok" : "warn"}`}>
                {formatMoney(tbQuery.data.totals.delta, tbQuery.data.currency)}
              </dd>
            </dl>
            <table>
              <thead>
                <tr>
                  <th>Type</th>
                  <th className="num">Debits</th>
                  <th className="num">Credits</th>
                  <th className="num">Net</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(tbQuery.data.byType).map(([type, row]) => (
                  <tr key={type}>
                    <td>{type}</td>
                    <td className="num">{formatMoney(row.debits, tbQuery.data!.currency)}</td>
                    <td className="num">{formatMoney(row.credits, tbQuery.data!.currency)}</td>
                    <td className="num">{formatMoney(row.net, tbQuery.data!.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <JsonDisclosure data={tbQuery.data} />
          </>
        )}
      </section>

      <section className="card">
        <h2>Journal entries</h2>
        <form className="form-grid" onSubmit={runJournal}>
          <Field
            label="Account"
            value={journalDraft.account}
            onChange={(e) => setJournalDraft((f) => ({ ...f, account: e.target.value }))}
          />
          <Field
            label="Transaction ID"
            value={journalDraft.transactionId}
            onChange={(e) => setJournalDraft((f) => ({ ...f, transactionId: e.target.value }))}
          />
          <Field
            label="Limit"
            type="number"
            min={1}
            max={200}
            value={journalDraft.limit}
            onChange={(e) => setJournalDraft((f) => ({ ...f, limit: Number(e.target.value) }))}
          />
          <div className="form-grid__actions">
            <button type="submit" className="btn btn--primary">Search</button>
          </div>
        </form>
        {journalQuery.isError && <ErrorBox error={journalQuery.error} title="Journal query failed" />}
        {journalQuery.data && journalQuery.data.data.length === 0 && <EmptyState title="No journal entries match." />}
        {journalQuery.data && journalQuery.data.data.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Entry</th>
                <th>Transaction</th>
                <th>Account</th>
                <th>Side</th>
                <th className="num">Amount</th>
              </tr>
            </thead>
            <tbody>
              {journalQuery.data.data.map((entry) => (
                <tr key={entry.journalEntryId}>
                  <td><code>{entry.journalEntryId}</code></td>
                  <td><code>{entry.transactionId}</code></td>
                  <td>{entry.account}</td>
                  <td><span className={`pill pill--${entry.side.toLowerCase()}`}>{entry.side}</span></td>
                  <td className="num">{formatMoney(entry.amount, entry.currency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="card">
        <h2>Chart of accounts</h2>
        {coaQuery.isError && <ErrorBox error={coaQuery.error} title="COA query failed" />}
        {coaQuery.data && (
          <table>
            <thead>
              <tr>
                <th>ID</th>
                <th>Name</th>
                <th>Type</th>
                <th>Normal side</th>
              </tr>
            </thead>
            <tbody>
              {coaQuery.data.map((row) => (
                <tr key={row.id}>
                  <td><code>{row.id}</code></td>
                  <td>{row.name}</td>
                  <td>{row.type}</td>
                  <td>{row.normalSide}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}
