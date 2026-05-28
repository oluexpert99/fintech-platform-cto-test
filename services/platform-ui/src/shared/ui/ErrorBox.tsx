import { isAxiosError } from "axios";

type ErrorBoxProps = {
  title?: string;
  error?: unknown;
  message?: string;
};

type Problem = {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  traceId?: string;
  violations?: Array<{ field?: string; code?: string; message?: string }>;
};

function extract(error: unknown): { title?: string; detail?: string; code?: string; status?: number; violations?: Problem["violations"]; traceId?: string } | null {
  if (!error) return null;
  if (isAxiosError(error)) {
    const body = (error.response?.data ?? {}) as Problem;
    return {
      title: body.title,
      detail: body.detail || error.message,
      code: body.code,
      status: body.status ?? error.response?.status,
      violations: body.violations,
      traceId: body.traceId,
    };
  }
  if (error instanceof Error) return { detail: error.message };
  return null;
}

export function ErrorBox({ title, error, message }: ErrorBoxProps) {
  const problem = extract(error);
  const headline = title ?? problem?.title ?? "Request failed";
  const detail = problem?.detail ?? message;
  const code = problem?.code;
  const status = problem?.status;

  return (
    <div className="error-box" role="alert">
      <div className="error-box__title">
        <strong>{headline}</strong>
        {(status || code) && (
          <span className="error-box__code">
            {status ? `HTTP ${status}` : ""}
            {status && code ? " · " : ""}
            {code ?? ""}
          </span>
        )}
      </div>
      {detail && <div className="error-box__detail">{detail}</div>}
      {problem?.violations && problem.violations.length > 0 && (
        <ul className="error-box__violations">
          {problem.violations.map((v, i) => (
            <li key={i}>
              {v.field ? <code>{v.field}</code> : null} {v.message ?? v.code}
            </li>
          ))}
        </ul>
      )}
      {problem?.traceId && <div className="error-box__trace">trace: {problem.traceId}</div>}
    </div>
  );
}
