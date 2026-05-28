type Props = { data: unknown; label?: string };

export function JsonDisclosure({ data, label = "Raw response" }: Props) {
  return (
    <details className="raw-disclosure">
      <summary>{label}</summary>
      <pre>{JSON.stringify(data, null, 2)}</pre>
    </details>
  );
}
