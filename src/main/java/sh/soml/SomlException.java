package sh.soml;

import java.io.Serial;
import java.util.List;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;

/**
Thrown when a document is not valid, when a valid document does not fit the type it is decoded to, or when a value cannot be encoded.

<p>The message has the path, the reason, the position, and a code frame:

<pre>
servers[1].port: Expected an int, but found the string 'eighty' at line 4, column 9

  3 | servers: [
  4 | 	{port: 'eighty'}
    | 	       ^
</pre>

<p>When a valid document has more than one value that does not fit, the message has each of them, up to 10, and {@link #errors()} lists them, while the other methods describe the first.

<p>Use {@link #reason()} for the reason alone. A text can hold characters that a terminal acts on, so the reason and the code frame replace control characters, bidirectional controls, and lone surrogates with U+FFFD.
*/
public final class SomlException extends RuntimeException {
	@Serial
	private static final long serialVersionUID = 1L;

	private static final int CONTEXT_LINES = 2;
	private static final int MAX_LINE_WIDTH = 100;

	private final String reason;
	private final int line;
	private final int column;
	// The UTF-16 index of the error in the text, or -1 when it has no position.
	private final int offset;
	// The keys and indexes are strings and integers, which can be serialized.
	@SuppressWarnings("serial")
	private final List<Object> path;
	private final String pathString;
	private final String codeFrame;
	@SuppressWarnings("serial")
	private final List<SomlException> errors;

	/**
	Creates an exception with a reason and no position, as for a {@link SomlAdapter} that rejects a value. The decoder adds the path and the position of the value.
	*/
	public SomlException(String reason) {
		this(reason, null);
	}

	/**
	Creates an exception with a reason, a cause, and no position.
	*/
	public SomlException(String reason, @Nullable Throwable cause) {
		this(sanitize(reason), List.of(), 0, 0, -1, "", cause);
	}

	private SomlException(String reason, List<Object> path, int line, int column, int offset, String codeFrame, @Nullable Throwable cause) {
		path = List.copyOf(path);
		var pathString = sanitize(formatPath(path));
		var message = pathString.isEmpty() ? reason : pathString + ": " + reason;
		super(line == 0 ? message : message + " at line " + line + ", column " + column + "\n\n" + codeFrame, cause);
		this.reason = reason;
		this.path = path;
		this.pathString = pathString;
		this.line = line;
		this.column = column;
		this.offset = offset;
		this.codeFrame = codeFrame;
		this.errors = List.of(this);
	}

	private SomlException(List<SomlException> errors, boolean isStopped) {
		errors = List.copyOf(errors);
		var first = errors.getFirst();
		super(createMessage(errors, isStopped), first.getCause());
		this.reason = first.reason;
		this.path = first.path;
		this.pathString = first.pathString;
		this.line = first.line;
		this.column = first.column;
		this.offset = first.offset;
		this.codeFrame = first.codeFrame;
		this.errors = errors;
	}

	/*
	One exception for the values of a document that do not fit, after which decoding stopped with more of them left when `isStopped` is true. It describes the first, and its message has them all.
	*/
	static SomlException of(List<SomlException> errors, boolean isStopped) {
		return errors.size() == 1 ? errors.getFirst() : new SomlException(errors, isStopped);
	}

	/*
	An error at `offset` in `source`, a UTF-16 index, with the path to the value it is about, which is empty for a syntax error.
	*/
	static SomlException at(String reason, String source, int offset, List<Object> path, @Nullable Throwable cause) {
		var line = 1;
		var lineStart = 0;

		for (var index = source.indexOf('\n', 0, offset); index != -1; index = source.indexOf('\n', index + 1, offset)) {
			line++;
			lineStart = index + 1;
		}

		var column = source.codePointCount(lineStart, offset) + 1;
		return new SomlException(sanitize(reason), path, line, column, offset, createCodeFrame(source, offset, line), cause);
	}

	static SomlException at(String reason, String source, int offset) {
		return at(reason, source, offset, List.of(), null);
	}

	/*
	An error with a path and no position, as when encoding.
	*/
	static SomlException atPath(String reason, List<Object> path, @Nullable Throwable cause) {
		return new SomlException(sanitize(reason), path, 0, 0, -1, "", cause);
	}

	/**
	What is wrong, without the path and the position.
	*/
	public String reason() {
		return reason;
	}

	int offset() {
		return offset;
	}

	/**
	The 1-based line of the error, or 0 when it has no position, as when encoding.
	*/
	public int line() {
		return line;
	}

	/**
	The 1-based column of the error, counted in Unicode scalar values, so an emoji is one column, or 0 when it has no position.
	*/
	public int column() {
		return column;
	}

	/**
	The path to the value the error is about, as keys ({@link String}) and array indexes ({@link Integer}), from the top of the document. It is empty for a syntax error, and for an error about the document as a whole.
	*/
	public List<Object> path() {
		return path;
	}

	/**
	Up to three lines ending at the error, with a caret under its position, or an empty string when it has no position.
	*/
	public String codeFrame() {
		return codeFrame;
	}

	/**
	The decoding errors in document order, with missing members after an object's other errors. This exception describes the first. At most 10 errors are returned; the message says when decoding stopped early. A record with invalid members is not constructed, so constructor validation runs only after those errors are fixed. Syntax, encoding, and Java type setup errors return a list containing only this exception.
	*/
	public List<SomlException> errors() {
		return errors;
	}

	/**
	The path as the message shows it, such as {@code servers[1].port}, or an empty string for the top of the document. A key that is not a bare key is quoted, as in {@code labels["app.kubernetes.io/name"]}, a long key is shortened, and control characters are replaced, as in the message. {@link #path()} has the keys as they are.
	*/
	public String pathString() {
		return pathString;
	}

	static String formatPath(List<Object> path) {
		// Note: Not using `StringJoiner` because indexes and quoted keys attach without the separator that bare keys need.
		var text = new StringBuilder();

		for (var component : path) {
			switch (component) {
				case Integer index -> text.append('[').append(index).append(']');
				case String key when Writer.isBareKey(key) -> {
					if (!text.isEmpty()) {
						text.append('.');
					}

					text.append(Parser.abbreviate(key, 40));
				}
				case String key -> text.append('[').append(Parser.quoteJson(Parser.abbreviate(key, 40))).append(']');
				default -> throw new IllegalStateException("Unexpected path component: " + component);
			}
		}

		return text.toString();
	}

	private static String createMessage(List<SomlException> errors, boolean isStopped) {
		var message = new StringJoiner("\n\n");

		for (var error : errors) {
			message.add(error.getMessage());
		}

		if (isStopped) {
			message.add("Decoding stopped after " + errors.size() + " errors");
		}

		return message.toString();
	}

	private static String createCodeFrame(String source, int offset, int line) {
		// Note: Not using `String.split()` because it scans and allocates lines for the whole document. Scan backward from the error for its line and up to two preceding lines.
		var lineStarts = new int[CONTEXT_LINES + 1];
		lineStarts[0] = source.lastIndexOf('\n', offset - 1) + 1;
		var count = 1;

		while (count < lineStarts.length && lineStarts[count - 1] > 0) {
			// The line feed that ends the line before is just before this line's start, so the search starts before it.
			var searchFrom = lineStarts[count - 1] - 2;
			lineStarts[count++] = source.lastIndexOf('\n', searchFrom) + 1;
		}

		var gutterWidth = String.valueOf(line).length();
		var output = new StringBuilder((MAX_LINE_WIDTH + gutterWidth + 8) * count);

		for (var index = count - 1; index >= 0; index--) {
			var lineStart = lineStarts[index];
			var number = line - index;
			var isErrorLine = index == 0;
			// Only the displayed window matters. Do not scan the rest of a minified document to find its line ending.
			var searchStart = isErrorLine ? offset : lineStart;
			var searchEnd = (int) Math.min(source.length(), (long) searchStart + MAX_LINE_WIDTH + 1);
			var lineEnd = source.indexOf('\n', searchStart, searchEnd);

			if (lineEnd == -1) {
				lineEnd = searchEnd;
			}

			var lineLength = lineEnd - lineStart;
			var start = 0;
			var end = lineLength;
			var pointerOffset = isErrorLine ? offset - lineStart : 0;

			// A long line, such as a minified document, is cut to a window around the pointer. The cut never splits a surrogate pair.
			if (lineLength > MAX_LINE_WIDTH) {
				start = Math.clamp(pointerOffset - (MAX_LINE_WIDTH / 2), 0, lineLength - MAX_LINE_WIDTH);
				end = start + MAX_LINE_WIDTH;

				if (start > 0 && Character.isSurrogatePair(source.charAt(lineStart + start - 1), source.charAt(lineStart + start))) {
					start++;
				}

				if (end < lineLength && Character.isSurrogatePair(source.charAt(lineStart + end - 1), source.charAt(lineStart + end))) {
					end--;
				}
			}

			var prefix = start > 0 ? "…" : "";
			var suffix = end < lineLength ? "…" : "";
			pointerOffset = pointerOffset - start + prefix.length();
			var lineText = sanitize(source.substring(lineStart + start, lineStart + end));

			if (!output.isEmpty()) {
				output.append('\n');
			}

			output.append(isErrorLine ? '>' : ' ').append(' ').repeat(' ', gutterWidth - String.valueOf(number).length()).append(number).append(" |");

			if (!lineText.isEmpty() || !prefix.isEmpty() || !suffix.isEmpty()) {
				output.append(' ').append(prefix).append(lineText).append(suffix);
			}

			if (isErrorLine) {
				// Keep the tabs from the line, so the caret lines up whatever the tab width is.
				output.append("\n  ").repeat(' ', gutterWidth).append(" | ");
				var paddingEnd = Math.min(pointerOffset - prefix.length(), lineText.length());

				if (!prefix.isEmpty() && pointerOffset > 0) {
					output.append(' ');
				}

				// One space per character before the caret, which is one per code point, so that an emoji takes one.
				for (var position = 0; position < paddingEnd; position += Character.charCount(lineText.codePointAt(position))) {
					output.append(lineText.charAt(position) == '\t' ? '\t' : ' ');
				}

				output.append('^');
			}
		}

		return output.toString();
	}

	/*
	Replaces every character that a terminal acts on rather than shows with U+FFFD, which is also one code unit, so the caret still lines up: control characters except tab, bidirectional controls, and lone surrogates.
	*/
	static String sanitize(String text) {
		StringBuilder output = null;
		var runStart = 0;

		for (var index = 0; index < text.length(); index++) {
			var character = text.charAt(index);

			if (character >= 0x20 && character < 0x7F) {
				continue;
			}

			if (Character.isHighSurrogate(character) && index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1))) {
				index++;
				continue;
			}

			var isUnprintable = Character.isSurrogate(character) || isBidiControl(character) || (Character.isISOControl(character) && character != '\t');

			if (isUnprintable) {
				if (output == null) {
					output = new StringBuilder(text.length());
				}

				output.append(text, runStart, index).append('\uFFFD');
				runStart = index + 1;
			}
		}

		return output == null ? text : output.append(text, runStart, text.length()).toString();
	}

	private static boolean isBidiControl(char character) {
		return character == 0x061C
			|| character == 0x200E
			|| character == 0x200F
			|| (character >= 0x202A && character <= 0x202E)
			|| (character >= 0x2066 && character <= 0x2069);
	}
}
