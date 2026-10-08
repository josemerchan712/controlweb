package dev.josemariamerchan.controlweb.auth.service;

/**
 * Login failed. The message is deliberately the same whether the email is unknown or the
 * password is wrong, so the API cannot be used to find out which emails have an account.
 */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("Invalid email or password");
	}
}
