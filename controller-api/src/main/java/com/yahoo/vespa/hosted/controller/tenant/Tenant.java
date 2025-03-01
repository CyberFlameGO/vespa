// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.tenant;

import com.yahoo.config.provision.TenantName;
import com.yahoo.vespa.hosted.controller.api.integration.organization.Contact;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A tenant in hosted Vespa.
 *
 * @author mpolden
 */
public abstract class Tenant {

    private final TenantName name;
    private final Instant createdAt;
    private final LastLoginInfo lastLoginInfo;
    private final Optional<Contact> contact;
    private final Instant tenantRolesLastMaintained;
    private final List<CloudAccountInfo> cloudAccounts;

    Tenant(TenantName name, Instant createdAt, LastLoginInfo lastLoginInfo, Optional<Contact> contact, Instant tenantRolesLastMaintained, List<CloudAccountInfo> cloudAccounts) {
        this.name = name;
        this.createdAt = createdAt;
        this.lastLoginInfo = lastLoginInfo;
        this.contact = contact;
        this.tenantRolesLastMaintained = tenantRolesLastMaintained;
        this.cloudAccounts = cloudAccounts;
    }

    /** Name of this tenant */
    public TenantName name() {
        return name;
    }

    /** Instant when the tenant was created */
    public Instant createdAt() {
        return createdAt;
    }

    /** Returns login information for this tenant */
    public LastLoginInfo lastLoginInfo() {
        return lastLoginInfo;
    }

    /** Contact information for this tenant */
    public Optional<Contact> contact() {
        return contact;
    }

    public Instant tenantRolesLastMaintained() {
        return tenantRolesLastMaintained;
    }

    public List<CloudAccountInfo> cloudAccounts() {
        return cloudAccounts;
    }

    public abstract Type type();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Tenant tenant = (Tenant) o;
        return Objects.equals(name, tenant.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    public static TenantName requireName(TenantName name) {
        if ( ! name.value().matches("^(?=.{1,20}$)[a-z](-?[a-z0-9]+)*$")) {
            throw new IllegalArgumentException("New tenant or application names must start with a letter, may " +
                                               "contain no more than 20 characters, and may only contain lowercase " +
                                               "letters, digits or dashes, but no double-dashes.");
        }
        return name;
    }


    public enum Type {

        /** Tenant authenticated through Athenz. */
        athenz,

        /** Tenant authenticated through some cloud identity provider. */
        cloud,

        /** Tenant has been deleted. */
        deleted,

    }

}
