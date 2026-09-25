import { HttpsError } from "firebase-functions/v2/https";

/** Reads a callable's untrusted `data`, throwing `invalid-argument` for anything malformed. */
export class Input {
  constructor(
    private readonly data: Record<string, unknown>,
    private readonly prefix = "",
  ) {}

  static of(data: unknown, prefix = ""): Input {
    if (typeof data !== "object" || data === null || Array.isArray(data)) {
      throw invalid(`${prefix || "Request"} must be an object.`);
    }
    return new Input(data as Record<string, unknown>, prefix);
  }

  /** Trimmed; null when absent or blank. */
  optionalString(key: string, maxLength = MAX_TEXT): string | null {
    const value = this.data[key];
    if (value === undefined || value === null) return null;
    if (typeof value !== "string") throw invalid(`${this.name(key)} must be a string.`);
    const trimmed = value.trim();
    if (trimmed.length > maxLength) throw invalid(`${this.name(key)} is longer than ${maxLength} characters.`);
    return trimmed.length > 0 ? trimmed : null;
  }

  /** Trimmed and not blank. */
  string(key: string, maxLength = MAX_TEXT): string {
    const value = this.optionalString(key, maxLength);
    if (value === null) throw invalid(`${this.name(key)} is required.`);
    return value;
  }

  optionalInt(key: string, min = 0, max = Number.MAX_SAFE_INTEGER): number | null {
    const value = this.data[key];
    if (value === undefined || value === null) return null;
    if (typeof value !== "number" || !Number.isInteger(value) || value < min || value > max) {
      throw invalid(`${this.name(key)} must be a whole number from ${min} to ${max}.`);
    }
    return value;
  }

  int(key: string, min = 0, max = Number.MAX_SAFE_INTEGER): number {
    const value = this.optionalInt(key, min, max);
    if (value === null) throw invalid(`${this.name(key)} is required.`);
    return value;
  }

  /** Epoch millis. */
  optionalMillis(key: string): number | null {
    return this.optionalInt(key, 0, MAX_MILLIS);
  }

  boolean(key: string, fallback: boolean): boolean {
    const value = this.data[key];
    if (value === undefined || value === null) return fallback;
    if (typeof value !== "boolean") throw invalid(`${this.name(key)} must be true or false.`);
    return value;
  }

  /** Trimmed, blanks dropped, and deduplicated ignoring case, as the app's tags table does. */
  stringList(key: string, maxItems: number, maxLength: number): string[] {
    const value = this.data[key];
    if (value === undefined || value === null) return [];
    if (!Array.isArray(value) || value.length > maxItems) {
      throw invalid(`${this.name(key)} must be a list of at most ${maxItems} strings.`);
    }
    const seen = new Set<string>();
    const result: string[] = [];
    for (const entry of value) {
      if (typeof entry !== "string") throw invalid(`${this.name(key)} must hold strings only.`);
      const trimmed = entry.trim();
      if (trimmed.length > maxLength) throw invalid(`An entry of ${this.name(key)} is longer than ${maxLength} characters.`);
      if (trimmed.length === 0 || seen.has(trimmed.toLowerCase())) continue;
      seen.add(trimmed.toLowerCase());
      result.push(trimmed);
    }
    return result;
  }

  optionalObject(key: string): Input | null {
    const value = this.data[key];
    if (value === undefined || value === null) return null;
    return Input.of(value, this.name(key));
  }

  object(key: string): Input {
    const value = this.optionalObject(key);
    if (value === null) throw invalid(`${this.name(key)} is required.`);
    return value;
  }

  private name(key: string): string {
    return this.prefix ? `${this.prefix}.${key}` : key;
  }
}

export function invalid(message: string): HttpsError {
  return new HttpsError("invalid-argument", message);
}

const MAX_TEXT = 20_000;
/** Year 3000; past it, a "millis" value is surely seconds or garbage. */
const MAX_MILLIS = 32_503_680_000_000;
