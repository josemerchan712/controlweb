package dev.josemariamerchan.controlweb.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.josemariamerchan.controlweb.site.service.InvalidSiteUrlException;
import dev.josemariamerchan.controlweb.site.service.SiteUrlNormalizer;

class SiteUrlNormalizerTest {

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			// scheme and host are case-insensitive
			"HTTPS://Example.COM,                     https://example.com",
			// a bare root slash is redundant
			"https://example.com/,                    https://example.com",
			// fragments are never sent to the server
			"https://example.com/#top,                https://example.com",
			"https://example.com/Path?Q=1#frag,       https://example.com/Path?Q=1",
			// default ports are redundant, other ports are kept
			"https://example.com:443/,                https://example.com",
			"http://example.com:80,                   http://example.com",
			"http://example.com:8080/,                http://example.com:8080",
			"https://example.com:80,                  https://example.com:80",
			// surrounding whitespace is trimmed
			"'  https://example.com  ',               https://example.com",
			// a trailing dot denotes the same fully-qualified host
			"https://example.com.,                    https://example.com",
			// paths are case-sensitive and a trailing slash on a real path is meaningful: kept
			"https://example.com/Blog/,               https://example.com/Blog/",
			"https://example.com/health,              https://example.com/health",
			// query without path
			"https://example.com?x=1,                 https://example.com?x=1",
			// IP literals
			"http://127.0.0.1:8080/,                  http://127.0.0.1:8080",
			"http://[::1]/,                           http://[::1]"
	})
	@DisplayName("normalizes equivalent URLs to one canonical form")
	void normalizes(String input, String expected) {
		assertThat(SiteUrlNormalizer.normalize(input)).hasToString(expected);
	}

	@Test
	@DisplayName("equivalent spellings of the same site normalize to the same string")
	void equivalentSpellingsCollapse() {
		assertThat(SiteUrlNormalizer.normalize("HTTPS://Example.com:443/#a"))
				.isEqualTo(SiteUrlNormalizer.normalize("https://example.com"));
	}

	@Test
	@DisplayName("exposes the lower-cased host of the normalized URL")
	void exposesHost() {
		assertThat(SiteUrlNormalizer.normalize("https://API.Example.com/x").getHost()).isEqualTo("api.example.com");
	}

	@ParameterizedTest(name = "rejects credentials in {0}")
	@ValueSource(strings = {
			"http://user:pass@example.com",
			"https://user:pass@example.com/path",
			"https://user@example.com",
			"https://:pass@example.com",
			"https://@example.com"
	})
	void rejectsEmbeddedCredentials(String url) {
		assertThatThrownBy(() -> SiteUrlNormalizer.normalize(url))
				.isInstanceOf(InvalidSiteUrlException.class)
				.hasMessageContaining("credentials");
	}

	@ParameterizedTest(name = "rejects \"{0}\"")
	@ValueSource(strings = {
			"",
			"   ",
			"example.com",
			"ftp://example.com",
			"file:///etc/passwd",
			"javascript:alert(1)",
			"https://",
			"https:///path",
			"https://exa mple.com",
			"http://exa_mple.com"
	})
	void rejectsInvalidUrls(String url) {
		assertThatThrownBy(() -> SiteUrlNormalizer.normalize(url)).isInstanceOf(InvalidSiteUrlException.class);
	}

	@Test
	@DisplayName("rejects null")
	void rejectsNull() {
		assertThatThrownBy(() -> SiteUrlNormalizer.normalize(null)).isInstanceOf(InvalidSiteUrlException.class);
	}
}
