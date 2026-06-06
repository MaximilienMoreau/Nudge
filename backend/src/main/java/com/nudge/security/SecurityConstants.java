package com.nudge.security;

/** Shared security-related constants used across filters, controllers, and configs. */
public final class SecurityConstants {

    private SecurityConstants() {}

    /** Name of the httpOnly cookie that carries the JWT. */
    public static final String JWT_COOKIE_NAME = "nudge_jwt";
}
