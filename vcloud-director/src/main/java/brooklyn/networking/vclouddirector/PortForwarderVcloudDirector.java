/*
 * Copyright 2013-2014 by Cloudsoft Corporation Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brooklyn.networking.vclouddirector;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.ConfigKey;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.location.Location;
import brooklyn.location.MachineLocation;
import brooklyn.location.PortRange;
import brooklyn.location.access.PortForwardManager;
import brooklyn.location.access.PortForwardManagerAuthority;
import brooklyn.location.basic.PortRanges;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.networking.subnet.PortForwarder;
import brooklyn.networking.subnet.SubnetTier;
import brooklyn.networking.vclouddirector.NatService.OpenPortForwardingConfig;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.HasNetworkAddresses;
import brooklyn.util.net.Protocol;

import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;

public class PortForwarderVcloudDirector implements PortForwarder {

    /*
     * TODO 
     * - How does the extra VM-provisioning config (e.g. networkId etc) get injected into the JcloudsLocation?
     * - 
     */
    
    private static final Logger LOG = LoggerFactory.getLogger(PortForwarderVcloudDirector.class);

    public static final ConfigKey<String> NETWORK_ID = ConfigKeys.newStringConfigKey(
            "advancednetworking.vcloud.network.id",
            "Optionally specify the id of an existing network");

    public static final ConfigKey<String> NETWORK_PUBLIC_IP = ConfigKeys.newStringConfigKey(
            "advancednetworking.vcloud.network.publicip",
            "Optionally specify an existing public IP associated with the network");

    private final PortForwardManager portForwardManager;

    private final Object mutex = new Object();
    
    private NatService service;

    private SubnetTier subnetTier;
    
    public PortForwarderVcloudDirector() {
        this(new PortForwardManagerAuthority());
    }

    public PortForwarderVcloudDirector(PortForwardManager portForwardManager) {
        this.portForwardManager = portForwardManager;
    }

    public PortForwardManager getPortForwardManager() {
        return portForwardManager;
    }

    @Override
    public void inject(Entity owner, List<Location> locations) {
        subnetTier = (SubnetTier) owner;
        JcloudsLocation jcloudsLocation = (JcloudsLocation) Iterables.find(locations, Predicates.instanceOf(JcloudsLocation.class));
        service = NatService.builder().location(jcloudsLocation).build();
    }
    
    @Override
    public String openGateway() {
        // TODO Handle case where publicIp not already supplied
        String publicIp = subnetTier.getConfig(NETWORK_PUBLIC_IP);
        return publicIp;
    }

    @Override
    public String openStaticNat(Entity serviceToOpen) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void openFirewallPort(Entity entity, int port, Protocol protocol, Cidr accessingCidr) {
        openFirewallPortRange(entity, PortRanges.fromInteger(port), protocol, accessingCidr);
    }

    @Override
    public void openFirewallPortRange(Entity entity, PortRange portRange, Protocol protocol, Cidr accessingCidr) {
        throw new UnsupportedOperationException();
    }

    public HostAndPort openPortForwarding(MachineLocation targetVm, int targetPort, Optional<Integer> optionalPublicPort,
            Protocol protocol, Cidr accessingCidr) {
        // TODO assumes targetVM is in the JcloudsLocation injected earlier; could check
        return openPortForwarding((HasNetworkAddresses)targetVm, targetPort, optionalPublicPort, protocol, accessingCidr);
    }

    @Override
    public HostAndPort openPortForwarding(HasNetworkAddresses targetVm, int targetPort, Optional<Integer> optionalPublicPort,
            Protocol protocol, Cidr accessingCidr) {
        String targetIp = Iterables.getFirst(Iterables.concat(targetVm.getPrivateAddresses(), targetVm.getPublicAddresses()), null);
        if (targetIp==null) {
            throw new IllegalStateException("Failed to open port-forarding for machine "+targetVm+" because its location has no target ip: "+targetVm);
        }

        HostAndPort targetSide = HostAndPort.fromParts(targetIp, targetPort);
        return openPortForwarding(targetSide, optionalPublicPort, protocol, accessingCidr);
    }
    
    @Override
    public HostAndPort openPortForwarding(HostAndPort target, Optional<Integer> optionalPublicPort, Protocol protocol, Cidr accessingCidr) {
        // TODO should associate ip:port with PortForwardManager; but that takes location param
        //      getPortForwardManager().associate(publicIp, publicPort, targetVm, targetPort);
        // TODO Could check old mapping, and re-use that public port
        // TODO Given the VM, could look up to find the network (rather than hard-coding!)
        // TODO Pass cidr in vcloud-director call
        PortForwardManager pfw = getPortForwardManager();
        String networkId = subnetTier.getConfig(NETWORK_ID);
        String publicIp = subnetTier.getConfig(NETWORK_PUBLIC_IP);

        int publicPort;
        if (optionalPublicPort.isPresent()) {
            publicPort = optionalPublicPort.get();
            pfw.acquirePublicPortExplicit(publicIp, publicPort);
        } else {
            publicPort = pfw.acquirePublicPort(publicIp);
        }
        
        try {
            // must synchronize because adding NAT rule involves: 1) download existing NAT rules; 
            // 2) add new rule to collection; 3) upload all NAT rules. Therefore if two threads
            // execute concurrently we may not get both new NAT rules in the resulting uploaded set.
            synchronized (mutex) {
                HostAndPort result = service.openPortForwarding(new OpenPortForwardingConfig()
                        .networkId(networkId)
                        .publicIp(publicIp)
                        .protocol(Protocol.TCP)
                        .target(target)
                        .publicPort(publicPort));
                LOG.debug("Enabled port-forwarding for {}, via {}, on ", new Object[] {target, result, subnetTier});
                return result;
            }
        } catch (Exception e) {
            LOG.error("Failed creating port forwarding rule on "+this+" to "+target, e);
            // it might already be created, so don't crash and burn too hard!
            return HostAndPort.fromParts(publicIp, publicPort);
        }
    }

    @Override
    public boolean isClient() {
        return false;
    }
}