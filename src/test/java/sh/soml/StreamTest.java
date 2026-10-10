package sh.soml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class StreamTest {
	/*
	Every document of the conformance suite, valid or not, reads the same from a stream as from its bytes, and from a reader as from its text.
	*/
	@TestFactory
	Stream<DynamicTest> readsStreamsAsBytes() throws IOException {
		var root = Path.of("src/test/resources/conformance");

		return Files.walk(root).filter(path -> path.toString().endsWith(".soml")).sorted().map(path -> DynamicTest.dynamicTest(root.relativize(path).toString(), () -> {
			var bytes = Files.readAllBytes(path);
			assertEquals(outcome(() -> Soml.parse(bytes)), outcome(() -> Soml.parse(new ByteArrayInputStream(bytes))), "parse");
			assertEquals(outcome(() -> Soml.decode(bytes, Object.class)), outcome(() -> Soml.decode(new ByteArrayInputStream(bytes), Object.class)), "decode");

			var text = new String(bytes, StandardCharsets.UTF_8);
			assertEquals(outcome(() -> Soml.parse(text)), outcome(() -> Soml.parse(new StringReader(text))), "parse a reader");
			assertEquals(outcome(() -> Soml.decode(text, Object.class)), outcome(() -> Soml.decode(new StringReader(text), Object.class)), "decode a reader");
		}));
	}

	private interface Read {
		Object run() throws IOException;
	}

	// The value, or the reason and the position of the error.
	private static Object outcome(Read read) throws IOException {
		try {
			return read.run();
		} catch (SomlException error) {
			return List.of(error.reason(), error.line(), error.column(), error.path());
		}
	}

	record Server(String host, int port) {}

	@Test
	void writesStreams() throws IOException {
		var server = new Server("ünïcode", 80);
		var writer = new StringWriter();
		Soml.encode(server, writer);
		assertEquals(Soml.encode(server), writer.toString());

		var output = new ByteArrayOutputStream();
		Soml.encode(server, output);
		assertArrayEquals(Soml.encode(server).getBytes(StandardCharsets.UTF_8), output.toByteArray());
		assertEquals(server, Soml.decode(new ByteArrayInputStream(output.toByteArray()), Server.class));
		assertEquals(server, Soml.decode(new StringReader(writer.toString()), new TypeReference<Server>() {}));

		// Nothing is written for a value that cannot be encoded.
		var failed = new StringWriter();
		assertThrows(SomlException.class, () -> Soml.encode(List.of(Double.NaN), failed));
		assertEquals("", failed.toString());
	}

	@Test
	void readsEverySource() throws IOException {
		var text = "host: 'a'\nport: 1\n";
		var bytes = text.getBytes(StandardCharsets.UTF_8);
		var expected = new Server("a", 1);
		var type = new TypeReference<Server>() {};
		var file = Files.createTempFile("soml", ".soml");

		try {
			Files.write(file, bytes);
			assertEquals(Soml.parse(text), Soml.parse(file));
			assertEquals(expected, Soml.decode(file, Server.class));
			assertEquals(expected, Soml.decode(file, type));
		} finally {
			Files.delete(file);
		}

		assertEquals(expected, Soml.decode(bytes, type));
		assertEquals(expected, Soml.decode(new ByteArrayInputStream(bytes), type));
		assertEquals(expected, Soml.decode(new StringReader(text), Server.class));
		assertEquals(expected, Soml.fromValue(Soml.parse(text), type));
	}

	@Test
	void doesNotCloseStreams() throws IOException {
		var isClosed = new boolean[1];
		var input = new ByteArrayInputStream("a: 1\n".getBytes(StandardCharsets.UTF_8)) {
			@Override
			public void close() {
				isClosed[0] = true;
			}
		};

		Soml.decode(input, Map.class);
		assertEquals(false, isClosed[0]);

		var calls = new ArrayList<String>();
		var reader = new StringReader("a: 1\n") {
			@Override
			public void close() {
				calls.add("reader close");
			}
		};
		var writer = new StringWriter() {
			@Override
			public void flush() {
				calls.add("writer flush");
			}

			@Override
			public void close() {
				calls.add("writer close");
			}
		};
		var output = new ByteArrayOutputStream() {
			@Override
			public void flush() {
				calls.add("stream flush");
			}

			@Override
			public void close() {
				calls.add("stream close");
			}
		};

		Soml.decode(reader, Map.class);
		Soml.encode(Map.of("a", 1), writer);
		Soml.encode(Map.of("a", 1), output);
		assertEquals(List.of(), calls);
	}

	@Test
	void limitsTheLengthOfDocuments() throws IOException {
		var mapper = SomlMapper.builder().maxDocumentLength(10).build();
		var fits = "a: [1, 2]\n";
		var tooLong = "a: [1, 22]\n";
		assertEquals(10, fits.length());

		assertEquals(Map.of("a", List.of(1L, 2L)), mapper.decode(fits, Map.class));
		assertEquals(Map.of("a", List.of(1L, 2L)), mapper.decode(fits.getBytes(StandardCharsets.UTF_8), Map.class));
		assertEquals("The document is longer than the limit of 10 characters", assertThrows(SomlException.class, () -> mapper.decode(tooLong, Map.class)).reason());
		assertEquals("The document is longer than the limit of 10 bytes", assertThrows(SomlException.class, () -> mapper.decode(tooLong.getBytes(StandardCharsets.UTF_8), Map.class)).reason());
		assertEquals("The document is longer than the limit of 10 characters", assertThrows(SomlException.class, () -> mapper.decode(new StringReader(tooLong), Map.class)).reason());
		// The limit is in bytes for UTF-8, where `é` is two.
		assertThrows(SomlException.class, () -> mapper.decode("a: 'ééé'\n".getBytes(StandardCharsets.UTF_8), Map.class));
		assertEquals(Map.of("a", "ééé"), mapper.decode("a: 'ééé'\n", Map.class));

		// A stream that never ends is read only one byte past the limit.
		var endless = new CountingStream();
		assertThrows(SomlException.class, () -> mapper.decode(endless, Map.class));
		assertEquals(11, endless.count);

		var file = Files.createTempFile("soml", ".soml");

		try {
			Files.writeString(file, tooLong);
			assertThrows(SomlException.class, () -> mapper.decode(file, Map.class));
		} finally {
			Files.delete(file);
		}

		assertEquals(Map.of(), SomlMapper.builder().maxDocumentLength(3).build().decode("{}\n", Map.class));
		// An empty document is within a limit of 0, and is then refused because it is empty.
		assertTrue(assertThrows(SomlException.class, () -> SomlMapper.builder().maxDocumentLength(0).build().decode("", SomlValue.class)).reason().startsWith("A document must contain"));
		assertThrows(IllegalArgumentException.class, () -> SomlMapper.builder().maxDocumentLength(-1));
	}

	private static final class CountingStream extends InputStream {
		int count;

		@Override
		public int read() {
			count++;
			return 'a';
		}
	}
}
