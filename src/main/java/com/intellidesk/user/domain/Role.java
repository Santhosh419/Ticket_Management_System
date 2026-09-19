package com.intellidesk.user.domain;

/**
 * System roles supported by IntelliDesk.
 *
 * <p>Design decision (documented in the README): roles are a small, code-owned set,
 * so they are stored as an enum column on {@code users} instead of a separate
 * {@code roles} table. A dedicated table only pays off when roles can be created
 * dynamically at runtime, which IntelliDesk does not support by design.</p>
 */
public enum Role {
    CUSTOMER,
    AGENT,
    ADMIN
}
