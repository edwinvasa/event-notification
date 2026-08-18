package com.edwin.eventnotification.adapter.out.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;

class SsrfSafeAddressResolverTest {

    private final SsrfSafeAddressResolver resolver = new SsrfSafeAddressResolver();

    private InetAddress address(int a, int b, int c, int d) throws UnknownHostException {
        return InetAddress.getByAddress(new byte[] {(byte) a, (byte) b, (byte) c, (byte) d});
    }

    @Test
    void allowsAPublicLookingAddress() throws Exception {
        InetAddress publicAddress = address(93, 184, 216, 34);

        assertThat(resolver.isUnsafe(publicAddress)).isFalse();
    }

    @Test
    void rejectsLoopbackAddress() throws Exception {
        InetAddress loopback = address(127, 0, 0, 1);

        assertThat(resolver.isUnsafe(loopback)).isTrue();
    }

    @Test
    void rejectsPrivateRfc1918Address() throws Exception {
        InetAddress privateAddress = address(10, 0, 0, 5);

        assertThat(resolver.isUnsafe(privateAddress)).isTrue();
    }

    @Test
    void rejectsLinkLocalAddress() throws Exception {
        // 169.254.169.254 is also the well-known cloud metadata endpoint.
        InetAddress linkLocal = address(169, 254, 169, 254);

        assertThat(resolver.isUnsafe(linkLocal)).isTrue();
    }

    @Test
    void validateAllPassesWhenEveryAddressIsSafe() throws Exception {
        InetAddress[] addresses = {address(93, 184, 216, 34), address(8, 8, 8, 8)};

        resolver.validateAll("example.com", addresses);
        // no exception thrown = success
    }

    @Test
    void validateAllRejectsWhenAnySingleAddressIsUnsafe() throws Exception {
        InetAddress[] addresses = {address(93, 184, 216, 34), address(10, 0, 0, 5)};

        assertThatThrownBy(() -> resolver.validateAll("mixed.example.com", addresses))
                .isInstanceOf(SecurityPolicyViolationException.class);
    }
}
