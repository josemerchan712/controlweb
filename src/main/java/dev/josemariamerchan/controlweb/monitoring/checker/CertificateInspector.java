package dev.josemariamerchan.controlweb.monitoring.checker;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Optional;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

import org.springframework.stereotype.Component;

/** Reads the expiry date of the server certificate from the TLS session of a request that already happened. */
@Component
public class CertificateInspector {

	/** The {@code notAfter} of the server's own (leaf) certificate, if the session has one. */
	public Optional<Instant> expiryOf(SSLSession session) {
		try {
			Certificate[] chain = session.getPeerCertificates();
			if (chain != null && chain.length > 0 && chain[0] instanceof X509Certificate leaf) {
				return Optional.of(leaf.getNotAfter().toInstant());
			}
		}
		catch (SSLPeerUnverifiedException ex) {
			// no certificate was presented: nothing to report
		}
		return Optional.empty();
	}
}
