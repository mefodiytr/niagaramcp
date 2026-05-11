/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Salted SHA-256 hashing for MCP user tokens, plus constant-time
 * comparison helpers.
 *
 * <p>Tokens themselves are not stored — only the hash, written to the
 * {@code mcp:tokenHash} tag on a {@code BUser}. Bearer authentication
 * walks {@code BUserService.getUsers()}, hashes the presented bearer
 * with the per-service salt from {@link
 * com.niagaramcp.server.BMcpPlatformService#getTokenSalt()}, and uses
 * {@link #constantTimeEquals(String, String)} against each user's tag
 * value. The walk is unconditional — no early-exit on mismatch — so
 * timing does not leak which usernames carry an MCP tag.
 *
 * <p>Hash output is lowercase hex of SHA-256 ({@code salt_bytes ||
 * token_utf8}), 64 characters. Salt is base64-encoded raw bytes
 * (default 16, see {@link #generateSaltBase64()}).
 *
 * <p>No external dependencies. Java 8 baseline.
 */
public final class TokenHasher {

  private static final int SALT_BYTES = 16;
  private static final int TOKEN_BYTES = 32;
  private static final SecureRandom RNG = new SecureRandom();

  private TokenHasher() {}

  /**
   * Hash {@code plaintext} with the given base64-encoded salt.
   *
   * @return 64-char lowercase hex of SHA-256
   * @throws IllegalArgumentException if salt is not valid base64
   * @throws IllegalStateException    if the JVM lacks SHA-256 (never happens on standard JREs)
   */
  public static String hash(String plaintext, String saltBase64) {
    if (plaintext == null) throw new IllegalArgumentException("plaintext must not be null");
    if (saltBase64 == null || saltBase64.isEmpty()) {
      throw new IllegalArgumentException("salt must not be null/empty");
    }
    byte[] salt = Base64.getDecoder().decode(saltBase64);
    MessageDigest md;
    try {
      md = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
    md.update(salt);
    md.update(plaintext.getBytes(StandardCharsets.UTF_8));
    return toHex(md.digest());
  }

  /**
   * Constant-time equality of two strings. Returns {@code false} for
   * any null input. Defers to {@link MessageDigest#isEqual(byte[], byte[])},
   * which the JDK documents as length-independent and constant-time.
   *
   * <p>Strings are compared as their UTF-8 byte sequences. Differences in
   * length cause {@code false} but the comparison still runs to completion
   * over the longer length to avoid leaking length info via timing.
   */
  public static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) return false;
    byte[] ab = a.getBytes(StandardCharsets.UTF_8);
    byte[] bb = b.getBytes(StandardCharsets.UTF_8);
    // Pad shorter to longer so isEqual sees same-length arrays — keeps timing
    // tied to max(len) only, not to whether lengths matched.
    int len = Math.max(ab.length, bb.length);
    byte[] ax = (ab.length == len) ? ab : pad(ab, len);
    byte[] bx = (bb.length == len) ? bb : pad(bb, len);
    boolean equal = MessageDigest.isEqual(ax, bx);
    return equal && ab.length == bb.length;
  }

  /** @return base64-encoded {@value #SALT_BYTES}-byte SecureRandom salt. */
  public static String generateSaltBase64() {
    byte[] s = new byte[SALT_BYTES];
    RNG.nextBytes(s);
    return Base64.getEncoder().encodeToString(s);
  }

  /**
   * @return base64url-encoded {@value #TOKEN_BYTES}-byte SecureRandom
   *         token suitable as a Bearer value. Used by the workbench
   *         {@code generateUserToken} action and the MCP
   *         {@code rotateMcpToken} tool (added in v0.5+ commits).
   */
  public static String generateTokenBase64Url() {
    byte[] t = new byte[TOKEN_BYTES];
    RNG.nextBytes(t);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(t);
  }

  // ---- helpers ----

  private static byte[] pad(byte[] src, int len) {
    byte[] out = new byte[len];
    System.arraycopy(src, 0, out, 0, src.length);
    return out;
  }

  private static String toHex(byte[] bytes) {
    char[] HEX = "0123456789abcdef".toCharArray();
    char[] out = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      int v = bytes[i] & 0xFF;
      out[i * 2]     = HEX[v >>> 4];
      out[i * 2 + 1] = HEX[v & 0x0F];
    }
    return new String(out);
  }

  /**
   * Standalone smoke for ad-hoc verification (no JUnit on baseline).
   * Run with: {@code java -cp niagaramcp-rt.jar com.niagaramcp.server.auth.TokenHasher}.
   * Asserts via plain System.exit on failure.
   */
  public static void main(String[] args) {
    String salt = generateSaltBase64();
    String token = generateTokenBase64Url();
    String h1 = hash(token, salt);
    String h2 = hash(token, salt);
    if (!h1.equals(h2)) { System.err.println("hash not deterministic"); System.exit(1); }
    if (h1.length() != 64) { System.err.println("hash length != 64"); System.exit(1); }
    if (!constantTimeEquals(h1, h2)) { System.err.println("equal hashes failed compare"); System.exit(1); }
    // Flip last hex char to a guaranteed-different one (avoids 1/16 collision when
    // h1 already ended in the replacement char).
    char last = h1.charAt(63);
    char other = (last == 'f') ? '0' : (char)(last + 1);
    if (constantTimeEquals(h1, h1.substring(0, 63) + other)) {
      System.err.println("differing hashes wrongly equal"); System.exit(1);
    }
    if (constantTimeEquals(null, h1)) { System.err.println("null/non-null wrongly equal"); System.exit(1); }
    if (constantTimeEquals(h1, h1.substring(0, 63))) {
      System.err.println("different-length wrongly equal"); System.exit(1);
    }
    String h3 = hash(token, generateSaltBase64());
    if (constantTimeEquals(h1, h3)) { System.err.println("different salts produced same hash"); System.exit(1); }
    System.out.println("TokenHasher smoke passed");
    System.out.println("  salt:  " + salt);
    System.out.println("  token: " + token);
    System.out.println("  hash:  " + h1);
  }
}
