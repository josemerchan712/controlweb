package dev.josemariamerchan.controlweb.monitoring;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Throw-away certificates generated in memory for TLS tests: nothing with a private key is stored
 * in the repository. A {@link CertificateAuthority} signs server certificates with any validity
 * period and host names, so we can produce valid, expired, not-yet-valid, wrong-host and untrusted
 * (unknown CA) servers.
 */
final class TestPki {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final char[] PASSWORD = "test".toCharArray();

	private TestPki() {
	}

	record Identity(X509Certificate certificate, PrivateKey key, X509Certificate issuer) {
	}

	static final class CertificateAuthority {

		private final KeyPair keys;
		private final X500Name name;
		final X509Certificate certificate;

		private CertificateAuthority(KeyPair keys, X500Name name, X509Certificate certificate) {
			this.keys = keys;
			this.name = name;
			this.certificate = certificate;
		}

		static CertificateAuthority create(String commonName) throws Exception {
			KeyPair keys = newKeyPair();
			X500Name name = new X500Name("CN=" + commonName);
			Instant now = Instant.now();
			JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, serial(),
					Date.from(now.minusSeconds(3600)), Date.from(now.plusSeconds(86_400L * 30)), name,
					keys.getPublic());
			builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
			builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
			return new CertificateAuthority(keys, name, sign(builder, keys.getPrivate()));
		}

		/** A server certificate for the given DNS names (and 127.0.0.1), valid between the two instants. */
		Identity issue(String commonName, List<String> dnsNames, Instant notBefore, Instant notAfter) throws Exception {
			KeyPair serverKeys = newKeyPair();
			JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, serial(),
					Date.from(notBefore), Date.from(notAfter), new X500Name("CN=" + commonName),
					serverKeys.getPublic());
			List<GeneralName> alternativeNames = new ArrayList<>();
			dnsNames.forEach(dns -> alternativeNames.add(new GeneralName(GeneralName.dNSName, dns)));
			alternativeNames.add(new GeneralName(GeneralName.iPAddress, "127.0.0.1"));
			builder.addExtension(Extension.subjectAlternativeName, false,
					new GeneralNames(alternativeNames.toArray(new GeneralName[0])));
			builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
			builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
			return new Identity(sign(builder, keys.getPrivate()), serverKeys.getPrivate(), certificate);
		}
	}

	/** An SSL context for a server presenting {@code identity} (leaf + CA chain). */
	static SSLContext serverContext(Identity identity) throws Exception {
		KeyStore store = KeyStore.getInstance("PKCS12");
		store.load(null, null);
		store.setKeyEntry("server", identity.key(), PASSWORD,
				new Certificate[] { identity.certificate(), identity.issuer() });
		KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		factory.init(store, PASSWORD);
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(factory.getKeyManagers(), null, null);
		return context;
	}

	/** An SSL context for a client that trusts exactly these CAs (and nothing else, not even the JDK's). */
	static SSLContext clientTrusting(X509Certificate... authorities) throws Exception {
		KeyStore store = KeyStore.getInstance("PKCS12");
		store.load(null, null);
		for (int i = 0; i < authorities.length; i++) {
			store.setCertificateEntry("ca" + i, authorities[i]);
		}
		TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		factory.init(store);
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(null, factory.getTrustManagers(), null);
		return context;
	}

	private static KeyPair newKeyPair() throws GeneralSecurityException {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));
		return generator.generateKeyPair();
	}

	private static BigInteger serial() {
		return new BigInteger(63, RANDOM).add(BigInteger.ONE);
	}

	private static X509Certificate sign(JcaX509v3CertificateBuilder builder, PrivateKey signingKey) throws Exception {
		ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(signingKey);
		return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
	}
}
