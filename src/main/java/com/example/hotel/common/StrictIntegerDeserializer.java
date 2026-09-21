package com.example.hotel.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/**
 * Deserializes an {@link Integer} only from a JSON integer token. Jackson's default silently truncates a decimal
 * such as {@code 1.5} to {@code 1} and coerces numeric strings; fields that must be whole numbers (guest counts) use
 * this deserializer so such input is rejected instead of being normalized. A JSON {@code null} still yields
 * {@code null}, which Bean Validation then rejects.
 */
public class StrictIntegerDeserializer extends JsonDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return (Integer) context.handleUnexpectedToken(Integer.class, parser);
        }
        return parser.getIntValue();
    }
}
