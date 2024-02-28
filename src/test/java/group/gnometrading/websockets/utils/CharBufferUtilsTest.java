package group.gnometrading.websockets.utils;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.CharBuffer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CharBufferUtilsTest {

    @ParameterizedTest
    @MethodSource("testEndsWithArguments")
    void testEndsWith(String buffer, String check, boolean expected) {
        boolean actual = CharBufferUtils.endsWith(CharBuffer.wrap(buffer), check);
        assertEquals(expected, actual);
    }

    private static Stream<Arguments> testEndsWithArguments() {
        return Stream.of(
                Arguments.of("", "", true),
                Arguments.of("", "check", false),
                Arguments.of("check", "check", true),
                Arguments.of("123check", "check", true),
                Arguments.of("123check123", "check", false)
        );
    }

}