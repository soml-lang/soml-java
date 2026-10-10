package sh.soml;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/*
A document as it is written: every member, item, key, and scalar with its range in the text, and every comment, for the formatter and the editor. A range is a start and an end as UTF-16 offsets into the text.

The document is validated by the parser first, so the builder reads it again assuming it is valid. It is a port of the JavaScript reference's `parseTree()`, with only what the formatter and the editor use.
*/
final class SyntaxTree {
	/*
	A member of an object or an item of an array.
	*/
	sealed interface Item permits MemberNode, ValueNode {
		int start();

		int end();
	}

	sealed interface ValueNode extends Item permits Container, ScalarNode {}

	sealed interface Container extends ValueNode permits ObjectNode, ArrayNode {
		List<? extends Item> items();

		/*
		False only for a top-level object written without braces.
		*/
		boolean isBraced();
	}

	record ObjectNode(List<MemberNode> members, boolean isBraced, int start, int end) implements Container {
		@Override
		public List<MemberNode> items() {
			return members;
		}

		@Nullable MemberNode member(String key) {
			// The keys of an object are unique in a valid document, so the first match is the only one.
			for (var member : members) {
				if (member.key().matches(key)) {
					return member;
				}
			}

			return null;
		}
	}

	record ArrayNode(List<ValueNode> elements, int start, int end) implements Container {
		@Override
		public List<ValueNode> items() {
			return elements;
		}

		@Override
		public boolean isBraced() {
			return true;
		}
	}

	record MemberNode(KeyNode key, ValueNode value) implements Item {
		@Override
		public int start() {
			return key.start();
		}

		@Override
		public int end() {
			return value.end();
		}
	}

	/*
	A key, whose value is decoded only when the editor looks it up. Formatting only needs its range.
	*/
	record KeyNode(String text, int start, int end) {
		String value() {
			var first = text.charAt(start);
			return first == '\'' || first == '"' ? Parser.readQuoted(text, start, end) : text.substring(start, end);
		}

		boolean matches(String key) {
			var first = text.charAt(start);
			var isQuoted = first == '\'' || first == '"';
			var contentStart = isQuoted ? start + 1 : start;
			var contentEnd = isQuoted ? end - 1 : end;

			// Escapes can shorten the written key, but can never make its decoded UTF-16 length longer.
			if (key.length() > contentEnd - contentStart) {
				return false;
			}

			if (first == '"' && text.indexOf('\\', contentStart, contentEnd) != -1) {
				return value().equals(key);
			}

			return key.length() == contentEnd - contentStart && text.startsWith(key, contentStart);
		}
	}

	/*
	A string, a number, a keyword, a date, an instant, or a duration, as its text.
	*/
	record ScalarNode(boolean isBlockString, int start, int end) implements ValueNode {}

	/*
	A line comment, from `#` to the end of its line, or a block comment.
	*/
	record Comment(boolean isBlock, int start, int end) {}

	final String text;
	final Container body;
	// In source order.
	final List<Comment> comments;

	private SyntaxTree(String text, Container body, List<Comment> comments) {
		this.text = text;
		this.body = body;
		this.comments = comments;
	}

	/*
	@throws SomlException When the document is not valid.
	*/
	static SyntaxTree parse(String text) {
		Parser.validate(text);
		return ofValid(text);
	}

	/*
	Builds syntax for text that the parser has already validated, as in a SomlDocument being edited.
	*/
	static SyntaxTree ofValid(String text) {
		return new Builder(text).build();
	}

	private static final class Builder {
		private final String text;
		private final List<Comment> comments = new ArrayList<>();
		private int index;

		Builder(String text) {
			this.text = text;
		}

		// Past the end, -1, which matches no character, so the scans stop at the end.
		private int code(int offset) {
			return offset < text.length() ? text.charAt(offset) : -1;
		}

		private int code() {
			return code(index);
		}

		SyntaxTree build() {
			skipTrivia();
			Container body;

			if (code() == '{') {
				body = object();
			} else if (code() == '[') {
				body = array();
			} else {
				body = bareObject();
			}

			skipTrivia();
			return new SyntaxTree(text, body, List.copyOf(comments));
		}

		private void skipTrivia() {
			for (;;) {
				var code = code();
				var start = index;

				if (code == '\n' || Parser.isSpace(code)) {
					index++;
				} else if (code == '#') {
					var lineEnd = text.indexOf('\n', start);
					index = lineEnd == -1 ? text.length() : lineEnd;
					comments.add(new Comment(false, start, index));
				} else if (code == '/' && code(start + 1) == '*') {
					// The `*/` begins after the `/*`, so the `*` of `/*/` does not close it, and the document is valid, so the `*/` exists.
					index = text.indexOf("*/", start + 2) + 2;
					comments.add(new Comment(true, start, index));
				} else {
					return;
				}
			}
		}

		private ObjectNode bareObject() {
			var members = new ArrayList<MemberNode>();

			// The document is valid, so after a member and the trivia after it, either the text ends or another member begins.
			do {
				members.add(member());
				skipTrivia();
			} while (index < text.length());

			return new ObjectNode(List.copyOf(members), false, members.getFirst().start(), members.getLast().end());
		}

		private ObjectNode object() {
			var start = index;
			var members = items('}', this::member);
			return new ObjectNode(members, true, start, index);
		}

		private ArrayNode array() {
			var start = index;
			var elements = items(']', this::value);
			return new ArrayNode(elements, start, index);
		}

		/*
		The items between an opening bracket and its closing one, which is consumed too. The parser has already checked the separators.
		*/
		private <T> List<T> items(char closing, Supplier<T> parseItem) {
			index++;
			skipTrivia();

			if (code() == closing) {
				index++;
				return List.of();
			}

			var items = new ArrayList<T>();

			while (code() != closing) {
				items.add(parseItem.get());
				skipTrivia();

				if (code() == ',') {
					index++;
					skipTrivia();
				}
			}

			index++;
			return List.copyOf(items);
		}

		private MemberNode member() {
			var key = key();
			// Nothing may come between a key and its `:`.
			index++;
			skipTrivia();
			var value = value();
			return new MemberNode(key, value);
		}

		private KeyNode key() {
			var start = index;
			var code = code();

			if (code == '\'' || code == '"') {
				index = singleLineStringEnd(start);
			} else {
				while (Writer.isBareKeyCharacter(code())) {
					index++;
				}
			}

			return new KeyNode(text, start, index);
		}

		private ValueNode value() {
			var code = code();

			if (code == '{') {
				return object();
			}

			if (code == '[') {
				return array();
			}

			var start = index;

			if (code == '\'' || code == '"') {
				// In a valid document, a quote never follows a value directly, so three quotes always open a block string.
				if (code(start + 1) == code && code(start + 2) == code) {
					index = blockStringEnd(start, (char) code);
					return new ScalarNode(true, start, index);
				}

				index = singleLineStringEnd(start);
				return new ScalarNode(false, start, index);
			}

			// Any other value is one run of scalar characters: a keyword, a number, a date, an instant, or a duration.
			index++;

			// The same characters as `Parser.isNumberCharacter()`, which the keywords are made of too. The two must agree.
			while (Parser.isNumberCharacter(code())) {
				index++;
			}

			return new ScalarNode(false, start, index);
		}

		/*
		`'...'` ends at the next `'`. `"..."` ends at the next `"` that is not escaped, and a `\` always starts an escape that holds no quote.
		*/
		private int singleLineStringEnd(int start) {
			if (text.charAt(start) == '\'') {
				return text.indexOf('\'', start + 1) + 1;
			}

			for (var end = text.indexOf('"', start + 1);; end = text.indexOf('"', end + 1)) {
				var backslashStart = end;

				while (text.charAt(backslashStart - 1) == '\\') {
					backslashStart--;
				}

				if ((end - backslashStart) % 2 == 0) {
					return end + 1;
				}
			}
		}

		/*
		The end of the closing delimiter: the first line whose first content after spaces and tabs is a run of exactly as many quotes as the opening delimiter.
		*/
		private int blockStringEnd(int start, char quote) {
			var delimiterLength = 0;

			while (code(start + delimiterLength) == quote) {
				delimiterLength++;
			}

			// The opening delimiter is followed directly by a line break, and the document is valid, so a closing line exists.
			for (var lineStart = text.indexOf('\n', start) + 1;; lineStart = text.indexOf('\n', lineStart) + 1) {
				var delimiterStart = lineStart;

				while (Parser.isSpace(code(delimiterStart))) {
					delimiterStart++;
				}

				var runLength = 0;

				while (code(delimiterStart + runLength) == quote) {
					runLength++;
				}

				if (runLength == delimiterLength) {
					return delimiterStart + delimiterLength;
				}
			}
		}
	}
}
