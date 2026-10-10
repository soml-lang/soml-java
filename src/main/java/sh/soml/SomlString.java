package sh.soml;

import java.util.Objects;

/**
A string: a sequence of Unicode scalar values without U+000D (carriage return).

@param value The string.
*/
public record SomlString(String value) implements SomlValue {
	/**
	Creates a string.

	@throws IllegalArgumentException When {@code value} contains a carriage return (U+000D), which is not representable, or a lone surrogate, which is not a Unicode scalar value.
	*/
	public SomlString {
		Objects.requireNonNull(value, "value");

		// The parser has checked its strings already, with the rest of the document.
		if (!Parser.CURRENT.isBound() || Parser.CURRENT.get().checkedString != value) {
			checkText(value, "A string");
		}
	}

	/**
	The string as canonical form writes it: {@code '...'} when it needs no escapes, and otherwise {@code "..."}.
	*/
	@Override
	public String toString() {
		return Writer.formatString(value);
	}

	/**
	Throws when {@code text} holds a carriage return or a lone surrogate. {@code kind} names the text in the message, such as "A key".
	*/
	static void checkText(String text, String kind) {
		// Note: Not using `CharsetEncoder.canEncode()` because it accepts carriage returns and cannot distinguish our validation errors.
		for (var index = 0; index < text.length(); index++) {
			var character = text.charAt(index);

			if (character == '\r') {
				throw new IllegalArgumentException(kind + " cannot contain a carriage return (U+000D), which is not representable");
			}

			if (Character.isSurrogate(character)) {
				if (index + 1 < text.length() && Character.isSurrogatePair(character, text.charAt(index + 1))) {
					index++;
				} else {
					throw new IllegalArgumentException(kind + " cannot contain a lone surrogate, which is not a Unicode scalar value");
				}
			}
		}
	}
}
