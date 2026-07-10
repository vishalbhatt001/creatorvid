import crypto from "node:crypto";
import { env } from "../env.js";

/**
 * Minimal Razorpay client over the REST API — no SDK dependency.
 *  - createOrder: opens an order the frontend Checkout widget pays against.
 *  - verifyPaymentSignature: confirms a completed payment really came from
 *    Razorpay (HMAC of `${orderId}|${paymentId}` with the key secret).
 *  - verifyWebhookSignature: validates webhook callbacks.
 */

const RAZORPAY_API = "https://api.razorpay.com/v1";
const KEY_ID_RE = /^rzp_(test|live)_/;

export function isRazorpayConfigured(): boolean {
  return Boolean(
    env.RAZORPAY_KEY_ID &&
      env.RAZORPAY_KEY_SECRET &&
      KEY_ID_RE.test(env.RAZORPAY_KEY_ID),
  );
}

/** Throws a clear error if Razorpay keys aren't set or look malformed. */
function requireKeys(): { id: string; secret: string } {
  if (!env.RAZORPAY_KEY_ID || !env.RAZORPAY_KEY_SECRET) {
    throw new Error(
      "Razorpay is not configured. Set RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET in the backend .env.",
    );
  }
  if (!KEY_ID_RE.test(env.RAZORPAY_KEY_ID)) {
    throw new Error(
      "RAZORPAY_KEY_ID looks invalid (expected rzp_test_… or rzp_live_…). Copy both keys from the Razorpay dashboard → Settings → API Keys.",
    );
  }
  return { id: env.RAZORPAY_KEY_ID, secret: env.RAZORPAY_KEY_SECRET };
}

function authFailureMessage(status: number, body: string): string | null {
  if (status === 401 || body.includes("Authentication failed")) {
    return (
      "Razorpay authentication failed — verify RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET are a matching pair from the same mode (test or live) in the Razorpay dashboard → Settings → API Keys."
    );
  }
  return null;
}

export interface RazorpayOrder {
  id: string;
  amount: number;
  currency: string;
  status: string;
}

/** Create a Razorpay order. `amount` is in the smallest unit (paise for INR). */
export async function createOrder(params: {
  amount: number;
  currency: string;
  receipt: string;
  notes?: Record<string, string>;
}): Promise<RazorpayOrder> {
  const { id, secret } = requireKeys();
  const auth = Buffer.from(`${id}:${secret}`).toString("base64");
  const res = await fetch(`${RAZORPAY_API}/orders`, {
    method: "POST",
    headers: {
      Authorization: `Basic ${auth}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      amount: params.amount,
      currency: params.currency,
      receipt: params.receipt,
      notes: params.notes,
    }),
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(authFailureMessage(res.status, body) ?? `Razorpay order creation failed: ${res.status} ${body}`);
  }
  return (await res.json()) as RazorpayOrder;
}

/** True if the (orderId, paymentId, signature) triple is authentic. */
export function verifyPaymentSignature(params: {
  orderId: string;
  paymentId: string;
  signature: string;
}): boolean {
  const { secret } = requireKeys();
  const expected = crypto
    .createHmac("sha256", secret)
    .update(`${params.orderId}|${params.paymentId}`)
    .digest("hex");
  return timingSafeEqual(expected, params.signature);
}

/** Validate a Razorpay webhook payload against the configured webhook secret. */
export function verifyWebhookSignature(rawBody: string, signature: string): boolean {
  if (!env.RAZORPAY_WEBHOOK_SECRET) return false;
  const expected = crypto
    .createHmac("sha256", env.RAZORPAY_WEBHOOK_SECRET)
    .update(rawBody)
    .digest("hex");
  return timingSafeEqual(expected, signature);
}

/** Constant-time string comparison that tolerates length mismatches. */
function timingSafeEqual(a: string, b: string): boolean {
  const ab = Buffer.from(a);
  const bb = Buffer.from(b);
  if (ab.length !== bb.length) return false;
  return crypto.timingSafeEqual(ab, bb);
}
