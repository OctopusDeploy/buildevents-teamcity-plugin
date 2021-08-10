package com.octopus.teamcity.opentelemetry.server;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogMasker {

    private LogMasker() {
        throw new IllegalStateException("Utility class LogMasker should not be instantiated ");
    }

    public static String mask(String message) {
        final String API_KEY_REGEX = "([a-z0-9]{31})";
        final Pattern apikeyPattern = Pattern.compile(API_KEY_REGEX);
        final String API_KEY_REPLACEMENT_REGEX = "XXXXXXXXXXXXXXXX";

        StringBuilder buffer = new StringBuilder();

        Matcher matcher = apikeyPattern.matcher(message);
        while (matcher.find()) {
            matcher.appendReplacement(buffer, API_KEY_REPLACEMENT_REGEX);
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
