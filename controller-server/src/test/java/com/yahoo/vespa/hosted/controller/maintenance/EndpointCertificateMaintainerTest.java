// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.CloudAccount;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.jdisc.test.MockMetric;
import com.yahoo.vespa.flags.Flags;
import com.yahoo.vespa.flags.InMemoryFlagSource;
import com.yahoo.vespa.flags.PermanentFlags;
import com.yahoo.vespa.hosted.controller.ControllerTester;
import com.yahoo.vespa.hosted.controller.api.integration.certificates.EndpointCertificate;
import com.yahoo.vespa.hosted.controller.api.integration.certificates.EndpointCertificateProviderMock;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.RevisionId;
import com.yahoo.vespa.hosted.controller.application.Deployment;
import com.yahoo.vespa.hosted.controller.application.DeploymentActivity;
import com.yahoo.vespa.hosted.controller.application.DeploymentMetrics;
import com.yahoo.vespa.hosted.controller.application.QuotaUsage;
import com.yahoo.vespa.hosted.controller.application.TenantAndApplicationId;
import com.yahoo.vespa.hosted.controller.certificate.AssignedCertificate;
import com.yahoo.vespa.hosted.controller.deployment.ApplicationPackageBuilder;
import com.yahoo.vespa.hosted.controller.deployment.DeploymentContext;
import com.yahoo.vespa.hosted.controller.deployment.DeploymentTester;
import com.yahoo.vespa.hosted.controller.integration.SecretStoreMock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Stream;

import static com.yahoo.vespa.hosted.controller.deployment.DeploymentContext.devUsEast1;
import static com.yahoo.vespa.hosted.controller.deployment.DeploymentContext.perfUsEast3;
import static com.yahoo.vespa.hosted.controller.deployment.DeploymentContext.productionUsWest1;
import static com.yahoo.vespa.hosted.controller.deployment.DeploymentContext.stagingTest;
import static com.yahoo.vespa.hosted.controller.deployment.DeploymentContext.systemTest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author andreer
 */
public class EndpointCertificateMaintainerTest {

    private final ControllerTester tester = new ControllerTester();
    private final SecretStoreMock secretStore = (SecretStoreMock) tester.controller().secretStore();
    private final EndpointCertificateMaintainer maintainer = new EndpointCertificateMaintainer(tester.controller(), Duration.ofHours(1));
    private final CertificatePoolMaintainer certificatePoolMaintainer = new CertificatePoolMaintainer(tester.controller(), new MockMetric(), Duration.ofHours(1));
    private final EndpointCertificate exampleCert = new EndpointCertificate("keyName", "certName", 0, 0, "root-request-uuid", Optional.of("leaf-request-uuid"), List.of(), "issuer", Optional.empty(), Optional.empty(), Optional.empty());

    @Test
    void old_and_unused_cert_is_deleted() {
        tester.curator().writeAssignedCertificate(assignedCertificate(ApplicationId.defaultId(), exampleCert));
        assertEquals(0.0, maintainer.maintain(), 0.0000001);
        assertTrue(tester.curator().readAssignedCertificate(ApplicationId.defaultId()).isEmpty());
    }

    @Test
    void unused_but_recently_used_cert_is_not_deleted() {
        EndpointCertificate recentlyRequestedCert = exampleCert.withLastRequested(tester.clock().instant().minusSeconds(3600).getEpochSecond());
        tester.curator().writeAssignedCertificate(assignedCertificate(ApplicationId.defaultId(), recentlyRequestedCert));
        assertEquals(0.0, maintainer.maintain(), 0.0000001);
        assertEquals(Optional.of(recentlyRequestedCert), tester.curator().readAssignedCertificate(ApplicationId.defaultId()).map(AssignedCertificate::certificate));
    }

    @Test
    void refreshed_certificate_is_updated() {
        EndpointCertificate recentlyRequestedCert = exampleCert.withLastRequested(tester.clock().instant().minusSeconds(3600).getEpochSecond());
        tester.curator().writeAssignedCertificate(assignedCertificate(ApplicationId.defaultId(), recentlyRequestedCert));

        secretStore.setSecret(exampleCert.keyName(), "foo", 1);
        secretStore.setSecret(exampleCert.certName(), "bar", 1);

        assertEquals(0.0, maintainer.maintain(), 0.0000001);

        var updatedCert = Optional.of(recentlyRequestedCert.withLastRefreshed(tester.clock().instant().getEpochSecond()).withVersion(1));

        assertEquals(updatedCert, tester.curator().readAssignedCertificate(ApplicationId.defaultId()).map(AssignedCertificate::certificate));
    }

    @Test
    void certificate_in_use_is_not_deleted() {
        var appId = ApplicationId.from("tenant", "application", "default");

        DeploymentTester deploymentTester = new DeploymentTester(tester);

        var applicationPackage = new ApplicationPackageBuilder()
                .region("us-west-1")
                .build();

        DeploymentContext deploymentContext = deploymentTester.newDeploymentContext("tenant", "application", "default");

        deploymentContext.submit(applicationPackage).runJob(systemTest).runJob(stagingTest).runJob(productionUsWest1);

        assertEquals(0.0, maintainer.maintain(), 0.0000001);
        var cert = tester.curator().readAssignedCertificate(appId).orElseThrow().certificate();
        tester.controller().serviceRegistry().endpointCertificateProvider().certificateDetails(cert.rootRequestId()); // cert should not be deleted, the app is deployed!
    }

    @Test
    void refreshed_certificate_is_discovered_and_after_four_days_deployed() {
        var appId = ApplicationId.from("tenant", "application", "default");

        DeploymentTester deploymentTester = new DeploymentTester(tester);

        var applicationPackage = new ApplicationPackageBuilder()
                .region("us-west-1")
                .build();

        DeploymentContext deploymentContext = deploymentTester.newDeploymentContext("tenant", "application", "default");
        deploymentContext.submit(applicationPackage).runJob(systemTest).runJob(stagingTest).runJob(productionUsWest1);
        var assignedCertificate = tester.curator().readAssignedCertificate(appId).orElseThrow();

        // cert should not be deleted, the app is deployed!
        assertEquals(0.0, maintainer.maintain(), 0.0000001);
        assertEquals(tester.curator().readAssignedCertificate(appId), Optional.of(assignedCertificate));
        tester.controller().serviceRegistry().endpointCertificateProvider().certificateDetails(assignedCertificate.certificate().rootRequestId());

        // This simulates a cert refresh performed 3 days later
        tester.clock().advance(Duration.ofDays(3));
        secretStore.setSecret(assignedCertificate.certificate().keyName(), "foo", 1);
        secretStore.setSecret(assignedCertificate.certificate().certName(), "bar", 1);
        tester.controller().serviceRegistry().endpointCertificateProvider().requestCaSignedCertificate(appId.toFullString(), assignedCertificate.certificate().requestedDnsSans(), Optional.of(assignedCertificate.certificate()), "rsa_2048", false);
        // We should now pick up the new key and cert version + uuid, but not force trigger deployment yet
        assertEquals(0.0, maintainer.maintain(), 0.0000001);
        deploymentContext.assertNotRunning(productionUsWest1);
        var updatedCert = tester.curator().readAssignedCertificate(appId).orElseThrow().certificate();
        assertNotEquals(assignedCertificate.certificate().leafRequestId().orElseThrow(), updatedCert.leafRequestId().orElseThrow());
        assertEquals(updatedCert.version(), assignedCertificate.certificate().version() + 1);

        // after another 4 days, we should force trigger deployment if it hasn't already happened
        tester.clock().advance(Duration.ofDays(4).plusSeconds(1));
        deploymentContext.assertNotRunning(productionUsWest1);
        assertEquals(0.0, maintainer.maintain(), 0.0000001);
        deploymentContext.assertRunning(productionUsWest1);
    }

    @Test
    void testEligibleSorting() {
        EndpointCertificateMaintainer.EligibleJob oldestDeployment = makeDeploymentAtAge(5);
        assertEquals(
                oldestDeployment,
                Stream.of(makeDeploymentAtAge(2), oldestDeployment, makeDeploymentAtAge(4)).min(maintainer.oldestFirst).get());
    }

    private EndpointCertificateMaintainer.EligibleJob makeDeploymentAtAge(int ageInDays) {
        var deployment = new Deployment(ZoneId.defaultId(), CloudAccount.empty, RevisionId.forProduction(1), Version.emptyVersion,
                Instant.now().minus(ageInDays, ChronoUnit.DAYS), DeploymentMetrics.none, DeploymentActivity.none, QuotaUsage.none, OptionalDouble.empty());
        return new EndpointCertificateMaintainer.EligibleJob(deployment, ApplicationId.defaultId(), JobType.prod("somewhere"));
    }

    @Test
    void unmaintained_cert_is_deleted() {
        EndpointCertificateProviderMock endpointCertificateProvider = (EndpointCertificateProviderMock) tester.controller().serviceRegistry().endpointCertificateProvider();

        var cert = endpointCertificateProvider.requestCaSignedCertificate("something", List.of("a", "b", "c"), Optional.empty(), "rsa_2048", false);// Unknown to controller!

        assertEquals(0.0, maintainer.maintain(), 0.0000001);

        assertTrue(endpointCertificateProvider.dnsNamesOf(cert.rootRequestId()).isEmpty());
        assertTrue(endpointCertificateProvider.listCertificates().isEmpty());
    }

    @Test
    void cert_pool_is_not_deleted() {
        EndpointCertificateProviderMock endpointCertificateProvider = (EndpointCertificateProviderMock) tester.controller().serviceRegistry().endpointCertificateProvider();

        tester.flagSource().withIntFlag(PermanentFlags.CERT_POOL_SIZE.id(), 3);
        assertEquals(0.0, certificatePoolMaintainer.maintain(), 0.0000001);
        assertEquals(0.0, maintainer.maintain(), 0.0000001);

        assertNotEquals(List.of(), endpointCertificateProvider.listCertificates());
    }

    @Test
    void certificates_are_not_assigned_random_id_when_flag_disabled() {
        var app = ApplicationId.from("tenant", "app", "default");
        DeploymentTester deploymentTester = new DeploymentTester(tester);
        deployToAssignCert(deploymentTester, app, List.of(systemTest, stagingTest, productionUsWest1), Optional.empty());
        assertEquals(1, tester.curator().readAssignedCertificates().size());

        maintainer.maintain();
        assertEquals(1, tester.curator().readAssignedCertificates().size());
    }

    @Test
    void production_deployment_certificates_are_assigned_random_id() {
        var app = ApplicationId.from("tenant", "app", "default");
        DeploymentTester deploymentTester = new DeploymentTester(tester);
        deployToAssignCert(deploymentTester, app, List.of(systemTest, stagingTest, productionUsWest1), Optional.empty());
        assertEquals(1, tester.curator().readAssignedCertificates().size());

        ((InMemoryFlagSource)deploymentTester.controller().flagSource()).withBooleanFlag(Flags.ASSIGN_RANDOMIZED_ID.id(), true);
        maintainer.maintain();
        assertEquals(2, tester.curator().readAssignedCertificates().size());

        // Verify random id is same for application and instance certificates
        Optional<AssignedCertificate> applicationCertificate = tester.curator().readAssignedCertificate(TenantAndApplicationId.from(app), Optional.empty());
        assertTrue(applicationCertificate.isPresent());
        Optional<AssignedCertificate> instanceCertificate = tester.curator().readAssignedCertificate(TenantAndApplicationId.from(app), Optional.of(app.instance()));
        assertTrue(instanceCertificate.isPresent());
        assertEquals(instanceCertificate.get().certificate().randomizedId(), applicationCertificate.get().certificate().randomizedId());

        // Verify the 3 wildcard random names are same in all certs
        List<String> appWildcardSans = applicationCertificate.get().certificate().requestedDnsSans();
        assertEquals(3, appWildcardSans.size());
        List<String> instanceSans = instanceCertificate.get().certificate().requestedDnsSans();
        List<String> wildcards = instanceSans.stream().filter(appWildcardSans::contains).toList();
        assertEquals(appWildcardSans, wildcards);
    }

    @Test
    void existing_application_randomid_is_copied_to_new_instance_deployments() {
        var instance1 = ApplicationId.from("tenant", "prod", "instance1");
        var instance2 = ApplicationId.from("tenant", "prod", "instance2");

        DeploymentTester deploymentTester = new DeploymentTester(tester);
        deployToAssignCert(deploymentTester, instance1, List.of(systemTest, stagingTest,productionUsWest1),Optional.of("instance1"));
        assertEquals(1, tester.curator().readAssignedCertificates().size());
        ((InMemoryFlagSource)deploymentTester.controller().flagSource()).withBooleanFlag(Flags.ASSIGN_RANDOMIZED_ID.id(), true);
        maintainer.maintain();

        String randomId = tester.curator().readAssignedCertificate(instance1).get().certificate().randomizedId().get();

        deployToAssignCert(deploymentTester, instance2, List.of(productionUsWest1), Optional.of("instance1,instance2"));
        maintainer.maintain();
        assertEquals(3, tester.curator().readAssignedCertificates().size());

        assertEquals(randomId, tester.curator().readAssignedCertificate(instance1).get().certificate().randomizedId().get());
    }

    @Test
    void dev_certificates_are_not_assigned_application_level_certificate() {
        var devApp = ApplicationId.from("tenant", "devonly", "foo");
        DeploymentTester deploymentTester = new DeploymentTester(tester);
        deployToAssignCert(deploymentTester, devApp, List.of(devUsEast1), Optional.empty());
        assertEquals(1, tester.curator().readAssignedCertificates().size());
        ((InMemoryFlagSource)deploymentTester.controller().flagSource()).withBooleanFlag(Flags.ASSIGN_RANDOMIZED_ID.id(), true);
        List<String> originalRequestedSans = tester.curator().readAssignedCertificate(devApp).get().certificate().requestedDnsSans();
        maintainer.maintain();
        assertEquals(1, tester.curator().readAssignedCertificates().size());

        // Verify certificate is assigned random id and 3 new names
        Optional<AssignedCertificate> assignedCertificate = tester.curator().readAssignedCertificate(devApp);
        assertTrue(assignedCertificate.get().certificate().randomizedId().isPresent());
        List<String> newRequestedSans = assignedCertificate.get().certificate().requestedDnsSans();
        List<String> randomizedNames = newRequestedSans.stream().filter(san -> !originalRequestedSans.contains(san)).toList();
        assertEquals(3, randomizedNames.size());
    }

    private void deployToAssignCert(DeploymentTester tester, ApplicationId applicationId, List<JobType> jobTypes, Optional<String> instances) {
        var applicationPackageBuilder = new ApplicationPackageBuilder()
                .region("us-west-1");
        instances.map(applicationPackageBuilder::instances);
        var applicationPackage = applicationPackageBuilder.build();

        List<JobType> manualJobs = jobTypes.stream().filter(jt -> jt.environment().isManuallyDeployed()).toList();
        List<JobType> jobs = jobTypes.stream().filter(jt -> ! jt.environment().isManuallyDeployed()).toList();

        DeploymentContext deploymentContext = tester.newDeploymentContext(applicationId);
        deploymentContext.submit(applicationPackage);
        manualJobs.forEach(job -> deploymentContext.runJob(job, applicationPackage));
        jobs.forEach(deploymentContext::runJob);

    }
    EndpointCertificate certificate(List<String> sans) {
        return new EndpointCertificate("keyName", "certName", 0, 0, "root-request-uuid", Optional.of("leaf-request-uuid"), List.of(), "issuer", Optional.empty(), Optional.empty(), Optional.empty());
    }



    private static AssignedCertificate assignedCertificate(ApplicationId instance, EndpointCertificate certificate) {
        return new AssignedCertificate(TenantAndApplicationId.from(instance), Optional.of(instance.instance()), certificate);
    }

}
