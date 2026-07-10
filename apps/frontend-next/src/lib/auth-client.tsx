"use client";

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { API_URL } from "./api";

export interface SessionUser {
  id: string;
  name: string;
  email: string;
  image: string | null;
}

interface SessionData {
  user: SessionUser;
}

interface AuthContextValue {
  data: SessionData | null;
  isPending: boolean;
}

const AuthContext = createContext<AuthContextValue>({ data: null, isPending: true });

/** Custom event other code can dispatch to make every mounted useSession() re-fetch. */
const SESSION_REFRESH_EVENT = "auth:session-refresh";
function notifySessionChanged() {
  window.dispatchEvent(new Event(SESSION_REFRESH_EVENT));
}

/**
 * Session provider backed by our Spring session-cookie auth (not better-auth — this is a
 * from-scratch client matching better-auth's `useSession()`/`signIn`/`signUp`/`signOut` shape so
 * the ported UI components (AuthForm, Navbar, ...) needed no behavioral changes).
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [data, setData] = useState<SessionData | null>(null);
  const [isPending, setIsPending] = useState(true);

  const load = useCallback(() => {
    fetch(`${API_URL}/api/auth/session`, { credentials: "include" })
      .then((res) => (res.ok ? res.json() : null))
      .then((user) => setData(user ? { user } : null))
      .catch(() => setData(null))
      .finally(() => setIsPending(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    window.addEventListener(SESSION_REFRESH_EVENT, load);
    return () => window.removeEventListener(SESSION_REFRESH_EVENT, load);
  }, [load]);

  return <AuthContext.Provider value={{ data, isPending }}>{children}</AuthContext.Provider>;
}

export function useSession() {
  return useContext(AuthContext);
}

interface AuthResult {
  error?: { message: string };
}

async function extractError(res: Response, fallback: string): Promise<string> {
  const body = await res.json().catch(() => ({}));
  return typeof body.error === "string" ? body.error : fallback;
}

export const signIn = {
  email: async ({ email, password }: { email: string; password: string }): Promise<AuthResult> => {
    try {
      const res = await fetch(`${API_URL}/api/auth/login`, {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password }),
      });
      if (!res.ok) return { error: { message: await extractError(res, "Login failed") } };
      notifySessionChanged();
      return {};
    } catch (err) {
      return { error: { message: err instanceof Error ? err.message : "Login failed" } };
    }
  },
  /** callbackURL is accepted for API-shape parity but not honored — the backend always
   * redirects to its configured FRONTEND_URL after a Google login. */
  social: async ({ callbackURL }: { provider: "google"; callbackURL?: string }): Promise<void> => {
    void callbackURL;
    window.location.href = `${API_URL}/oauth2/authorization/google`;
  },
};

export const signUp = {
  email: async ({
    email,
    password,
    name,
  }: {
    email: string;
    password: string;
    name: string;
  }): Promise<AuthResult> => {
    try {
      const res = await fetch(`${API_URL}/api/auth/register`, {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, email, password }),
      });
      if (!res.ok) return { error: { message: await extractError(res, "Sign up failed") } };
      notifySessionChanged();
      return {};
    } catch (err) {
      return { error: { message: err instanceof Error ? err.message : "Sign up failed" } };
    }
  },
};

export async function signOut(): Promise<void> {
  await fetch(`${API_URL}/api/auth/logout`, { method: "POST", credentials: "include" }).catch(() => {});
  notifySessionChanged();
}
