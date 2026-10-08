package dev.josemariamerchan.controlweb.publicstatus;

/** The public status could not be computed and no recent answer is available. Answered as a bare 503. */
public class PublicStatusUnavailableException extends RuntimeException {

	public PublicStatusUnavailableException() {
		// No message and no cause: nothing internal can end up in the response.
		super("The status is temporarily unavailable", null, false, false);
	}
}
