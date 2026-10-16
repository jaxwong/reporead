package com.reporead.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;

/** The server binds to 127.0.0.1 or does not start; the whole trust model assumes only loopback clients. */
class AppSecurityTest {
    private static void bind(String address) throws Exception {
        var factory = new TomcatServletWebServerFactory();
        if (address != null) factory.setAddress(InetAddress.getByName(address));
        new AppSecurity().loopbackOnly().customize(factory);
    }

    @Test
    void loopbackIsTheOnlyAddressItStartsOn() throws Exception {
        bind("127.0.0.1");
        // No address (every interface), every interface explicitly, IPv6 loopback, which the proxy does not use, and a
        // LAN address.
        for (String address : new String[] {null, "0.0.0.0", "::1", "192.168.1.10"}) {
            var refused = assertThrows(IllegalArgumentException.class, () -> bind(address), String.valueOf(address));
            assertTrue(refused.getMessage().startsWith("RepoRead must bind to 127.0.0.1"), refused.getMessage());
        }
    }
}
