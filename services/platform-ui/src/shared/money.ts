const ZERO_DECIMAL = new Set(["JPY", "KRW", "VND", "CLP", "ISK"]);
const THREE_DECIMAL = new Set(["BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND"]);

export function fractionDigits(currency: string): number {
  const code = currency?.toUpperCase?.() || "USD";
  if (ZERO_DECIMAL.has(code)) return 0;
  if (THREE_DECIMAL.has(code)) return 3;
  return 2;
}

export function parseAmountToMinor(input: string, currency: string): number {
  const digits = fractionDigits(currency);
  const trimmed = input.trim();
  if (!/^\d+(\.\d+)?$/.test(trimmed)) {
    throw new Error("Enter a positive amount (digits and optional decimal).");
  }
  const [whole, fraction = ""] = trimmed.split(".");
  if (fraction.length > digits) {
    throw new Error(`${currency} supports at most ${digits} decimal places.`);
  }
  const padded = (fraction + "0".repeat(digits)).slice(0, digits);
  const minor = BigInt(whole) * BigInt(10) ** BigInt(digits) + BigInt(padded || "0");
  if (minor <= 0n) {
    throw new Error("Amount must be greater than zero.");
  }
  if (minor > BigInt(Number.MAX_SAFE_INTEGER)) {
    throw new Error("Amount is too large.");
  }
  return Number(minor);
}

export function formatMoney(minor: number | null | undefined, currency: string | null | undefined): string {
  if (minor === null || minor === undefined || Number.isNaN(minor)) return "—";
  const code = (currency || "USD").toUpperCase();
  const digits = fractionDigits(code);
  const value = minor / 10 ** digits;
  try {
    return new Intl.NumberFormat(undefined, { style: "currency", currency: code }).format(value);
  } catch {
    return `${value.toFixed(digits)} ${code}`;
  }
}
