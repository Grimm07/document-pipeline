import type { ErrorResponse } from "@/types/api";

/** Custom error representing a non-OK HTTP response from the API. */
export class ApiError extends Error {
  constructor(
    public status: number,
    public body: ErrorResponse,
  ) {
    super(body.error);
    this.name = "ApiError";
  }
}

const BASE_URL = "/api";

/** Typed fetch wrapper that prepends the API base URL and handles errors. */
export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE_URL}${path}`, {
    headers: {
      Accept: "application/json",
      ...init?.headers,
    },
    ...init,
  });

  if (!response.ok) {
    let body: ErrorResponse;
    try {
      body = await response.json();
    } catch {
      body = { error: `HTTP ${response.status}: ${response.statusText}` };
    }
    throw new ApiError(response.status, body);
  }

  // Handle empty responses (e.g. 204)
  const text = await response.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}
