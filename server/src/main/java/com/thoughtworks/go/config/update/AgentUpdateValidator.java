/*
 * Copyright 2019 ThoughtWorks, Inc.
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
package com.thoughtworks.go.config.update;

import com.thoughtworks.go.config.EnvironmentsConfig;
import com.thoughtworks.go.config.exceptions.ElasticAgentsResourceUpdateException;
import com.thoughtworks.go.config.exceptions.EntityType;
import com.thoughtworks.go.config.exceptions.InvalidPendingAgentOperationException;
import com.thoughtworks.go.config.exceptions.RecordNotFoundException;
import com.thoughtworks.go.domain.AgentInstance;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.service.GoConfigService;
import com.thoughtworks.go.server.service.result.HttpOperationResult;
import com.thoughtworks.go.util.TriState;
import org.apache.commons.collections4.CollectionUtils;

import static com.thoughtworks.go.i18n.LocalizedMessage.forbiddenToEdit;
import static com.thoughtworks.go.serverhealth.HealthStateScope.GLOBAL;
import static com.thoughtworks.go.serverhealth.HealthStateType.forbidden;
import static com.thoughtworks.go.serverhealth.HealthStateType.general;
import static java.lang.String.format;
import static java.util.Collections.singletonList;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

public class AgentUpdateValidator {
    private final Username username;
    private final AgentInstance agentInstance;
    private final String hostname;
    private final EnvironmentsConfig environments;
    private final String resources;
    private final HttpOperationResult result;
    private GoConfigService goConfigService;
    private final TriState state;

    public AgentUpdateValidator(Username username, AgentInstance agentInstance, String hostname, EnvironmentsConfig environments,
                                String resources, TriState state, HttpOperationResult result, GoConfigService goConfigService) {
        this.username = username;
        this.agentInstance = agentInstance;
        this.hostname = hostname;
        this.environments = environments;
        this.resources = resources;
        this.state = state;
        this.result = result;
        this.goConfigService = goConfigService;
    }

    public boolean canContinue() {
        if (!isAuthorized()) {
            return false;
        }

        if (isAnyOperationPerformedOnAgents()) {
            return true;
        }

        String msg = "No Operation performed on agent.";
        result.badRequest(msg, msg, general(GLOBAL));

        return false;
    }

    private boolean isAuthorized() {
        if (goConfigService.isAdministrator(username.getUsername())) {
            return true;
        }
        result.forbidden(forbiddenToEdit(), forbiddenToEdit(), forbidden());
        return false;
    }

    public void validate() throws Exception {
        bombWhenAgentsDoesNotExist();
        bombIfEnvironmentsSpecifiedAsEmpty(environments);
        bombIfResourcesSpecifiedAsBlank(resources);
        bombIfAnyOperationOnPendingAgent();
        bombWhenElasticAgentResourcesAreUpdated();
    }

    private void bombIfEnvironmentsSpecifiedAsEmpty(EnvironmentsConfig environments) {
        if (environments != null && CollectionUtils.isEmpty(environments)) {
            String msg = "Environments are specified but they are blank.";
            result.badRequest(msg, msg, general(GLOBAL));
            throw new IllegalArgumentException(msg);
        }
    }

    private void bombIfResourcesSpecifiedAsBlank(String entityValues) {
        if (entityValues != null && isBlank(entityValues)) {
            String msg = "Resources are specified but they are blank.";
            result.badRequest(msg, msg, general(GLOBAL));
            throw new IllegalArgumentException(msg);
        }
    }

    private void bombWhenAgentsDoesNotExist() {
        if (agentInstance.isNullAgent()) {
            String agentNotFoundMessage = format("Agent '%s' not found.", agentInstance.getUuid());
            result.notFound(agentNotFoundMessage, agentNotFoundMessage, general(GLOBAL));
            throw new RecordNotFoundException(EntityType.Agent, agentInstance.getUuid());
        }
    }

    private boolean isAnyOperationPerformedOnAgents() {
        return isNotBlank(resources)
                || CollectionUtils.isNotEmpty(environments)
                || isNotBlank(hostname)
                || state.isTrue() || state.isFalse();
    }

    private void bombIfAnyOperationOnPendingAgent() throws InvalidPendingAgentOperationException {
        if (!agentInstance.isPending()) {
            return;
        }

        if (state.isTrue() || state.isFalse()) {
            return;
        }

        String msg = format("Pending agent [%s] must be explicitly enabled or disabled when performing any operation on it.", agentInstance.getUuid());
        result.badRequest(msg, msg, general(GLOBAL));
        throw new InvalidPendingAgentOperationException(singletonList(agentInstance.getUuid()));
    }

    private void bombWhenElasticAgentResourcesAreUpdated() throws ElasticAgentsResourceUpdateException {
        if (isBlank(resources)) {
            return;
        }
        if (!agentInstance.isElastic()) {
            return;
        }

        String message = format("Resources on elastic agent with uuid [%s] can not be updated.", agentInstance.getUuid());
        result.badRequest(message, "", general(GLOBAL));
        throw new ElasticAgentsResourceUpdateException(singletonList(agentInstance.getUuid()));
    }
}
