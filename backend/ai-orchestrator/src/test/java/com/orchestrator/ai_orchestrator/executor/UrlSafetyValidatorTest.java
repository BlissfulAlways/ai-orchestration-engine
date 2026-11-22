package com.orchestrator.ai_orchestrator.executor.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Uses only IP addresses, so it needs no network and no DNS. */
class UrlSafetyValidatorTest {

    private final UrlSafetyValidator validator = new UrlSafetyValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/",
            "http://localhost/",
            "http://169.254.169.254/latest/meta-data/",   // cloud metadata
            "http://10.0.0.5/",
            "http://192.168.1.1/",
            "http://172.16.0.1/",
            "http://100.64.0.1/",                         // carrier-grade NAT
            "http://0.0.0.0/",
            "http://[::1]/",
            "http://[fc00::1]/",                          // IPv6 private range
            "http://[::ffff:127.0.0.1]/",                 // IPv4 hidden inside IPv6
            "http://2130706433/",                         // 127.0.0.1 written as one number
            "http://127.1/",
            "ftp://93.184.216.34/",
            "file:///etc/passwd",
            "http://user:secret@93.184.216.34/",
            "http://93.184.216.34:8080/",                 // unusual port
            "not a url",
            ""
    })
    void blocksUnsafeUrls(String url) {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(url));
    }

    @Test
    void allowsAPublicAddressOnDefaultPorts() {
        assertEquals("93.184.216.34", validator.validate("http://93.184.216.34/page").getHost());
        assertEquals(443, validator.validate("https://93.184.216.34:443/").getPort());
    }
}