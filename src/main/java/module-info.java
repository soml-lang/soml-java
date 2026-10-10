import org.jspecify.annotations.NullMarked;

/**
Read and write SOML, a config format for humans.

<p>Start with {@link sh.soml.Soml}.
*/
@NullMarked
module sh.soml {
	// Optional at run time, and visible to the code that uses the library.
	requires static transitive org.jspecify;

	exports sh.soml;
}
