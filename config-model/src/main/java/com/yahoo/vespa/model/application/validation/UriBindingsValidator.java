// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.application.validation;

import com.yahoo.config.model.deploy.DeployState;
import com.yahoo.vespa.model.VespaModel;
import com.yahoo.vespa.model.container.ApplicationContainerCluster;
import com.yahoo.vespa.model.container.component.BindingPattern;
import com.yahoo.vespa.model.container.component.Handler;
import com.yahoo.vespa.model.container.component.SystemBindingPattern;
import com.yahoo.vespa.model.container.http.FilterBinding;
import com.yahoo.vespa.model.container.http.Http;

import java.util.logging.Level;

/**
 * Validates URI bindings for filters and handlers
 * Enforced in public systems, log warning in non-public systems
 *
 * @author bjorncs
 */
class UriBindingsValidator extends Validator {

    @Override
    public void validate(VespaModel model, DeployState deployState) {
        for (ApplicationContainerCluster cluster : model.getContainerClusters().values()) {
            for (Handler handler : cluster.getHandlers()) {
                for (BindingPattern binding : handler.getServerBindings()) {
                    validateUserBinding(binding, model, deployState);
                }
            }
            Http http = cluster.getHttp();
            if (http != null) {
                for (FilterBinding binding : cluster.getHttp().getBindings()) {
                    validateUserBinding(binding.binding(), model, deployState);
                }
            }
        }
    }

    private static void validateUserBinding(BindingPattern binding, VespaModel model, DeployState deployState) {
        validateScheme(binding, deployState);
        if (isHostedApplication(model, deployState)) {
            validateHostedApplicationUserBinding(binding, deployState);
        }
    }

    private static void validateScheme(BindingPattern binding, DeployState deployState) {
        if (binding.scheme().equals("https")) {
            String message = createErrorMessage(
                    binding, "'https' bindings are deprecated, use 'http' instead to bind to both http and https traffic.");
            deployState.getDeployLogger().logApplicationPackage(Level.WARNING, message);
        }
    }

    private static void validateHostedApplicationUserBinding(BindingPattern binding, DeployState deployState) {
        // only perform these validation for used-generated bindings
        // bindings produced by the hosted config model amender will violate some of the rules below
        if (binding instanceof SystemBindingPattern) return;

        // Allow binding to port if we are restricting data plane bindings
        if (!binding.matchesAnyPort()) {
            logOrThrow(createErrorMessage(binding, "binding with port is not allowed"), deployState);
        }
        if (!binding.host().equals(BindingPattern.WILDCARD_PATTERN)) {
            logOrThrow(createErrorMessage(binding, "only binding with wildcard ('*') for hostname is allowed"), deployState);
        }
        if (!binding.scheme().equals("http") && !binding.scheme().equals("https")) {
            logOrThrow(createErrorMessage(binding, "only 'http' is allowed as scheme"), deployState);
        }
    }

    /*
     * Logs to deploy logger in non-public systems, throw otherwise
     */
    private static void logOrThrow(String message, DeployState deployState) {
        if (deployState.zone().system().isPublic()) {
            throw new IllegalArgumentException(message);
        } else {
            deployState.getDeployLogger().log(Level.WARNING, message);
        }
    }

    private static boolean isHostedApplication(VespaModel model, DeployState deployState) {
        return deployState.isHostedTenantApplication(model.getAdmin().getApplicationType());
    }

    private static String createErrorMessage(BindingPattern binding, String message) {
        return String.format("For binding '%s': %s", binding.originalPatternString(), message);
    }

}
