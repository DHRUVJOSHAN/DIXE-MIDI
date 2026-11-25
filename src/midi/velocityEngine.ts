export const MIN_KEY_VELOCITY = 30;
export const MAX_KEY_VELOCITY = 127;
export const VELOCITY_JITTER = 2;

/**
 * Deterministic nonlinear position-based base velocity (30..127).
 * Top of key -> 30, bottom of key -> 127.
 */
export function calculateBasePositionVelocity(
  touchY: number,
  keyTop: number,
  keyHeight: number
): number {
  if (keyHeight <= 0) {
    return 64;
  }
  const p = Math.max(0, Math.min(1, (touchY - keyTop) / keyHeight));
  const span = MAX_KEY_VELOCITY - MIN_KEY_VELOCITY;
  const raw =
    MIN_KEY_VELOCITY +
    Math.round(span * ((Math.exp(3 * p) - 1) / (Math.exp(3) - 1)));
  return Math.max(MIN_KEY_VELOCITY, Math.min(MAX_KEY_VELOCITY, raw));
}

/**
 * Continuous position-based velocity (30..127) with ±2 humanization jitter,
 * clamped strictly within [30, 127].
 */
export function calculatePositionVelocity(
  touchY: number,
  keyTop: number,
  keyHeight: number
): number {
  const base = calculateBasePositionVelocity(touchY, keyTop, keyHeight);
  const jitter = Math.floor(Math.random() * (VELOCITY_JITTER * 2 + 1)) - VELOCITY_JITTER;
  return Math.max(MIN_KEY_VELOCITY, Math.min(MAX_KEY_VELOCITY, base + jitter));
}
