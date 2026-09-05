/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.sys.BBoolean;
import javax.baja.sys.BDouble;
import javax.baja.sys.BFloat;
import javax.baja.sys.BInteger;
import javax.baja.sys.BLong;
import javax.baja.sys.BSimple;
import javax.baja.sys.BString;
import javax.baja.sys.BValue;

/**
 * Shared JSON &harr; Niagara {@code BSimple} coercion for the v0.5+ write
 * tools ({@code setSlot}, {@code invokeAction}, {@code clearSlot}).
 *
 * <p>Scope is {@link BSimple}, in two tiers:
 * <ul>
 *   <li>The six primitives that map onto JSON scalars — {@link BString},
 *       {@link BBoolean}, {@link BInteger}, {@link BLong}, {@link BFloat},
 *       {@link BDouble} — are converted directly.</li>
 *   <li>Every other {@code BSimple} round-trips through the
 *       {@code encodeToString}/{@code decodeFromString} pair that
 *       {@code BSimple} itself declares as its canonical text form. That
 *       covers {@code BNameMap} (a folder's {@code displayNames}), frozen
 *       enums such as {@code BPollFrequency}, and {@code BRelTime},
 *       {@code BOrd}, {@code BAbsTime}, {@code BFacets}.</li>
 * </ul>
 *
 * <p>Complex and component types are still rejected via
 * {@link UnsupportedTypeException} so callers can be pointed at a
 * type-specific tool ({@code writePoint} already handles status-value
 * priority slots).
 *
 * <p>Widened from the original six-type allowlist because that list blocked
 * ordinary configuration work on a live station: bulk point renaming
 * ({@code BNameMap}), driver poll-rate retuning ({@code BPollFrequency})
 * and component interval changes ({@code BRelTime}) were all unreachable
 * through {@code setSlot} despite being plain Property-slot writes.
 *
 * <p>Extracted in v0.5.2 from the near-identical copies that lived in
 * {@code SetSlotTool} and {@code InvokeActionTool}.
 */
final class BValueCoercer {
  private BValueCoercer() {}

  /**
   * Thrown when the coercion template is not one of the supported
   * {@code BSimple} primitives. Carries a {@code hint} the caller can
   * fold into the {@code -32602} error {@code data}.
   */
  static final class UnsupportedTypeException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final String hint;
    UnsupportedTypeException(String hint) { super(hint); this.hint = hint; }
  }

  /**
   * True iff {@code v} can be coerced by {@link #coerce}: any {@code BSimple}.
   * The six JSON-scalar primitives are handled directly; every other
   * {@code BSimple} round-trips through {@code decodeFromString}.
   */
  static boolean isSupported(BValue v) {
    return v instanceof BSimple;
  }

  /**
   * Coerce a raw JSON scalar ({@code String} / {@code Number} / {@code Boolean})
   * to a {@link BValue} matching {@code template}'s {@code BSimple} type.
   *
   * @param template the slot's existing value or the action's parameter
   *                 default — only its runtime class is used
   * @param raw      the JSON value from the tool args
   * @throws UnsupportedTypeException {@code template} is not a supported
   *         {@code BSimple} type (callers map this to {@code -32602})
   * @throws NumberFormatException    {@code raw} can't be parsed as the
   *         needed numeric/boolean (callers map this to {@code -32602})
   */
  static BValue coerce(BValue template, Object raw) {
    if (template instanceof BString)  return BString.make(String.valueOf(raw));
    if (template instanceof BBoolean) return BBoolean.make(toBoolean(raw));
    if (template instanceof BInteger) return BInteger.make(toInt(raw));
    if (template instanceof BLong)    return BLong.make(toLong(raw));
    if (template instanceof BFloat)   return BFloat.make((float) toDouble(raw));
    if (template instanceof BDouble)  return BDouble.make(toDouble(raw));

    // Every other BSimple round-trips through its own string form. BSimple
    // declares encodeToString/decodeFromString as the canonical textual
    // representation, so this covers BNameMap (component displayNames),
    // frozen enums such as BPollFrequency, BRelTime, BOrd, BAbsTime and
    // BFacets without a branch per type. decodeFromString is conventionally
    // a factory that ignores instance state; only the runtime class of
    // template is used, matching the contract of the six cases above.
    if (template instanceof BSimple) {
      String s = String.valueOf(raw);
      try {
        return (BValue) ((BSimple) template).decodeFromString(s);
      } catch (Exception e) {
        // NumberFormatException is what SetSlotTool/InvokeActionTool already
        // map to -32602 "Cannot coerce value to existing slot type", so a
        // malformed literal reports as a bad argument rather than a 500.
        throw new NumberFormatException(
            "not a valid " + template.getClass().getSimpleName()
            + " literal: " + e.getMessage());
      }
    }

    throw new UnsupportedTypeException(
        "Slot/parameter type is "
        + (template == null ? "null" : template.getClass().getSimpleName())
        + "; only BSimple values are supported. BString/BBoolean/BInteger/"
        + "BLong/BFloat/BDouble accept JSON scalars; any other BSimple "
        + "accepts its string form. Complex and component types are not "
        + "supported — use a type-specific tool for status values.");
  }

  /**
   * {@link BValue} &rarr; JSON-friendly scalar: {@code String} /
   * {@code Boolean} / {@code Integer} / {@code Long} / {@code Float} /
   * {@code Double} for the supported primitives, {@code v.toString()} for
   * anything else, {@code null} for {@code null}.
   */
  static Object toJsonScalar(BValue v) {
    if (v == null)             return null;
    if (v instanceof BString)  return ((BString) v).getString();
    if (v instanceof BBoolean) return ((BBoolean) v).getBoolean();
    if (v instanceof BInteger) return ((BInteger) v).getInt();
    if (v instanceof BLong)    return ((BLong) v).getLong();
    if (v instanceof BFloat)   return ((BFloat) v).getFloat();
    if (v instanceof BDouble)  return ((BDouble) v).getDouble();
    // Other BSimple values render as their canonical string form, so a
    // displayNames map or a poll frequency reads back as the same literal
    // setSlot accepts, rather than "BNameMap@1a2b3c".
    if (v instanceof BSimple) {
      try {
        return ((BSimple) v).encodeToString();
      } catch (Exception e) {
        return v.toString();
      }
    }
    return v.toString();
  }

  /** Type spec ({@code "baja:String"}) or {@code "null"} for a null value. */
  static String typeSpec(BValue v) {
    return v == null ? "null" : v.getType().getTypeSpec().toString();
  }

  // ---- raw -> primitive helpers ----

  private static boolean toBoolean(Object raw) {
    if (raw instanceof Boolean) return ((Boolean) raw).booleanValue();
    String s = (raw == null) ? "" : raw.toString().trim().toLowerCase();
    if ("true".equals(s)  || "1".equals(s)) return true;
    if ("false".equals(s) || "0".equals(s)) return false;
    throw new NumberFormatException("not a boolean: " + raw);
  }

  private static int toInt(Object raw) {
    return (raw instanceof Number) ? ((Number) raw).intValue() : Integer.parseInt(String.valueOf(raw));
  }

  private static long toLong(Object raw) {
    return (raw instanceof Number) ? ((Number) raw).longValue() : Long.parseLong(String.valueOf(raw));
  }

  private static double toDouble(Object raw) {
    return (raw instanceof Number) ? ((Number) raw).doubleValue() : Double.parseDouble(String.valueOf(raw));
  }
}
