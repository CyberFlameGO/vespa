// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.clustercontroller.core;

import com.yahoo.jrt.Spec;
import com.yahoo.vdslib.state.Node;
import com.yahoo.vespa.clustercontroller.core.hostinfo.ResourceUsage;

import java.util.Locale;
import java.util.Objects;

/**
 * Wrapper that identifies a resource type that has been exhausted on a given node,
 * complete with both actual usage and the limit it exceeded.
 */
public class NodeResourceExhaustion {
    public final Node node;
    public final String resourceType;
    public final ResourceUsage resourceUsage;
    public final double limit;
    public final String rpcAddress;

    public NodeResourceExhaustion(Node node, String resourceType,
                                  ResourceUsage resourceUsage, double limit,
                                  String rpcAddress) {
        this.node = node;
        this.resourceType = resourceType;
        this.resourceUsage = resourceUsage;
        this.limit = limit;
        this.rpcAddress = rpcAddress;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NodeResourceExhaustion that = (NodeResourceExhaustion) o;
        return Double.compare(that.limit, limit) == 0 &&
                Objects.equals(node, that.node) &&
                Objects.equals(resourceType, that.resourceType) &&
                Objects.equals(resourceUsage, that.resourceUsage) &&
                Objects.equals(rpcAddress, that.rpcAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(node, resourceType, resourceUsage, limit, rpcAddress);
    }

    public String toExhaustionAddedDescription() {
        return String.format(Locale.US, "%s is %.1f%% full (the configured limit is %.1f%%)",
                makeDescriptionPrefix(), resourceUsage.getUsage() * 100.0, limit * 100.0);
    }

    public String toExhaustionRemovedDescription() {
        return String.format(Locale.US, "%s (<= %.1f%%)", makeDescriptionPrefix(), limit * 100.0);
    }

    public String toShorthandDescription() {
        return String.format(Locale.US, "%s%s %.1f%% > %.1f%%",
                resourceType,
                (resourceUsage.getName() != null ? ":" + resourceUsage.getName() : ""),
                resourceUsage.getUsage() * 100.0, limit * 100.0);
    }

    private String makeDescriptionPrefix() {
        return String.format(Locale.US, "%s%s on node %d [%s]",
                resourceType,
                (resourceUsage.getName() != null ? ":" + resourceUsage.getName() : ""),
                node.getIndex(),
                inferHostnameFromRpcAddress(rpcAddress));
    }

    private static String inferHostnameFromRpcAddress(String rpcAddress) {
        if (rpcAddress == null) {
            return "unknown hostname";
        }
        var spec = new Spec(rpcAddress);
        if (spec.malformed()) {
            return "unknown hostname";
        }
        return spec.host();
    }

}
