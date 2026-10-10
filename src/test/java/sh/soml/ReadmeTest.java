package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/*
The examples in the readme, so that they keep working.
*/
class ReadmeTest {
	record Config(String name, int replicas, Duration timeout, LocalDate launchDate, Instant deployedAt, List<String> labels, Postgres postgres) {}

	record Postgres(String host, int port) {}

	@Test
	void read() {
		var mapper = SomlMapper.builder()
			.keyNaming(KeyNaming.KEBAB_CASE)
			.build();

		var config = mapper.decode("""
			/* The edge service. */
			name: 'api-gateway'
			replicas: 3
			timeout: 1m30s # Including retries.
			launch-date: 2027-09-19
			deployed-at: 2026-09-19T14:00:00Z
			labels: ['prod', 'eu-west']
			postgres: {host: 'db.internal', port: 5432}
			""", Config.class);

		assertEquals(LocalDate.of(2027, 9, 19), config.launchDate());
		assertEquals(5432, config.postgres().port());
		assertEquals("PT1M30S", config.timeout().toString());
	}

	record Server(int port, List<String> hosts) {}

	@Test
	void write() {
		assertEquals("port: 8080\nhosts: [\n\t'a'\n\t'b'\n]\n", Soml.encode(new Server(8080, List.of("a", "b"))));
		assertEquals("hosts: [\n\t'a'\n\t'b'\n]\nport: 8080\n", Soml.encodeCanonical(new Server(8080, List.of("a", "b"))));
	}

	@Test
	void valuesOfUnknownShape() {
		var document = (SomlObject) Soml.parse("port: 8080\nhosts: ['a', 'b']");
		var port = document.get("port") instanceof SomlInt(var number) ? number : -1;
		assertEquals(8080, port);

		var hostCount = switch (document.get("hosts")) {
			case SomlArray(var items) -> items.size();
			case SomlString _ -> 1;
			case null, default -> 0;
		};

		assertEquals(2, hostCount);
	}

	static class KnownServer {
		String host = "localhost";
		int port = 8080;
	}

	static class Settings {
		KnownServer server = new KnownServer();
	}

	@Test
	void knownMembersOfUnknownShape() {
		var settings = Soml.decode("server: {port: 9}", Settings.class);
		assertEquals("localhost", settings.server.host);
		assertEquals(9, settings.server.port);
		assertEquals(8080, Soml.decode("other: 1", Settings.class).server.port);

		var error = assertThrows(SomlException.class, () -> Soml.decode("server: {port: '9'}", Settings.class));
		assertEquals("server.port: Expected an int, but found the string '9' at line 1, column 16", error.getMessage().lines().findFirst().orElseThrow());
	}

	record NamedServer(String host, int port) {}

	@Test
	void errors() {
		var error = assertThrows(SomlException.class, () -> Soml.decode("host: 'a'\nport: 'eighty'", NamedServer.class));
		assertEquals("port: Expected an int, but found the string 'eighty' at line 2, column 7\n\n  1 | host: 'a'\n> 2 | port: 'eighty'\n    |       ^", error.getMessage());
		assertEquals("Expected an int, but found the string 'eighty'", error.reason());
		assertEquals(List.of("port"), error.path());
	}

	@Test
	void typeReference() {
		List<Postgres> databases = Soml.decode("[{host: 'a', port: 1}]", new TypeReference<List<Postgres>>() {});
		assertEquals(List.of(new Postgres("a", 1)), databases);
	}

	record Color(int red, int green, int blue) {
		static final SomlAdapter<Color> SOML = SomlAdapter.of(
			color -> new SomlString("#%02X%02X%02X".formatted(color.red(), color.green(), color.blue())),
			value -> {
				if (!(value instanceof SomlString(var hex)) || !hex.matches("#[0-9A-F]{6}")) {
					throw new SomlException("Expected a color, such as '#FF8800'");
				}

				return new Color(Integer.parseInt(hex, 1, 3, 16), Integer.parseInt(hex, 3, 5, 16), Integer.parseInt(hex, 5, 7, 16));
			}
		);
	}

	record Theme(Color accent) {}

	@Test
	void customTypes() {
		assertEquals(new Theme(new Color(255, 136, 0)), Soml.decode("accent: '#FF8800'", Theme.class));
		assertEquals("accent: '#FF8800'\n", Soml.encode(new Theme(new Color(255, 136, 0))));
	}

	@Test
	void changeDocument() {
		var document = SomlDocument.parse("""
			# The edge service.
			port: 0x1F90 # The default.
			server: {host: 'a'}
			""");

		document.set(List.of("port"), 8080);
		document.set(List.of("server", "port"), 443);
		document.set(List.of("replicas"), 3);
		document.remove(List.of("server", "host"));
		assertEquals("# The edge service.\nport: 8080 # The default.\nserver: {port: 443}\nreplicas: 3\n", document.toString());
	}

	@Test
	void format() {
		assertEquals("pool: {min: 2, max: 16} # Connections\n", Soml.format("pool: {min: 2,   max: 16,} # Connections"));
		assertEquals("pool: {\n\tmin: 2\n\tmax: 16\n}\n", Soml.format("pool: {\nmin: 2, max: 16}"));
	}

	sealed interface Check permits Http, Command {}

	record Http(String url) implements Check {}

	record Command(List<String> arguments) implements Check {}

	record Service(String name, List<Check> checks) {}

	@Test
	void sealedTypes() {
		var service = Soml.decode("""
			name: 'api'
			checks: [
				{type: 'Http', url: 'https://example.com/health'}
				{type: 'Command', arguments: ['pg_isready']}
			]
			""", Service.class);

		assertEquals(new Service("api", List.of(new Http("https://example.com/health"), new Command(List.of("pg_isready")))), service);
	}

	@Test
	void untrustedInput() {
		var mapper = SomlMapper.builder().maxDocumentLength(1_000_000).build();
		assertEquals(new Service("api", List.of()), mapper.decode("name: 'api'\nchecks: []", Service.class));
		assertThrows(SomlException.class, () -> mapper.decode("a: '" + "x".repeat(1_000_000) + "'", SomlValue.class));
	}

	record DefaultedServer(String host, @Nullable Integer port) {
		DefaultedServer {
			port = port == null ? 8080 : port;
		}
	}

	@Test
	void defaultValues() {
		assertEquals(new DefaultedServer("example.com", 8080), Soml.decode("host: 'example.com'", DefaultedServer.class));
		assertEquals(new DefaultedServer("example.com", 80), Soml.decode("host: 'example.com'\nport: 80", DefaultedServer.class));
		assertEquals(new DefaultedServer("example.com", 8080), Soml.decode("host: 'example.com'\nport: null", DefaultedServer.class));
		assertEquals("host: 'example.com'\nport: 8080\n", Soml.encode(Soml.decode("host: 'example.com'", DefaultedServer.class)));
	}
}
