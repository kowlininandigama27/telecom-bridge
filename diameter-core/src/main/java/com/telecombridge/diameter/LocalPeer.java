package com.telecombridge.diameter;

import java.net.InetAddress;
import java.util.Objects;

/**
 * Identity this node advertises in base-protocol messages.
 *
 * @param originHost    Origin-Host (DiameterIdentity, FQDN)
 * @param originRealm   Origin-Realm
 * @param hostIpAddress Host-IP-Address advertised in CER/CEA
 * @param vendorId      Vendor-Id (0 = IETF, as required by the reference trace)
 * @param productName   Product-Name
 * @param originStateId Origin-State-Id, must increase on every restart
 */
public record LocalPeer(String originHost, String originRealm, InetAddress hostIpAddress,
                        long vendorId, String productName, long originStateId) {

    public LocalPeer {
        Objects.requireNonNull(originHost, "originHost");
        Objects.requireNonNull(originRealm, "originRealm");
        Objects.requireNonNull(hostIpAddress, "hostIpAddress");
        Objects.requireNonNull(productName, "productName");
    }
}
