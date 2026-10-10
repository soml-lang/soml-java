package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InstantCalendarBuiltinsTest {
	record Deployment(Instant at) {}

	@Test
	void utcDateRolloversKeepTheSameSpellingAcrossEncodingAndEditing() {
		var cases = Map.of(
			"0001-01-02T00:00:00.000000001+23:59", "0001-01-01T00:01:00.000000001Z",
			"9999-12-30T23:59:59.999999999-23:59", "9999-12-31T23:58:59.999999999Z",
			"1900-03-01T00:30:00+01:00", "1900-02-28T23:30:00Z",
			"2000-03-01T00:30:00+01:00", "2000-02-29T23:30:00Z",
			"1969-12-31T23:59:59.999999999Z", "1969-12-31T23:59:59.999999999Z",
			"1970-01-01T00:00:00.000000001Z", "1970-01-01T00:00:00.000000001Z"
		);

		for (var entry : cases.entrySet()) {
			var source = "at: " + entry.getKey();
			var spelling = entry.getValue();
			var expected = "at: " + spelling + "\n";
			var deployment = Soml.decode(source, Deployment.class);
			assertEquals(Instant.parse(spelling), deployment.at());
			assertEquals(spelling, new SomlInstant(deployment.at()).toString());
			assertEquals(expected, Soml.encode(deployment));
			assertEquals(expected, Soml.encodeCanonical(deployment));
			assertEquals(expected, Soml.encode(Soml.parse(source)));

			var document = SomlDocument.parse("at: 0 # retained comment\n");
			document.set(List.of("at"), deployment.at());
			assertEquals("at: " + spelling + " # retained comment\n", document.toString());
		}
	}
}
