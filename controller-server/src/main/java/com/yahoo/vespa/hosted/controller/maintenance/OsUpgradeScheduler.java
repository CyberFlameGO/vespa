// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.component.Version;
import com.yahoo.config.provision.CloudName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ArtifactRepository;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.OsRelease;
import com.yahoo.vespa.hosted.controller.versions.OsVersion;
import com.yahoo.vespa.hosted.controller.versions.OsVersionTarget;
import com.yahoo.yolean.Exceptions;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Automatically schedule upgrades to the next OS version.
 *
 * @author mpolden
 */
public class OsUpgradeScheduler extends ControllerMaintainer {

    private static final Logger LOG = Logger.getLogger(OsUpgradeScheduler.class.getName());

    public OsUpgradeScheduler(Controller controller, Duration interval) {
        super(controller, interval);
    }

    @Override
    protected double maintain() {
        Instant now = controller().clock().instant();
        int attempts = 0;
        int failures = 0;
        for (var cloud : controller().clouds()) {
            Optional<Change> change = changeIn(cloud, now, false);
            if (change.isEmpty()) continue;
            try {
                attempts++;
                controller().os().upgradeTo(change.get().osVersion().version(), cloud, false, false);
            } catch (IllegalArgumentException e) {
                failures++;
                LOG.log(Level.WARNING, "Failed to schedule OS upgrade: " + Exceptions.toMessageString(e) +
                                       ". Retrying in " + interval());
            }
        }
        return asSuccessFactorDeviation(attempts, failures);
    }

    /**
     * Returns the next OS version change
     *
     * @param cloud  The cloud where the change will be deployed
     * @param now    Current time
     * @param future Whether to return a change that cannot be scheduled now
     */
    public Optional<Change> changeIn(CloudName cloud, Instant now, boolean future) {
        Optional<OsVersionTarget> currentTarget = controller().os().target(cloud);
        if (currentTarget.isEmpty()) return Optional.empty();
        if (upgradingToNewMajor(cloud)) return Optional.empty(); // Skip further upgrades until major version upgrade is complete

        Version currentVersion = currentTarget.get().version();
        Change change = releaseIn(cloud).change(currentVersion, now);
        if (!change.osVersion().version().isAfter(currentVersion)) return Optional.empty();
        if (!future && !change.scheduleAt(now)) return Optional.empty();

        boolean certified = certified(change);
        if (!future && !certified) return Optional.empty();
        return Optional.of(change.withCertified(certified));
    }

    private boolean certified(Change change) {
        boolean certified = controller().os().certified(change.osVersion());
        if (!certified) {
            LOG.log(Level.WARNING, "Want to schedule " + change + ", but this change is not certified for " +
                                   "the current system version");
        }
        return certified;
    }

    private boolean upgradingToNewMajor(CloudName cloud) {
        return controller().os().status().versionsIn(cloud).stream()
                           .filter(version -> !version.isEmpty()) // Ignore empty/unknown versions
                           .map(Version::getMajor)
                           .distinct()
                           .count() > 1;
    }

    private Release releaseIn(CloudName cloud) {
        boolean useTaggedRelease = controller().zoneRegistry().zones().all().dynamicallyProvisioned().in(cloud)
                                               .zones().isEmpty();
        if (useTaggedRelease) {
            return new TaggedRelease(controller().system(), cloud, controller().serviceRegistry().artifactRepository());
        }
        return new CalendarVersionedRelease(controller().system(), cloud);
    }

    private static boolean canTriggerAt(Instant instant, boolean isCd) {
        ZonedDateTime dateTime = instant.atZone(ZoneOffset.UTC);
        int hourOfDay = dateTime.getHour();
        int dayOfWeek = dateTime.getDayOfWeek().getValue();
        // Upgrade can only be scheduled between 07:00 (02:00 in CD systems) and 12:59 UTC, Monday-Thursday
        int startHour = isCd ? 2 : 7;
        return hourOfDay >= startHour && hourOfDay <= 12 && dayOfWeek < 5;
    }

    /** Returns the earliest time, at or after instant, an upgrade can be scheduled */
    private static Instant schedulingInstant(Instant instant, SystemName system) {
        ChronoUnit schedulingResolution = ChronoUnit.HOURS;
        while (!canTriggerAt(instant, system.isCd())) {
            instant = instant.truncatedTo(schedulingResolution)
                             .plus(schedulingResolution.getDuration());
        }
        return instant;
    }

    /** Returns the remaining cool-down period relative to releaseAge */
    private static Duration remainingCooldownOf(Duration cooldown, Duration releaseAge) {
        return releaseAge.compareTo(cooldown) < 0 ? cooldown.minus(releaseAge) : Duration.ZERO;
    }

    private interface Release {

        /** The next available change of this release at given instant */
        Change change(Version currentVersion, Instant instant);

    }

    /** OS version change and the earliest time it can be scheduled */
    public record Change(OsVersion osVersion, Instant scheduleAt, boolean certified) {

        public Change {
            Objects.requireNonNull(osVersion);
            Objects.requireNonNull(scheduleAt);
        }

        public Change withCertified(boolean certified) {
            return new Change(osVersion, scheduleAt, certified);
        }

        /** Returns whether this can be scheduled at given instant */
        public boolean scheduleAt(Instant instant) {
            return !instant.isBefore(scheduleAt);
        }

    }

    /** OS release based on a tag */
    private record TaggedRelease(SystemName system, CloudName cloud, ArtifactRepository artifactRepository) implements Release {

        public TaggedRelease {
            Objects.requireNonNull(system);
            Objects.requireNonNull(cloud);
            Objects.requireNonNull(artifactRepository);
        }

        @Override
        public Change change(Version currentVersion, Instant instant) {
            OsRelease release = artifactRepository.osRelease(currentVersion.getMajor(), OsRelease.Tag.latest);
            Duration cooldown = remainingCooldownOf(cooldown(), release.age(instant));
            Instant scheduleAt = schedulingInstant(instant.plus(cooldown), system);
            return new Change(new OsVersion(release.version(), cloud), scheduleAt, false);
        }

        /** The cool-down period that must pass before a release can be used */
        private Duration cooldown() {
            return system.isCd() ? Duration.ofDays(1) : Duration.ZERO;
        }

    }

    /** OS release based on calendar-versioning */
    record CalendarVersionedRelease(SystemName system, CloudName cloud) implements Release {

        /** A fixed point in time which the release schedule is calculated from */
        private static final Instant START_OF_SCHEDULE = LocalDate.of(2022, 1, 1)
                                                                  .atStartOfDay()
                                                                  .toInstant(ZoneOffset.UTC);

        /** The approximate time that should elapse between versions */
        private static final Duration SCHEDULING_STEP = Duration.ofDays(60);

        /** The day of week new releases are published */
        private static final DayOfWeek RELEASE_DAY = DayOfWeek.TUESDAY;

        /** How far into release day we should wait before triggering. This is to give the new release some time to propagate */
        private static final Duration COOLDOWN = Duration.ofHours(6);

        public CalendarVersionedRelease {
            Objects.requireNonNull(system);
        }

        @Override
        public Change change(Version currentVersion, Instant instant) {
            CalendarVersion version = findVersion(instant, currentVersion);
            Instant predicted = instant;
            while (!version.version().isAfter(currentVersion)) {
                predicted = predicted.plus(Duration.ofDays(1));
                version = findVersion(predicted, currentVersion);
            }
            Duration cooldown = remainingCooldownOf(COOLDOWN, version.age(instant));
            Instant schedulingInstant = schedulingInstant(instant.plus(cooldown), system);
            return new Change(new OsVersion(version.version(), cloud), schedulingInstant, false);
        }

        /** Find the most recent version available according to the scheduling step, relative to now */
        static CalendarVersion findVersion(Instant now, Version currentVersion) {
            Instant candidate = START_OF_SCHEDULE;
            while (!candidate.plus(SCHEDULING_STEP).isAfter(now)) {
                candidate = candidate.plus(SCHEDULING_STEP);
            }
            LocalDate date = LocalDate.ofInstant(candidate, ZoneOffset.UTC);
            while (date.getDayOfWeek() != RELEASE_DAY) {
                date = date.minusDays(1);
            }
            return CalendarVersion.from(date, currentVersion);
        }

        record CalendarVersion(Version version, LocalDate date) {

            private static final DateTimeFormatter CALENDAR_VERSION_PATTERN = DateTimeFormatter.ofPattern("yyyyMMdd");

            private static CalendarVersion from(LocalDate date, Version currentVersion) {
                String qualifier = date.format(CALENDAR_VERSION_PATTERN);
                return new CalendarVersion(new Version(currentVersion.getMajor(),
                                                       currentVersion.getMinor(),
                                                       currentVersion.getMicro(),
                                                       qualifier),
                                           date);
            }

            /** Returns the age of this at given instant */
            private Duration age(Instant instant) {
                return Duration.between(date.atStartOfDay().toInstant(ZoneOffset.UTC), instant);
            }

        }

    }

}
