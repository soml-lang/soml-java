package sh.soml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
A document that you change one value at a time, while everything else stays as it is written: comments, blank lines, indentation, member order, and the spelling of every other value. For tools that update a config file, such as a {@code set} command or a dependency bumper.

<pre>{@code
var document = SomlDocument.parse("""
	# The edge service.
	port: 0x1F90 # The default.
	server: {host: 'a'}
	""");

document.set(List.of("port"), 8080);
document.set(List.of("server", "port"), 443);
document.set(List.of("replicas"), 3);
document.remove(List.of("server", "host"));

document.toString();
//=> "# The edge service.\nport: 8080 # The default.\nserver: {port: 443}\nreplicas: 3\n"
}</pre>

<p>A path is a list of keys ({@link String}) and array indexes ({@link Integer}), from the top of the document. Changes follow the editing rules of the spec, so tools agree on the result, and a change to a formatted document leaves it formatted:

<ul>
<li>A new value is written as {@link Soml#encode(Object)} writes it, at the indentation of its line, and on one line inside a container that is on one line, so {@code {a: 1}} becomes {@code {a: 1, b: 2}}.</li>
<li>A new member goes after the last member of its object, and missing objects on the path are created. A new item goes at the end of its array, at the index that is its length.</li>
<li>A removed member or item takes its comma and the comments it owns: those after it on its line, and the block comments before it on its line. A comment on a line of its own stays.</li>
<li>Removing something that does not exist changes nothing, and {@link #remove(List)} says whether it removed something. A path that does not fit the document, such as a key where an array is, is an error.</li>
</ul>

<p>An unchanged document prints the exact text it was parsed from. A document is not safe to change from several threads at once.
*/
public final class SomlDocument {
	private String text;
	private SomlValue value;

	private SomlDocument(String text, SomlValue value) {
		this.text = text;
		this.value = value;
	}

	/**
	Parses a document.

	@throws SomlException When the document is not valid.
	*/
	public static SomlDocument parse(String text) {
		return new SomlDocument(text, Parser.parse(text));
	}

	/**
	Parses a document of UTF-8 bytes.

	@throws SomlException When the bytes are not valid UTF-8 or the document is not valid.
	*/
	public static SomlDocument parse(byte[] utf8) {
		return parse(Parser.decode(utf8));
	}

	/**
	Parses a file.

	@throws IOException When the file cannot be read.
	@throws SomlException When the file is not valid UTF-8 or the document is not valid.
	*/
	public static SomlDocument parse(Path file) throws IOException {
		return parse(Files.readAllBytes(file));
	}

	/**
	The value of the whole document, which is a {@link SomlObject} or a {@link SomlArray}.
	*/
	public SomlValue value() {
		return value;
	}

	/**
	The value at {@code path}, or null when the document has none there, also when the path has a key where an array is, an index where an object is, or leads through a scalar.

	@throws IllegalArgumentException When {@code path} has something other than keys and non-negative indexes, or has a key with a carriage return (U+000D) or a lone surrogate, which no key can hold.
	*/
	public @Nullable SomlValue get(List<?> path) {
		var current = value;

		for (var component : path) {
			checkPathComponent(component);
			current = switch (current) {
				case SomlObject object when component instanceof String key -> object.get(key);
				case SomlArray(var items) when component instanceof Integer index && index < items.size() -> items.get(index);
				case null, default -> null;
			};
		}

		return current;
	}

	/**
	Sets the value at {@code path}, replacing it, or adding it when it does not exist. The value is converted as {@link Soml#toValue(Object)} converts it, so it can be a {@link SomlValue}, a record, a map, a list, a scalar, or {@code null}, which is written as {@code null}. Use {@link SomlMapper#toValue(Object)} for a mapper's options.

	@throws IllegalArgumentException When {@code path} is empty, has something other than keys and non-negative indexes, or has a key with a carriage return (U+000D) or a lone surrogate, which no key can hold.
	@throws SomlException When the path does not fit the document, as with a key where an array is, an index past the end of an array, or a path through a scalar, when the value cannot be represented, or when the change would nest the document more than 100 levels deep.
	*/
	public void set(List<?> path, @Nullable Object value) {
		apply(Editor.edit(text, checkPath(path), Soml.toValue(value)));
	}

	/**
	Removes the value at {@code path}, and returns whether there was one. A missing member, a missing object or array on the way, and an index at or past the end of its array are not errors, so removing the same path twice is safe.

	@throws IllegalArgumentException When {@code path} is empty, has something other than keys and non-negative indexes, or has a key with a carriage return (U+000D) or a lone surrogate, which no key can hold.
	@throws SomlException When the path does not fit the document, as with a key where an array is or a path through a scalar.
	*/
	public boolean remove(List<?> path) {
		return apply(Editor.edit(text, checkPath(path), null));
	}

	/*
	Commits a completed edit, keeping text and value together, and reports whether it changed anything.
	*/
	private boolean apply(Editor.Result result) {
		if (result.value() == null) {
			return false;
		}

		text = result.text();
		value = result.value();
		return true;
	}

	/**
	The text of the document, which is exactly the parsed text until a change.
	*/
	@Override
	public String toString() {
		return text;
	}

	private static List<Object> checkPath(List<?> path) {
		Objects.requireNonNull(path, "path");

		if (path.isEmpty()) {
			throw new IllegalArgumentException("The path must have at least one key or index");
		}

		var checked = path.toArray();

		for (var component : checked) {
			checkPathComponent(component);
		}

		return Arrays.asList(checked);
	}

	private static void checkPathComponent(@Nullable Object component) {
		switch (component) {
			case String key -> SomlString.checkText(key, "A key");
			case Integer index when index >= 0 -> {
			}
			case null, default -> throw new IllegalArgumentException("A path is made of keys (String) and non-negative array indexes (Integer), but it has " + (component == null ? "null" : component.getClass().getSimpleName() + " " + component));
		}
	}
}
