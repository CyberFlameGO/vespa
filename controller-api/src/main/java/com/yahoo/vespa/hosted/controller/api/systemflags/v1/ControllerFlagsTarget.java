// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.systemflags.v1;

import com.yahoo.config.provision.CloudName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.athenz.api.AthenzIdentity;
import com.yahoo.vespa.flags.json.FlagData;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.yahoo.vespa.hosted.controller.api.systemflags.v1.FlagsTarget.controllerFile;
import static com.yahoo.vespa.hosted.controller.api.systemflags.v1.FlagsTarget.defaultFile;
import static com.yahoo.vespa.hosted.controller.api.systemflags.v1.FlagsTarget.systemFile;

/**
 * @author bjorncs
 */
class ControllerFlagsTarget implements FlagsTarget {
    private final SystemName system;
    private final CloudName cloud;
    private final ZoneId zone;

    ControllerFlagsTarget(SystemName system, CloudName cloud, ZoneId zone) {
        this.system = Objects.requireNonNull(system);
        this.cloud = Objects.requireNonNull(cloud);
        this.zone = Objects.requireNonNull(zone);
    }

    @Override public List<String> flagDataFilesPrioritized() { return List.of(controllerFile(system), systemFile(system), defaultFile()); }
    @Override public URI endpoint() { return URI.create("https://localhost:4443/"); } // Note: Cannot use VIPs for controllers due to network configuration on AWS
    @Override public Optional<AthenzIdentity> athenzHttpsIdentity() { return Optional.empty(); }
    @Override public String asString() { return String.format("%s.controller", system.value()); }

    @Override
    public FlagData partiallyResolveFlagData(FlagData data) {
        return FlagsTarget.partialResolve(data, system, cloud, zone);
    }

    @Override
    public String toString() {
        return "ControllerFlagsTarget{" +
               "system=" + system +
               ", cloud=" + cloud +
               ", zone=" + zone +
               '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ControllerFlagsTarget that = (ControllerFlagsTarget) o;
        return system == that.system && cloud.equals(that.cloud) && zone.equals(that.zone);
    }

    @Override
    public int hashCode() {
        return Objects.hash(system, cloud, zone);
    }
}
