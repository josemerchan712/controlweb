package dev.josemariamerchan.controlweb.common.web;

/** A query parameter of a read endpoint is invalid: answered as 400 with a problem detail, never as a 500. */
public class InvalidQueryException extends RuntimeException {

	public InvalidQueryException(String message) {
		super(message);
	}
}
