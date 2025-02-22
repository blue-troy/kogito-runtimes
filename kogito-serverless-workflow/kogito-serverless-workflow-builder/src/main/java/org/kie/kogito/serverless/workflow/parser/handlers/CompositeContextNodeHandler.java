/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kie.kogito.serverless.workflow.parser.handlers;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Supplier;

import org.drools.mvel.java.JavaDialect;
import org.jbpm.compiler.canonical.descriptors.TaskDescriptor;
import org.jbpm.process.core.datatype.DataTypeResolver;
import org.jbpm.ruleflow.core.RuleFlowNodeContainerFactory;
import org.jbpm.ruleflow.core.factory.AbstractCompositeNodeFactory;
import org.jbpm.ruleflow.core.factory.CompositeContextNodeFactory;
import org.jbpm.ruleflow.core.factory.NodeFactory;
import org.jbpm.ruleflow.core.factory.WorkItemNodeFactory;
import org.kie.kogito.internal.utils.ConversionUtils;
import org.kie.kogito.jackson.utils.JsonNodeVisitor;
import org.kie.kogito.jackson.utils.JsonObjectUtils;
import org.kie.kogito.process.expr.ExpressionHandlerFactory;
import org.kie.kogito.serverless.workflow.SWFConstants;
import org.kie.kogito.serverless.workflow.parser.ParserContext;
import org.kie.kogito.serverless.workflow.parser.ServerlessWorkflowParser;
import org.kie.kogito.serverless.workflow.parser.SourceFileServerlessWorkflowBindEvent;
import org.kie.kogito.serverless.workflow.parser.handlers.openapi.OpenAPIDescriptor;
import org.kie.kogito.serverless.workflow.parser.handlers.openapi.OpenAPIDescriptorFactory;
import org.kie.kogito.serverless.workflow.suppliers.ApiKeyAuthDecoratorSupplier;
import org.kie.kogito.serverless.workflow.suppliers.BasicAuthDecoratorSupplier;
import org.kie.kogito.serverless.workflow.suppliers.BearerTokenAuthDecoratorSupplier;
import org.kie.kogito.serverless.workflow.suppliers.ClientOAuth2AuthDecoratorSupplier;
import org.kie.kogito.serverless.workflow.suppliers.CollectionParamsDecoratorSupplier;
import org.kie.kogito.serverless.workflow.suppliers.ConfigSuppliedWorkItemSupplier;
import org.kie.kogito.serverless.workflow.suppliers.ExpressionActionSupplier;
import org.kie.kogito.serverless.workflow.suppliers.ObjectResolverSupplier;
import org.kie.kogito.serverless.workflow.suppliers.ParamsRestBodyBuilderSupplier;
import org.kie.kogito.serverless.workflow.suppliers.PasswordOAuth2AuthDecoratorSupplier;
import org.kie.kogito.serverless.workflow.suppliers.SysoutActionSupplier;
import org.kie.kogito.serverless.workflow.utils.ExpressionHandlerUtils;
import org.kogito.workitem.rest.RestWorkItemHandler;
import org.kogito.workitem.rest.auth.ApiKeyAuthDecorator;
import org.kogito.workitem.rest.auth.ApiKeyAuthDecorator.Location;
import org.kogito.workitem.rest.auth.BearerTokenAuthDecorator;
import org.kogito.workitem.rest.auth.ClientOAuth2AuthDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.UnknownType;

import io.serverlessworkflow.api.Workflow;
import io.serverlessworkflow.api.actions.Action;
import io.serverlessworkflow.api.events.EventRef;
import io.serverlessworkflow.api.filters.ActionDataFilter;
import io.serverlessworkflow.api.functions.FunctionDefinition;
import io.serverlessworkflow.api.functions.FunctionRef;
import io.serverlessworkflow.api.functions.SubFlowRef;
import io.serverlessworkflow.api.interfaces.State;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityScheme.In;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import static org.kie.kogito.internal.utils.ConversionUtils.concatPaths;
import static org.kie.kogito.serverless.workflow.io.URIContentLoaderFactory.buildLoader;
import static org.kie.kogito.serverless.workflow.io.URIContentLoaderFactory.readAllBytes;
import static org.kie.kogito.serverless.workflow.utils.ServerlessWorkflowUtils.OPENAPI_OPERATION_SEPARATOR;
import static org.kie.kogito.serverless.workflow.utils.ServerlessWorkflowUtils.getServiceName;
import static org.kie.kogito.serverless.workflow.utils.ServerlessWorkflowUtils.resolveFunctionMetadata;
import static org.kie.kogito.serverless.workflow.utils.ServerlessWorkflowUtils.runtimeOpenApi;
import static org.kie.kogito.serverless.workflow.utils.ServerlessWorkflowUtils.runtimeRestApi;

public abstract class CompositeContextNodeHandler<S extends State> extends StateHandler<S> {

    private static Logger logger = LoggerFactory.getLogger(CompositeContextNodeHandler.class);

    private static final String SCRIPT_TYPE_PARAM = "script";
    private static final String SYSOUT_TYPE_PARAM = "message";
    private static final String SERVICE_TASK_TYPE = "Service Task";
    private static final String WORKITEM_INTERFACE = "Interface";
    private static final String WORKITEM_OPERATION = "Operation";
    private static final String WORKITEM_INTERFACE_IMPL = "interfaceImplementationRef";
    private static final String WORKITEM_OPERATION_IMPL = "operationImplementationRef";
    private static final String WORKITEM_PARAM_TYPE = "ParameterType";
    private static final String WORKITEM_PARAM = "Parameter";
    private static final String SERVICE_INTERFACE_KEY = "interface";
    private static final String SERVICE_OPERATION_KEY = "operation";
    private static final String SERVICE_IMPL_KEY = "implementation";
    private static final String LANG_SEPARATOR = ":";
    private static final String METHOD_SEPARATOR = ":";
    private static final String INTFC_SEPARATOR = "::";
    private static final String USER_PROP = "username";
    private static final String PASSWORD_PROP = "password";
    private static final String API_KEY_PREFIX = "api_key_prefix";
    private static final String API_KEY = "api_key";
    private static final String ACCESS_TOKEN = "access_token";

    protected CompositeContextNodeHandler(S state, Workflow workflow, ParserContext parserContext) {
        super(state, workflow, parserContext);
    }

    protected final CompositeContextNodeFactory<?> makeCompositeNode(RuleFlowNodeContainerFactory<?, ?> factory) {
        return factory.compositeContextNode(parserContext.newId()).name(state.getName()).autoComplete(true);
    }

    protected final <T extends AbstractCompositeNodeFactory<?, ?>> T handleActions(T embeddedSubProcess, List<Action> actions) {
        return handleActions(embeddedSubProcess, actions, null);
    }

    protected final <T extends AbstractCompositeNodeFactory<?, ?>> T handleActions(T embeddedSubProcess, List<Action> actions, String outputVar, String... extraVariables) {
        if (actions != null && !actions.isEmpty()) {
            NodeFactory<?, ?> startNode = embeddedSubProcess.startNode(parserContext.newId()).name("EmbeddedStart");
            NodeFactory<?, ?> currentNode = startNode;
            for (Action action : actions) {
                currentNode = connect(currentNode, getActionNode(embeddedSubProcess, action, outputVar, extraVariables));
            }
            connect(currentNode, embeddedSubProcess.endNode(parserContext.newId()).name("EmbeddedEnd").terminate(true)).done();
        } else {
            connect(embeddedSubProcess.startNode(parserContext.newId()).name("EmbeddedStart"), embeddedSubProcess.endNode(parserContext.newId()).name("EmbeddedEnd").terminate(true)).done();
        }
        return embeddedSubProcess;
    }

    protected final MakeNodeResult getActionNode(RuleFlowNodeContainerFactory<?, ?> embeddedSubProcess,
            Action action) {
        return getActionNode(embeddedSubProcess, action, null);
    }

    public MakeNodeResult getActionNode(RuleFlowNodeContainerFactory<?, ?> embeddedSubProcess,
            Action action, String collectVar, String... extraVariables) {
        ActionDataFilter actionFilter = action.getActionDataFilter();
        String fromExpr = null;
        String resultExpr = null;
        String toExpr = null;
        boolean useData = true;
        if (actionFilter != null) {
            fromExpr = actionFilter.getFromStateData();
            resultExpr = actionFilter.getResults();
            toExpr = actionFilter.getToStateData();
            useData = actionFilter.isUseResults();
        }
        if (action.getFunctionRef() != null) {
            return filterAndMergeNode(embeddedSubProcess, fromExpr, resultExpr, toExpr, useData,
                    (factory, inputVar, outputVar) -> getActionNode(factory, action.getFunctionRef(), inputVar, outputVar, collectVar, extraVariables));
        } else if (action.getEventRef() != null) {
            return filterAndMergeNode(embeddedSubProcess, fromExpr, resultExpr, toExpr, useData,
                    (factory, inputVar, outputVar) -> getActionNode(factory, action.getEventRef(), inputVar));
        } else if (action.getSubFlowRef() != null) {
            return filterAndMergeNode(embeddedSubProcess, fromExpr, resultExpr, toExpr, useData,
                    (factory, inputVar, outputVar) -> getActionNode(factory, action.getSubFlowRef(), inputVar, outputVar));
        } else {
            throw new IllegalArgumentException("Action node " + action.getName() + " of state " + state.getName() + " does not have function or event defined");
        }
    }

    private NodeFactory<?, ?> getActionNode(RuleFlowNodeContainerFactory<?, ?> factory,
            SubFlowRef subFlowRef,
            String inputVar,
            String outputVar) {
        return ServerlessWorkflowParser.subprocessNode(
                factory.subProcessNode(parserContext.newId()).name(subFlowRef.getWorkflowId()).processId(subFlowRef.getWorkflowId()).waitForCompletion(true),
                inputVar,
                outputVar);
    }

    private NodeFactory<?, ?> getActionNode(RuleFlowNodeContainerFactory<?, ?> embeddedSubProcess,
            EventRef eventRef, String inputVar) {
        return sendEventNode(embeddedSubProcess.actionNode(parserContext.newId()), eventDefinition(eventRef.getTriggerEventRef()), eventRef.getData(), inputVar);
    }

    private NodeFactory<?, ?> getActionNode(RuleFlowNodeContainerFactory<?, ?> embeddedSubProcess,
            FunctionRef functionRef, String inputVar, String outputVar, String collectVar, String... extraVariables) {
        String actionName = functionRef.getRefName();
        FunctionDefinition actionFunction = workflow.getFunctions().getFunctionDefs()
                .stream()
                .filter(wf -> wf.getName().equals(actionName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("cannot find function " + actionName));

        ActionType actionType = ActionType.from(actionFunction);
        String operation = actionType.getOperation(actionFunction);
        switch (actionType) {
            case SCRIPT:
                return embeddedSubProcess
                        .actionNode(parserContext.newId())
                        .name(actionName)
                        .action(JavaDialect.ID,
                                functionRef
                                        .getArguments().get(SCRIPT_TYPE_PARAM).asText());
            case EXPRESSION:
                return embeddedSubProcess
                        .actionNode(parserContext.newId())
                        .name(actionName)
                        .action(ExpressionActionSupplier.of(workflow, operation).withVarNames(inputVar, outputVar).withCollectVar(collectVar)
                                .withAddInputVars(extraVariables).build());
            case SYSOUT:
                return embeddedSubProcess
                        .actionNode(parserContext.newId())
                        .name(actionName)
                        .action(new SysoutActionSupplier(workflow.getExpressionLang(), functionRef.getArguments().get(SYSOUT_TYPE_PARAM).asText(), inputVar, extraVariables));
            case SERVICE:
                return addServiceParameters(embeddedSubProcess
                        .workItemNode(parserContext.newId())
                        .name(actionName)
                        .metaData(TaskDescriptor.KEY_WORKITEM_TYPE, SERVICE_TASK_TYPE)
                        .workName(SERVICE_TASK_TYPE)
                        .inMapping(inputVar, WORKITEM_PARAM)
                        .outMapping(WORKITEM_PARAM, outputVar), actionFunction, operation, functionRef.getArguments());
            case REST:
                return addFunctionArgs(addRestParameters(buildRestWorkItem(embeddedSubProcess, actionFunction, inputVar, outputVar), actionFunction, operation), functionRef);
            case OPENAPI:
                return addFunctionArgs(addOpenApiParameters(buildRestWorkItem(embeddedSubProcess, actionFunction, inputVar, outputVar), actionFunction, operation), functionRef);
            default:
                return emptyNode(embeddedSubProcess, actionName);
        }
    }

    private WorkItemNodeFactory<?> buildRestWorkItem(RuleFlowNodeContainerFactory<?, ?> embeddedSubProcess,
            FunctionDefinition actionFunction,
            String inputVar,
            String outputVar) {
        return embeddedSubProcess
                .workItemNode(parserContext.newId())
                .name(actionFunction.getName())
                .metaData(TaskDescriptor.KEY_WORKITEM_TYPE, RestWorkItemHandler.REST_TASK_TYPE)
                .workName(RestWorkItemHandler.REST_TASK_TYPE)
                .inMapping(inputVar, SWFConstants.MODEL_WORKFLOW_VAR)
                .outMapping(RestWorkItemHandler.RESULT, outputVar)
                .workParameter(RestWorkItemHandler.BODY_BUILDER, new ParamsRestBodyBuilderSupplier());
    }

    private <T extends RuleFlowNodeContainerFactory<T, ?>> WorkItemNodeFactory<T> addFunctionArgs(WorkItemNodeFactory<T> node, FunctionRef functionRef) {
        JsonNode functionArgs = functionRef.getArguments();
        if (functionArgs != null) {
            processArgs(node, functionArgs, SWFConstants.MODEL_WORKFLOW_VAR);
        }
        return node;
    }

    private <T extends RuleFlowNodeContainerFactory<T, ?>> WorkItemNodeFactory<T> addServiceParameters(WorkItemNodeFactory<T> node,
            FunctionDefinition actionFunction,
            String operation, JsonNode functionArgs) {
        String intfc = null;
        String method = null;
        String lang = null;
        // try extracting from operation (format language:interface::method)
        if (operation != null) {
            int indexOf = operation.indexOf(INTFC_SEPARATOR);
            if (indexOf != -1) {
                method = operation.substring(indexOf + INTFC_SEPARATOR.length());
                operation = operation.substring(0, indexOf);
                indexOf = operation.indexOf(LANG_SEPARATOR);
                if (indexOf != -1) {
                    intfc = operation.substring(indexOf + LANG_SEPARATOR.length());
                    lang = operation.substring(0, indexOf);
                } else {
                    intfc = operation;
                }
            }
        }
        if (lang == null) {
            lang = resolveFunctionMetadata(
                    actionFunction, SERVICE_IMPL_KEY, parserContext.getContext(), String.class, "Java");
        }
        // fallback to metadata for backward compatibility
        if (intfc == null) {
            intfc = resolveFunctionMetadata(
                    actionFunction, SERVICE_INTERFACE_KEY, parserContext.getContext());
        }
        if (method == null) {
            method = resolveFunctionMetadata(
                    actionFunction, SERVICE_OPERATION_KEY, parserContext.getContext());
        }

        if (functionArgs == null || functionArgs.isEmpty()) {
            node.workParameter(WORKITEM_PARAM_TYPE, ServerlessWorkflowParser.JSON_NODE);
        } else {
            processArgs(node, functionArgs, WORKITEM_PARAM);
        }

        return node.workParameter(WORKITEM_INTERFACE, intfc)
                .workParameter(WORKITEM_OPERATION, method)
                .workParameter(WORKITEM_INTERFACE_IMPL, intfc)
                .workParameter(WORKITEM_OPERATION_IMPL, method)
                .workParameter(SERVICE_IMPL_KEY, lang);
    }

    private <T extends RuleFlowNodeContainerFactory<T, ?>> WorkItemNodeFactory<T> addRestParameters(WorkItemNodeFactory<T> node,
            FunctionDefinition actionFunction,
            String operation) {
        String url = null;
        String method = null;
        // try extracting from operation (format method:url)
        if (operation != null) {
            int indexOf = operation.indexOf(METHOD_SEPARATOR);
            if (indexOf != -1) {
                method = operation.substring(0, indexOf);
                url = operation.substring(indexOf + METHOD_SEPARATOR.length());
            } else {
                url = operation;
            }
        }
        if (method == null) {
            method = resolveFunctionMetadata(actionFunction, "method", parserContext.getContext());
        }

        return node.workParameter(RestWorkItemHandler.URL, url)
                .workParameter(RestWorkItemHandler.METHOD, method)
                .workParameter(RestWorkItemHandler.USER, runtimeRestApi(actionFunction, USER_PROP, parserContext.getContext()))
                .workParameter(RestWorkItemHandler.PASSWORD, runtimeRestApi(actionFunction, PASSWORD_PROP, parserContext.getContext()))
                .workParameter(RestWorkItemHandler.HOST, runtimeRestApi(actionFunction, "host", parserContext.getContext()))
                .workParameter(RestWorkItemHandler.PORT, runtimeRestApi(actionFunction, "port", parserContext.getContext(), Integer.class, 8080))
                .workParameter(RestWorkItemHandler.BODY_BUILDER, new ParamsRestBodyBuilderSupplier())
                .workParameter(BearerTokenAuthDecorator.BEARER_TOKEN, runtimeRestApi(actionFunction, ACCESS_TOKEN, parserContext.getContext()))
                .workParameter(ApiKeyAuthDecorator.KEY_PREFIX, runtimeRestApi(actionFunction, API_KEY_PREFIX, parserContext.getContext()))
                .workParameter(ApiKeyAuthDecorator.KEY, runtimeRestApi(actionFunction, API_KEY, parserContext.getContext()));
    }

    private <T extends RuleFlowNodeContainerFactory<T, ?>> WorkItemNodeFactory<T> addOpenApiParameters(WorkItemNodeFactory<T> node,
            FunctionDefinition function,
            String operation) {
        int indexOf = function.getOperation().indexOf(OPENAPI_OPERATION_SEPARATOR);
        String uri = operation.substring(0, indexOf);
        String serviceName = getServiceName(uri);
        String operationId = operation.substring(indexOf + OPENAPI_OPERATION_SEPARATOR.length());
        try {
            // although OpenAPIParser has built in support to load uri, it messes up when using contextclassloader, so using our retrieval apis to get the content
            SwaggerParseResult result =
                    new OpenAPIParser().readContents(new String(readAllBytes(buildLoader(URI.create(uri), parserContext.getContext().getClassLoader(), workflow, function.getAuthRef()))), null, null);
            OpenAPI openAPI = result.getOpenAPI();
            if (openAPI == null) {
                throw new IllegalArgumentException("Problem parsing uri " + uri);
            }
            logger.debug("OpenAPI parser messages {}", result.getMessages());
            OpenAPIDescriptor openAPIDescriptor = OpenAPIDescriptorFactory.of(openAPI, operationId);
            addSecurity(node, openAPIDescriptor, serviceName);

            WorkItemNodeFactory<T> workItemNodeFactory = node.workParameter(RestWorkItemHandler.URL,
                    runtimeOpenApi(serviceName, "base_path", String.class, OpenAPIDescriptorFactory.getDefaultURL(openAPI, "http://localhost:8080"),
                            (key, clazz, defaultValue) -> new ConfigSuppliedWorkItemSupplier<String>(key, clazz, defaultValue, calculatedKey -> concatPaths(calculatedKey, openAPIDescriptor.getPath()),
                                    new LambdaExpr(new Parameter(new UnknownType(), "calculatedKey"),
                                            new MethodCallExpr(ConversionUtils.class.getCanonicalName() + ".concatPaths")
                                                    .addArgument(new NameExpr("calculatedKey")).addArgument(new StringLiteralExpr(openAPIDescriptor.getPath()))))))
                    .workParameter(RestWorkItemHandler.METHOD, openAPIDescriptor.getMethod())
                    .workParameter(RestWorkItemHandler.PARAMS_DECORATOR, new CollectionParamsDecoratorSupplier(openAPIDescriptor.getHeaderParams(), openAPIDescriptor.getQueryParams()));

            notifySourceFileCodegenBindListeners(uri);

            return workItemNodeFactory;
        } catch (IOException e) {
            throw new IllegalArgumentException("Problem retrieving uri " + uri);
        }
    }

    private void notifySourceFileCodegenBindListeners(String uri) {
        parserContext.getContext()
                .getSourceFileCodegenBindNotifier()
                .ifPresent(notifier -> notifier.notify(new SourceFileServerlessWorkflowBindEvent(workflow.getId(), uri)));
    }

    private ApiKeyAuthDecorator.Location from(In in) {
        switch (in) {
            case COOKIE:
                return Location.COOKIE;
            case HEADER:
                return Location.HEADER;
            case QUERY:
            default:
                return Location.QUERY;
        }
    }

    private void addSecurity(WorkItemNodeFactory<?> node, OpenAPIDescriptor openAPI, String serviceName) {
        Collection<Supplier<Expression>> authDecorators = new ArrayList<>();
        for (SecurityScheme scheme : openAPI.getSchemes()) {
            switch (scheme.getType()) {
                case APIKEY:
                    authDecorators.add(new ApiKeyAuthDecoratorSupplier(scheme.getName(), from(scheme.getIn())));
                    node.workParameter(ApiKeyAuthDecorator.KEY_PREFIX, runtimeOpenApi(serviceName, API_KEY_PREFIX, parserContext.getContext()))
                            .workParameter(ApiKeyAuthDecorator.KEY, runtimeOpenApi(serviceName, API_KEY, parserContext.getContext()));
                    break;
                case HTTP:
                    if (scheme.getScheme().equals("bearer")) {
                        authDecorators.add(new BearerTokenAuthDecoratorSupplier());
                        node.workParameter(RestWorkItemHandler.AUTH_METHOD, new BearerTokenAuthDecorator()).workParameter(BearerTokenAuthDecorator.BEARER_TOKEN,
                                runtimeOpenApi(serviceName, ACCESS_TOKEN, parserContext.getContext()));
                    } else if (scheme.getScheme().equals("basic")) {
                        authDecorators.add(new BasicAuthDecoratorSupplier());
                        node.workParameter(RestWorkItemHandler.USER, runtimeOpenApi(serviceName, USER_PROP, parserContext.getContext()))
                                .workParameter(RestWorkItemHandler.PASSWORD, runtimeOpenApi(serviceName, PASSWORD_PROP, parserContext.getContext()));
                    }
                    break;
                case OAUTH2:
                    // only support client and password credentials
                    if (scheme.getFlows().getClientCredentials() != null) {
                        authDecorators.add(new ClientOAuth2AuthDecoratorSupplier(scheme.getFlows().getClientCredentials().getTokenUrl(), scheme.getFlows().getClientCredentials().getRefreshUrl()));
                        node.workParameter(ClientOAuth2AuthDecorator.CLIENT_ID, runtimeOpenApi(serviceName, "client_id", parserContext.getContext()))
                                .workParameter(ClientOAuth2AuthDecorator.CLIENT_SECRET, runtimeOpenApi(serviceName, "client_secret", parserContext.getContext()));
                    } else if (scheme.getFlows().getPassword() != null) {
                        authDecorators.add(new PasswordOAuth2AuthDecoratorSupplier(scheme.getFlows().getPassword().getTokenUrl(), scheme.getFlows().getPassword().getRefreshUrl()));
                        node.workParameter(RestWorkItemHandler.USER, runtimeOpenApi(serviceName, USER_PROP, parserContext.getContext()))
                                .workParameter(RestWorkItemHandler.PASSWORD, runtimeOpenApi(serviceName, PASSWORD_PROP, parserContext.getContext()));
                    } else if (scheme.getFlows().getAuthorizationCode() != null) {
                        logger.warn("Unsupported scheme type {} for authorization code flow {}", scheme.getType(), scheme.getFlows().getAuthorizationCode());
                    } else if (scheme.getFlows().getImplicit() != null) {
                        logger.warn("Unsupported scheme type {} for implicit flow {}", scheme.getType(), scheme.getFlows().getImplicit());
                    }
                    break;
                default:
                    logger.warn("Unsupported scheme type {}", scheme.getType());
            }
        }
        if (!authDecorators.isEmpty()) {
            node.workParameter(RestWorkItemHandler.AUTH_METHOD, authDecorators);
        }
    }

    private Map<String, Object> functionsToMap(JsonNode jsonNode) {
        Map<String, Object> map = new HashMap<>();
        if (jsonNode != null) {
            Iterator<Entry<String, JsonNode>> iter = jsonNode.fields();
            while (iter.hasNext()) {
                Entry<String, JsonNode> entry = iter.next();
                map.put(entry.getKey(), functionReference(JsonObjectUtils.simpleToJavaValue(entry.getValue())));
            }
        }
        return map;
    }

    private Object functionReference(Object object) {
        if (object instanceof JsonNode) {
            return JsonNodeVisitor.transformTextNode((JsonNode) object, node -> JsonObjectUtils.fromValue(ExpressionHandlerUtils.replaceExpr(workflow, node.asText())));
        } else if (object instanceof CharSequence) {
            return ExpressionHandlerUtils.replaceExpr(workflow, object.toString());
        } else {
            return object;
        }
    }

    private void processArgs(WorkItemNodeFactory<?> workItemFactory,
            JsonNode functionArgs, String paramName) {
        if (functionArgs.isObject()) {
            functionsToMap(functionArgs).entrySet().forEach(entry -> processArg(entry.getKey(), entry.getValue(), workItemFactory, paramName));
        } else {
            processArg(RestWorkItemHandler.CONTENT_DATA, functionReference(JsonObjectUtils.simpleToJavaValue(functionArgs)), workItemFactory, paramName);
        }
    }

    private void processArg(String key, Object value, WorkItemNodeFactory<?> workItemFactory, String paramName) {
        boolean isExpr = value instanceof CharSequence && ExpressionHandlerFactory.get(workflow.getExpressionLang(), value.toString()).isValid(Optional.empty()) || value instanceof JsonNode;
        workItemFactory
                .workParameter(key,
                        isExpr ? new ObjectResolverSupplier(workflow.getExpressionLang(), value, paramName) : value)
                .workParameterDefinition(key,
                        DataTypeResolver.fromObject(value, isExpr));
    }

    private NodeFactory<?, ?> emptyNode(RuleFlowNodeContainerFactory<?, ?> embeddedSubProcess, String actionName) {
        return embeddedSubProcess
                .actionNode(parserContext.newId())
                .name(actionName)
                .action(JavaDialect.ID, "");
    }
}
