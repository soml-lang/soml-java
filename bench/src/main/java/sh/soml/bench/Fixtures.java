package sh.soml.bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/*
The fixtures in `bench/fixtures`: the same data as SOML, JSON, and TOML. `service` is a hand-written service config of 3 KB with kebab-case keys, `platform` a config of 35 KB with many comments, and `earthquakes` 986 earthquakes as GeoJSON, 733 KB with camelCase keys. They are a copy of the `benchmark` directory of the `soml` repository, which every implementation uses, so the numbers compare across implementations. The records are for `service` and `earthquakes`, which the decoding and encoding benchmarks use.

An instant and a duration are an instant and a duration in SOML, and an ISO 8601 string, such as "2026-09-19T14:00:00Z" and "PT55S", in JSON and TOML.
*/
final class Fixtures {
	private Fixtures() {}

	static String read(String name) {
		var directory = Files.isDirectory(Path.of("fixtures")) ? Path.of("fixtures") : Path.of("bench/fixtures");

		try {
			return Files.readString(directory.resolve(name));
		} catch (IOException error) {
			throw new UncheckedIOException("Run the benchmarks from the repository root or from `bench`", error);
		}
	}

	public record Service(String name, String version, String environment, String region, Instant deployedAt, Server server, Database database, Cache cache, Queue queue, Payments payments, Limits limits, Features features, Logging logging, Telemetry telemetry, Maintenance maintenance, List<Region> regions) {}

	public record Server(String host, int port, Duration keepAlive, Duration readTimeout, Duration writeTimeout, int maxHeaderSize, Tls tls) {}

	public record Tls(String certificate, String key, String minVersion, List<String> ciphers) {}

	public record Database(String primary, List<String> replicas, Pool pool, String setup) {}

	public record Pool(int min, int max, Duration idleTimeout, Duration acquireTimeout) {}

	public record Cache(String url, Duration ttl, int maxEntries, String eviction) {}

	public record Queue(String broker, List<Exchange> exchanges, Retry retry) {}

	public record Exchange(String name, String type, boolean durable) {}

	public record Retry(int attempts, Duration initialDelay, Duration maxDelay, double multiplier) {}

	public record Payments(String provider, String currency, String capture, int reviewThreshold, Fees fees, Webhooks webhooks) {}

	public record Fees(double percentage, int fixed) {}

	public record Webhooks(String path, Duration tolerance, List<String> events) {}

	public record Limits(int requestsPerMinute, int burst, int maxBodySize, int maxItemsPerOrder, double cpu, int memoryMb) {}

	public record Features(boolean expressCheckout, boolean giftCards, boolean buyNowPayLater, boolean addressAutocomplete, boolean newTaxEngine) {}

	public record Logging(String level, String format, double sampleRate, List<String> redact, LogFile file) {}

	public record LogFile(String path, int mode, int maxSizeMb, int maxFiles) {}

	public record Telemetry(String endpoint, Duration interval, Map<String, String> attributes, Histograms histograms) {}

	public record Histograms(List<Double> requestDuration) {}

	public record Maintenance(boolean enabled, String message) {}

	public record Region(String name, int weight, boolean primary) {}

	public record Earthquakes(String type, EarthquakeMetadata metadata, List<Double> bbox, List<Feature> features) {}

	public record EarthquakeMetadata(Instant generated, String url, String title, int status, String api, int count) {}

	public record Feature(String type, Earthquake properties, Geometry geometry, String id) {}

	/*
	`felt`, `cdi`, `mmi`, and `alert` are only in some earthquakes.
	*/
	public record Earthquake(double mag, String place, Instant time, Instant updated, String url, String detail, Optional<Integer> felt, Optional<Double> cdi, Optional<Double> mmi, Optional<String> alert, String status, int tsunami, int sig, String net, String code, String ids, String sources, String types, int nst, double dmin, double rms, double gap, String magType, String type, String title) {}

	public record Geometry(String type, List<Double> coordinates) {}

	/*
	The same records for JSON and TOML, where instants and durations are strings, and a member that is left out is `null`, because Gson cannot create an `Optional`.
	*/
	public record TextService(String name, String version, String environment, String region, String deployedAt, TextServer server, TextDatabase database, TextCache cache, TextQueue queue, TextPayments payments, Limits limits, Features features, Logging logging, TextTelemetry telemetry, Maintenance maintenance, List<Region> regions) {}

	public record TextServer(String host, int port, String keepAlive, String readTimeout, String writeTimeout, int maxHeaderSize, Tls tls) {}

	public record TextDatabase(String primary, List<String> replicas, TextPool pool, String setup) {}

	public record TextPool(int min, int max, String idleTimeout, String acquireTimeout) {}

	public record TextCache(String url, String ttl, int maxEntries, String eviction) {}

	public record TextQueue(String broker, List<Exchange> exchanges, TextRetry retry) {}

	public record TextRetry(int attempts, String initialDelay, String maxDelay, double multiplier) {}

	public record TextPayments(String provider, String currency, String capture, int reviewThreshold, Fees fees, TextWebhooks webhooks) {}

	public record TextWebhooks(String path, String tolerance, List<String> events) {}

	public record TextTelemetry(String endpoint, String interval, Map<String, String> attributes, Histograms histograms) {}

	public record TextEarthquakes(String type, TextEarthquakeMetadata metadata, List<Double> bbox, List<TextFeature> features) {}

	public record TextEarthquakeMetadata(String generated, String url, String title, int status, String api, int count) {}

	public record TextFeature(String type, TextEarthquake properties, Geometry geometry, String id) {}

	public record TextEarthquake(double mag, String place, String time, String updated, String url, String detail, Integer felt, Double cdi, Double mmi, String alert, String status, int tsunami, int sig, String net, String code, String ids, String sources, String types, int nst, double dmin, double rms, double gap, String magType, String type, String title) {}
}
