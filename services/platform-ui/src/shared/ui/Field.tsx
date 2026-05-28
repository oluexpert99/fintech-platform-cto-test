import { type InputHTMLAttributes, type ReactNode, useId } from "react";

type FieldProps = {
  label: string;
  hint?: string;
  children?: ReactNode;
} & InputHTMLAttributes<HTMLInputElement>;

export function Field({ label, hint, children, id, ...rest }: FieldProps) {
  const auto = useId();
  const fieldId = id ?? auto;
  return (
    <label className="field" htmlFor={fieldId}>
      <span className="field__label">{label}</span>
      {children ?? <input id={fieldId} {...rest} />}
      {hint && <span className="field__hint">{hint}</span>}
    </label>
  );
}
