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
import javax.baja.sys.BString;
import javax.baja.sys.BValue;

/**
 * Shared JSON &harr; Niagara {@code BSimple} coercion for the v0.5+ write
 * tools ({@code setSlot}, {@code invokeAction}, {@code clearSlot}).
 *
 * <p>Deliberately narrow: only the six {@code BSimple} primitives that
 * round-trip cleanly to/from JSON scalars — {@link BString},
 * {@link BBoolean}, {@link BInteger}, {@link BLong}, {@link BFloat},
 * {@link BDouble}. Everything else (status values, facets, ords, abs-times,
 * structured complex types) is rejected via {@link UnsupportedTypeException}
 * so callers can point operators at a type-specific tool ({@code writePoint}
 * already handles status-value priority slots).
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

  /** True iff {@code v} is one of the six supported {@code BSimple} primitive types. */
  static boolean isSupported(BValue v) {
    return v instanceof BString  || v instanceof BBoolean || v instanceof BInteger
        || v instanceof BLong    || v instanceof BFloat   || v instanceof BDouble;
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
    throw new UnsupportedTypeException(
        "Slot/parameter type is "
        + (template == null ? "null" : template.getClass().getSimpleName())
        + "; only BSimple primitives are supported "
        + "(BString/BBoolean/BInteger/BLong/BFloat/BDouble). "
        + "Use a type-specific tool for status values, facets, ords, etc.");
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
