// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.component.Version;
import com.yahoo.config.provision.CloudName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.zone.ZoneApi;
import com.yahoo.vespa.hosted.controller.ControllerTester;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.OsRelease;
import com.yahoo.vespa.hosted.controller.integration.ZoneApiMock;
import com.yahoo.vespa.hosted.controller.maintenance.OsUpgradeScheduler.CalendarVersionedRelease.CalendarVersion;
import com.yahoo.vespa.hosted.controller.versions.OsVersionTarget;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author mpolden
 */
public class OsUpgradeSchedulerTest {

    @Test
    void schedule_calendar_versioned_release() {
        ControllerTester tester = new ControllerTester();
        OsUpgradeScheduler scheduler = new OsUpgradeScheduler(tester.controller(), Duration.ofDays(1));
        Instant t0 = Instant.parse("2022-01-16T09:05:00.00Z"); // Inside trigger period
        tester.clock().setInstant(t0);

        CloudName cloud = CloudName.from("cloud");
        ZoneApi zone = zone("prod.us-west-1", cloud);
        tester.zoneRegistry().setZones(zone).dynamicProvisioningIn(zone);

        // Initial run does nothing as the cloud does not have a target
        scheduler.maintain();
        assertTrue(tester.controller().os().target(cloud).isEmpty(), "No target set");

        // Target is set manually
        Version version0 = Version.fromString("7.0.0.20220101");
        tester.controller().os().upgradeTo(version0, cloud, false, false);

        // Target remains unchanged as it hasn't expired yet
        for (var interval : List.of(Duration.ZERO, Duration.ofDays(30))) {
            tester.clock().advance(interval);
            scheduler.maintain();
            assertEquals(version0, tester.controller().os().target(cloud).get().osVersion().version());
        }

        // New release becomes available, but is not triggered until cool-down period has passed, and we're inside a
        // trigger period
        Version version1 = Version.fromString("7.0.0.20220301");
        tester.clock().advance(Duration.ofDays(13).plusHours(15));
        assertEquals("2022-03-01T00:05:00", formatInstant(tester.clock().instant()));

        // Change does not become available until certification
        Optional<OsUpgradeScheduler.Change> change = scheduler.changeIn(cloud, tester.clock().instant(), true);
        assertTrue(change.isPresent());
        assertFalse(change.get().certified());
        Version systemVersion = tester.controller().readSystemVersion();
        Version olderThanSystemVersion = new Version(systemVersion.getMajor(), systemVersion.getMinor() - 1, systemVersion.getMicro());
        tester.controller().os().certify(version1, cloud, olderThanSystemVersion);

        // Change is now certified
        change = scheduler.changeIn(cloud, tester.clock().instant(), true);
        assertTrue(change.isPresent() && change.get().certified());
        assertEquals(version1, change.get().osVersion().version());
        scheduler.maintain();
        assertEquals(version0,
                     tester.controller().os().target(cloud).get().osVersion().version(),
                     "Target is unchanged because cooldown hasn't passed");
        tester.clock().advance(Duration.ofDays(3).plusHours(18));
        assertEquals("2022-03-04T18:05:00", formatInstant(tester.clock().instant()));
        scheduler.maintain();
        assertEquals(version0,
                     tester.controller().os().target(cloud).get().osVersion().version(),
                     "Target is unchanged because we're outside trigger period");
        tester.clock().advance(Duration.ofDays(2).plusHours(14));
        assertEquals("2022-03-07T08:05:00", formatInstant(tester.clock().instant()));

        // Time constraints have now passed, but the current target has been pinned in the meantime
        tester.controller().os().upgradeTo(version0, cloud, false, true);
        change = scheduler.changeIn(cloud, tester.clock().instant(), true);
        assertTrue(change.isPresent());
        assertTrue(change.get().certified());
        assertEquals(-1, scheduler.maintain());
        assertEquals(version0,
                     tester.controller().os().target(cloud).get().osVersion().version(),
                     "Target is unchanged because it's pinned");

        // Target is unpinned and new version is allowed to be scheduled
        tester.controller().os().upgradeTo(version0, cloud, false, false);
        scheduler.maintain();
        assertEquals(version1,
                     tester.controller().os().target(cloud).get().osVersion().version(),
                     "New target set");

        // A few more days pass and target remains unchanged
        tester.clock().advance(Duration.ofDays(2));
        scheduler.maintain();
        assertEquals(version1, tester.controller().os().target(cloud).get().osVersion().version());

        // Estimate next change
        Version expected = Version.fromString("7.0.0.20220426");
        tester.controller().os().certify(expected, cloud, systemVersion);
        Optional<OsUpgradeScheduler.Change> nextChange = scheduler.changeIn(cloud, tester.clock().instant(), true);
        assertTrue(nextChange.isPresent());
        assertEquals(expected, nextChange.get().osVersion().version());
        assertEquals("2022-04-26T07:00:00", formatInstant(nextChange.get().scheduleAt()));
    }

    @Test
    void schedule_calendar_versioned_release_in_cd() {
        ControllerTester tester = new ControllerTester(SystemName.cd);
        OsUpgradeScheduler scheduler = new OsUpgradeScheduler(tester.controller(), Duration.ofDays(1));
        Instant t0 = Instant.parse("2022-01-16T02:05:00.00Z"); // Inside trigger period
        tester.clock().setInstant(t0);
        CloudName cloud = CloudName.from("cloud");
        ZoneApi zone = zone("prod.us-west-1", cloud);
        tester.zoneRegistry().setZones(zone).dynamicProvisioningIn(zone);

        // Set initial target
        Version version0 = Version.fromString("7.0.0.20220101");
        tester.controller().os().upgradeTo(version0, cloud, false, false);

        // Next version is triggered
        Version version1 = Version.fromString("7.0.0.20220301");
        tester.clock().advance(Duration.ofDays(44));
        assertEquals("2022-03-01T02:05:00", formatInstant(tester.clock().instant()));
        scheduler.maintain();
        assertEquals(version0, tester.controller().os().target(cloud).get().osVersion().version());
        // Cool-down passes
        tester.clock().advance(Duration.ofHours(4));
        assertEquals(version1, scheduler.changeIn(cloud, tester.clock().instant(), false).get().osVersion().version());
        scheduler.maintain();
        assertEquals(version1, tester.controller().os().target(cloud).get().osVersion().version());

        // Estimate next change
        Optional<OsUpgradeScheduler.Change> nextChange = scheduler.changeIn(cloud, tester.clock().instant(), true);
        assertTrue(nextChange.isPresent());
        assertEquals("7.0.0.20220426", nextChange.get().osVersion().version().toFullString());
        assertEquals("2022-04-26T06:00:00", formatInstant(nextChange.get().scheduleAt()));
    }

    @Test
    void schedule_latest_release() {
        ControllerTester tester = new ControllerTester();
        OsUpgradeScheduler scheduler = new OsUpgradeScheduler(tester.controller(), Duration.ofDays(1));
        Instant t0 = Instant.parse("2021-06-22T00:42:12.00Z"); // Outside trigger period
        tester.clock().setInstant(t0);

        // Set initial target
        CloudName cloud = tester.controller().clouds().iterator().next();
        Version version0 = Version.fromString("8.0");
        tester.controller().os().upgradeTo(version0, cloud, false, false);

        // Stable release (tagged outside trigger period) is scheduled once trigger period opens
        Version version1 = Version.fromString("8.1");
        tester.serviceRegistry().artifactRepository().addRelease(new OsRelease(version1, OsRelease.Tag.latest,
                                                                               Instant.parse("2021-06-21T23:59:00.00Z")));
        scheduleUpgradeAfter(Duration.ZERO, version0, scheduler, tester);

        // No change yet because it hasn't been certified
        Optional<OsUpgradeScheduler.Change> nextChange = scheduler.changeIn(cloud, tester.clock().instant(), true);
        assertFalse(nextChange.get().certified(), "No change");

        // Change is certified and upgrade is scheduled
        Version systemVersion = tester.controller().readSystemVersion();
        tester.controller().os().certify(version1, cloud, systemVersion);
        nextChange = scheduler.changeIn(cloud, tester.clock().instant(), true);
        assertTrue(nextChange.isPresent());
        assertEquals(version1, nextChange.get().osVersion().version());
        assertEquals("2021-06-22T07:00:00", formatInstant(nextChange.get().scheduleAt()));
        scheduleUpgradeAfter(Duration.ofHours(7), version1, scheduler, tester); // Inside trigger period

        // A newer version is triggered manually
        Version version3 = Version.fromString("8.3");
        tester.controller().os().upgradeTo(version3, cloud, false, false);

        // Nothing happens in next iteration as tagged release is older than manually triggered version
        scheduleUpgradeAfter(Duration.ofDays(7), version3, scheduler, tester);
        assertTrue(scheduler.changeIn(cloud, tester.clock().instant(), true).isEmpty());
    }

    @Test
    void schedule_latest_release_in_cd() {
        ControllerTester tester = new ControllerTester(SystemName.cd);
        OsUpgradeScheduler scheduler = new OsUpgradeScheduler(tester.controller(), Duration.ofDays(1));
        Instant t0 = Instant.parse("2021-06-21T07:05:00.00Z"); // Inside trigger period
        tester.clock().setInstant(t0);

        // Set initial target
        CloudName cloud = tester.controller().clouds().iterator().next();
        Version version0 = Version.fromString("8.0");
        tester.controller().os().upgradeTo(version0, cloud, false, false);

        // Latest release is not scheduled immediately
        Version version1 = Version.fromString("8.1");
        tester.serviceRegistry().artifactRepository().addRelease(new OsRelease(version1, OsRelease.Tag.latest,
                tester.clock().instant()));
        assertEquals(version1, scheduler.changeIn(cloud, tester.clock().instant(), true).get().osVersion().version());
        assertEquals("2021-06-22T07:05:00", formatInstant(scheduler.changeIn(cloud, tester.clock().instant(), true).get().scheduleAt()),
                     "Not valid until cool-down period passes");
        scheduleUpgradeAfter(Duration.ZERO, version0, scheduler, tester);

        // Cooldown period passes and latest release is scheduled
        scheduleUpgradeAfter(Duration.ofDays(1).plusMinutes(3), version1, scheduler, tester);
    }

    @Test
    void schedule_of_calender_versioned_releases() {
        Map<String, String> tests = Map.of("2022-01-01", "2021-12-28",
                                           "2022-03-01", "2021-12-28",
                                           "2022-03-02", "2022-03-01",
                                           "2022-04-30", "2022-03-01",
                                           "2022-05-01", "2022-04-26",
                                           "2022-06-30", "2022-06-28",
                                           "2022-07-01", "2022-06-28",
                                           "2022-08-28", "2022-06-28",
                                           "2022-08-29", "2022-08-23");
        tests.forEach((now, expectedVersionDate) -> {
            Instant instant = LocalDate.parse(now).atStartOfDay().toInstant(ZoneOffset.UTC);
            CalendarVersion version = OsUpgradeScheduler.CalendarVersionedRelease.findVersion(instant, Version.fromString("1.0"));
            assertEquals(LocalDate.parse(expectedVersionDate), version.date(), "version to schedule at " + now);
        });
    }

    private void scheduleUpgradeAfter(Duration duration, Version version, OsUpgradeScheduler scheduler, ControllerTester tester) {
        tester.clock().advance(duration);
        scheduler.maintain();
        CloudName cloud = tester.controller().clouds().iterator().next();
        OsVersionTarget target = tester.controller().os().target(cloud).get();
        assertEquals(version, target.osVersion().version());
    }

    private static ZoneApi zone(String id, CloudName cloud) {
        return ZoneApiMock.newBuilder().withId(id).with(cloud).build();
    }

    private static String formatInstant(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME);
    }

}
