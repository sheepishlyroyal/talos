package dev.glade.client.action;

/** Authoritative outcome of a client action. */
public record ActionResult(boolean success, String message) {
}
