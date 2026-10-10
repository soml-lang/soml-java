package sh.soml;

import java.util.Locale;
import java.util.Objects;

/**
How the name of a record component or a field becomes a key. {@link SomlName} on a component or a field takes precedence.
*/
public enum KeyNaming {
	/**
	The Java name as it is: {@code deployedAt}.
	*/
	JAVA_NAME,

	/**
	Words in lowercase, separated by {@code -}: {@code deployedAt} becomes {@code deployed-at}, and {@code httpURL} becomes {@code http-url}. The usual style of SOML keys.
	*/
	KEBAB_CASE,

	/**
	Words in lowercase, separated by {@code _}: {@code deployedAt} becomes {@code deployed_at}.
	*/
	SNAKE_CASE;

	/**
	The key for a Java name.
	*/
	public String apply(String name) {
		Objects.requireNonNull(name, "name");
		return switch (this) {
			case JAVA_NAME -> name;
			case KEBAB_CASE -> separateWords(name, '-');
			case SNAKE_CASE -> separateWords(name, '_');
		};
	}

	/*
	A word starts at an uppercase letter that follows a lowercase letter or a digit, and at the last uppercase letter of a run that a lowercase letter follows, so `parseHTTPResponse` is `parse`, `http`, and `response`.
	*/
	private static String separateWords(String name, char separator) {
		// Note: Not using a lookbehind regex because the tested replacement missed boundaries after supplementary uppercase letters.
		StringBuilder output = null;
		var wordStart = 0;

		for (var index = 0; index < name.length();) {
			var character = name.codePointAt(index);
			var next = index + Character.charCount(character);

			if (Character.isUpperCase(character) && index > 0) {
				var previous = name.codePointBefore(index);
				var isAfterLowercase = Character.isLowerCase(previous) || Character.isDigit(previous);
				var isBeforeLowercase = Character.isUpperCase(previous) && next < name.length() && Character.isLowerCase(name.codePointAt(next));

				if (isAfterLowercase || isBeforeLowercase) {
					if (output == null) {
						output = new StringBuilder(name.length() + 4);
					}

					output.append(name, wordStart, index).append(separator);
					wordStart = index;
				}
			}

			index = next;
		}

		return (output == null ? name : output.append(name, wordStart, name.length()).toString()).toLowerCase(Locale.ROOT);
	}
}
