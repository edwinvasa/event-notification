package com.edwin.eventnotification.adapter.out.webhook;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.springframework.stereotype.Component;

@Component
public class SsrfSafeAddressResolver {

    public InetAddress resolveValidated(String hostname) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(hostname);
        validateAll(hostname, addresses);
        return addresses[0];
    }

    void validateAll(String hostname, InetAddress[] addresses) {
        for (InetAddress address : addresses) {
            if (isUnsafe(address)) {
                throw new SecurityPolicyViolationException(
                        "Hostname " + hostname + " resolved to a disallowed address: " + address.getHostAddress());
            }
        }
    }

    boolean isUnsafe(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || address.isAnyLocalAddress();
    }
}
