/*
 * Copyright 2015-2018 _floragunn_ GmbH
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Portions Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazon.opendistroforelasticsearch.security.filter;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.amazon.opendistroforelasticsearch.security.auth.RolesInjector;
import com.amazon.opendistroforelasticsearch.security.resolver.IndexResolverReplacer;
import com.amazon.opendistroforelasticsearch.security.support.WildcardMatcher;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.amazon.opendistroforelasticsearch.security.auth.BackendRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.DocWriteRequest.OpType;
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotRequest;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.admin.indices.close.CloseIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.bulk.BulkItemRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkShardRequest;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.action.support.ActionFilterChain;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.util.concurrent.ThreadContext.StoredContext;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.index.reindex.UpdateByQueryRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;

import com.amazon.opendistroforelasticsearch.security.action.whoami.WhoAmIAction;
import com.amazon.opendistroforelasticsearch.security.auditlog.AuditLog;
import com.amazon.opendistroforelasticsearch.security.auditlog.AuditLog.Origin;
import com.amazon.opendistroforelasticsearch.security.compliance.ComplianceConfig;
import com.amazon.opendistroforelasticsearch.security.configuration.AdminDNs;
import com.amazon.opendistroforelasticsearch.security.configuration.CompatConfig;
import com.amazon.opendistroforelasticsearch.security.configuration.DlsFlsRequestValve;
import com.amazon.opendistroforelasticsearch.security.privileges.PrivilegesEvaluator;
import com.amazon.opendistroforelasticsearch.security.privileges.PrivilegesEvaluatorResponse;
import com.amazon.opendistroforelasticsearch.security.support.Base64Helper;
import com.amazon.opendistroforelasticsearch.security.support.ConfigConstants;
import com.amazon.opendistroforelasticsearch.security.support.HeaderHelper;
import com.amazon.opendistroforelasticsearch.security.support.SourceFieldsContext;
import com.amazon.opendistroforelasticsearch.security.user.User;

import static com.amazon.opendistroforelasticsearch.security.OpenDistroSecurityPlugin.isActionTraceEnabled;
import static com.amazon.opendistroforelasticsearch.security.OpenDistroSecurityPlugin.traceAction;
import static com.amazon.opendistroforelasticsearch.security.support.ConfigConstants.OPENDISTRO_SECURITY_USER_INFO_THREAD_CONTEXT;

public class OpenDistroSecurityFilter implements ActionFilter {

    protected final Logger log = LogManager.getLogger(this.getClass());
    private final PrivilegesEvaluator evalp;
    private final AdminDNs adminDns;
    private DlsFlsRequestValve dlsFlsValve;
    private final AuditLog auditLog;
    private final ThreadContext threadContext;
    private final ClusterService cs;
    private final CompatConfig compatConfig;
    private final IndexResolverReplacer indexResolverReplacer;
    private final WildcardMatcher immutableIndicesMatcher;
    private final RolesInjector rolesInjector;
    private final Client client;
    private final BackendRegistry backendRegistry;

    public OpenDistroSecurityFilter(final Client client, final Settings settings, final PrivilegesEvaluator evalp, final AdminDNs adminDns,
            DlsFlsRequestValve dlsFlsValve, AuditLog auditLog, ThreadPool threadPool, ClusterService cs,
            final CompatConfig compatConfig, final IndexResolverReplacer indexResolverReplacer, BackendRegistry backendRegistry) {
        this.client = client;
        this.evalp = evalp;
        this.adminDns = adminDns;
        this.dlsFlsValve = dlsFlsValve;
        this.auditLog = auditLog;
        this.threadContext = threadPool.getThreadContext();
        this.cs = cs;
        this.compatConfig = compatConfig;
        this.indexResolverReplacer = indexResolverReplacer;
        this.immutableIndicesMatcher = WildcardMatcher.from(settings.getAsList(ConfigConstants.OPENDISTRO_SECURITY_COMPLIANCE_IMMUTABLE_INDICES, Collections.emptyList()));
        this.rolesInjector = new RolesInjector();
        this.backendRegistry = backendRegistry;
        log.info("{} indices are made immutable.", immutableIndicesMatcher);
    }

    @VisibleForTesting
    WildcardMatcher getImmutableIndicesMatcher() {
        return immutableIndicesMatcher;
    }

    @Override
    public int order() {
        return Integer.MIN_VALUE;
    }

    @Override
    public <Request extends ActionRequest, Response extends ActionResponse> void apply(Task task, final String action, Request request,
            ActionListener<Response> listener, ActionFilterChain<Request, Response> chain) {
        try (StoredContext ctx = threadContext.newStoredContext(true)){
            org.apache.logging.log4j.ThreadContext.clearAll();
            apply0(task, action, request, listener, chain);
        }
    }

    private static Set<String> alias2Name(Set<Alias> aliases) {
        return aliases.stream().map(a -> a.name()).collect(ImmutableSet.toImmutableSet());
    }

    private void setUserInfoThreadContext(User user) {
        if (threadContext.getTransient(OPENDISTRO_SECURITY_USER_INFO_THREAD_CONTEXT) == null) {
            final ImmutableList.Builder<String> builder = ImmutableList.builder();
            builder.add(user.getName(), String.join(",", user.getRoles()),  String.join(",", user.getOpenDistroSecurityRoles()));
            String requestedTenant = user.getRequestedTenant();
            if (!Strings.isNullOrEmpty(requestedTenant)) {
                builder.add(requestedTenant);
            }
            threadContext.putTransient(OPENDISTRO_SECURITY_USER_INFO_THREAD_CONTEXT, String.join("|", builder.build()));
        }
    }

    private <Request extends ActionRequest, Response extends ActionResponse> void apply0(Task task, final String action, Request request,
            ActionListener<Response> listener, ActionFilterChain<Request, Response> chain) {
        try {

            if(threadContext.getTransient(ConfigConstants.OPENDISTRO_SECURITY_ORIGIN) == null) {
                threadContext.putTransient(ConfigConstants.OPENDISTRO_SECURITY_ORIGIN, Origin.LOCAL.toString());
            }

            final ComplianceConfig complianceConfig = auditLog.getComplianceConfig();
            if (complianceConfig != null && complianceConfig.isEnabled()) {
                attachSourceFieldContext(request);
            }
            final Set<String> injectedRoles = rolesInjector.injectUserAndRoles(threadContext);
            boolean enforcePrivilegesEvaluation = false;
            User user = threadContext.getTransient(ConfigConstants.OPENDISTRO_SECURITY_USER);
            if(user == null && (user = backendRegistry.authenticate(request, null, task, action)) != null) {
                threadContext.putTransient(ConfigConstants.OPENDISTRO_SECURITY_USER, user);
                enforcePrivilegesEvaluation = true;
            }

            final boolean userIsAdmin = isUserAdmin(user, adminDns);
            final boolean interClusterRequest = HeaderHelper.isInterClusterRequest(threadContext);
            final boolean trustedClusterRequest = HeaderHelper.isTrustedClusterRequest(threadContext);
            final boolean confRequest = "true".equals(HeaderHelper.getSafeFromHeader(threadContext, ConfigConstants.OPENDISTRO_SECURITY_CONF_REQUEST_HEADER));
            final boolean passThroughRequest = action.startsWith("indices:admin/seq_no")
                    || action.equals(WhoAmIAction.NAME);

            final boolean internalRequest =
                    (interClusterRequest || HeaderHelper.isDirectRequest(threadContext))
                    && action.startsWith("internal:")
                    && !action.startsWith("internal:transport/proxy");

            if (user != null) {
                org.apache.logging.log4j.ThreadContext.put("user", user.getName());
            }
                        
            if (isActionTraceEnabled()) {

                String count = "";
                if(request instanceof BulkRequest) {
                    count = ""+((BulkRequest) request).requests().size();
                }

                if(request instanceof MultiGetRequest) {
                    count = ""+((MultiGetRequest) request).getItems().size();
                }

                if(request instanceof MultiSearchRequest) {
                    count = ""+((MultiSearchRequest) request).requests().size();
                }

                traceAction("Node "+cs.localNode().getName()+" -> "+action+" ("+count+"): userIsAdmin="+userIsAdmin+"/conRequest="+confRequest+"/internalRequest="+internalRequest
                        +"origin="+threadContext.getTransient(ConfigConstants.OPENDISTRO_SECURITY_ORIGIN)+"/directRequest="+HeaderHelper.isDirectRequest(threadContext)+"/remoteAddress="+request.remoteAddress());


                threadContext.putHeader("_opendistro_security_trace"+System.currentTimeMillis()+"#"+UUID.randomUUID().toString(), Thread.currentThread().getName()+" FILTER -> "+"Node "+cs.localNode().getName()+" -> "+action+" userIsAdmin="+userIsAdmin+"/conRequest="+confRequest+"/internalRequest="+internalRequest
                        +"origin="+threadContext.getTransient(ConfigConstants.OPENDISTRO_SECURITY_ORIGIN)+"/directRequest="+HeaderHelper.isDirectRequest(threadContext)+"/remoteAddress="+request.remoteAddress()+" "+threadContext.getHeaders().entrySet().stream().filter(p->!p.getKey().startsWith("_opendistro_security_trace")).collect(Collectors.toMap(p -> p.getKey(), p -> p.getValue())));


            }


            if(userIsAdmin
                    || confRequest
                    || internalRequest
                    || passThroughRequest){

                if(userIsAdmin && !confRequest && !internalRequest && !passThroughRequest) {
                    auditLog.logGrantedPrivileges(action, request, task);
                    auditLog.logIndexEvent(action, request, task);
                }

                chain.proceed(task, action, request, listener);
                return;
            }
            
            
            if(immutableIndicesMatcher != WildcardMatcher.NONE) {
            
                boolean isImmutable = false;
                
                if(request instanceof BulkShardRequest) {
                    for(BulkItemRequest bsr: ((BulkShardRequest) request).items()) {
                        isImmutable = checkImmutableIndices(bsr.request(), listener);
                        if(isImmutable) {
                            break;
                        }
                    }
                } else {
                    isImmutable = checkImmutableIndices(request, listener);
                }
    
                if(isImmutable) {
                    return;
                }

            }

            if(Origin.LOCAL.toString().equals(threadContext.getTransient(ConfigConstants.OPENDISTRO_SECURITY_ORIGIN))
                    && (interClusterRequest || HeaderHelper.isDirectRequest(threadContext))
                    && (injectedRoles == null)
                    && !enforcePrivilegesEvaluation
                    ) {

                chain.proceed(task, action, request, listener);
                return;
            }

            if(user == null) {

                if(action.startsWith("cluster:monitor/state")) {
                    chain.proceed(task, action, request, listener);
                    return;
                }

                if((interClusterRequest || trustedClusterRequest || request.remoteAddress() == null) && !compatConfig.transportInterClusterAuthEnabled()) {
                    chain.proceed(task, action, request, listener);
                    return;
                }

                log.error("No user found for "+ action+" from "+request.remoteAddress()+" "+threadContext.getTransient(ConfigConstants.OPENDISTRO_SECURITY_ORIGIN)+" via "+threadContext.getTransient(ConfigConstants.OPENDISTRO_SECURITY_CHANNEL_TYPE)+" "+threadContext.getHeaders());
                listener.onFailure(new ElasticsearchSecurityException("No user found for "+action, RestStatus.INTERNAL_SERVER_ERROR));
                return;
            }

            final PrivilegesEvaluator eval = evalp;

            if (!eval.isInitialized()) {
                log.error("Open Distro Security not initialized for {}", action);
                listener.onFailure(new ElasticsearchSecurityException("Open Distro Security not initialized for "
                + action, RestStatus.SERVICE_UNAVAILABLE));
                return;
            }

            if (log.isTraceEnabled()) {
                log.trace("Evaluate permissions for user: {}", user.getName());
            }

            final PrivilegesEvaluatorResponse pres = eval.evaluate(user, action, request, task, injectedRoles);
            
            if (log.isDebugEnabled()) {
                log.debug(pres);
            }

            setUserInfoThreadContext(user);

            if (pres.isAllowed()) {
                auditLog.logGrantedPrivileges(action, request, task);
                auditLog.logIndexEvent(action, request, task);
                if(!dlsFlsValve.invoke(request, listener, pres.getAllowedFlsFields(), pres.getMaskedFields(), pres.getQueries())) {
                    return;
                }
                final CreateIndexRequest createIndexRequest = pres.getRequest();
                if (createIndexRequest == null) {
                    chain.proceed(task, action, request, listener);
                } else {
                    client.admin().indices().create(createIndexRequest, new ActionListener<CreateIndexResponse>() {
                        @Override
                        public void onResponse(CreateIndexResponse createIndexResponse) {
                            if (createIndexResponse.isAcknowledged()) {
                                log.debug("Request to create index {} with aliases {} acknowledged, proceeding with {}",
                                        createIndexRequest.index(), alias2Name(createIndexRequest.aliases()), request.getClass().getSimpleName());
                                chain.proceed(task, action, request, listener);
                            } else {
                                Exception e = new ElasticsearchException("Request to create index {} with aliases {} was not acknowledged, failing {}",
                                        createIndexRequest.index(), alias2Name(createIndexRequest.aliases()), request.getClass().getSimpleName());
                                log.error(e.getMessage());
                                listener.onFailure(e);
                            }
                        }

                        @Override
                        public void onFailure(Exception e) {
                            if (e instanceof ResourceAlreadyExistsException) {
                                log.debug("Request to create index {} with aliases {} failed as resource already exist, proceeding with {}",
                                        createIndexRequest.index(), alias2Name(createIndexRequest.aliases()), request.getClass().getSimpleName(), e);
                                chain.proceed(task, action, request, listener);
                            } else {
                                log.error("Request to create index {} with aliases {} failed, failing {}",
                                        createIndexRequest.index(), alias2Name(createIndexRequest.aliases()), request.getClass().getSimpleName(), e);
                                listener.onFailure(e);
                            }
                        }
                    });
                }
                return;
            } else {
                auditLog.logMissingPrivileges(action, request, task);
                String err = (injectedRoles == null) ?
                        String.format("no permissions for %s and %s", pres.getMissingPrivileges(), user) :
                        String.format("no permissions for %s and associated roles %s", pres.getMissingPrivileges(), injectedRoles);
                log.debug(err);
                listener.onFailure(new ElasticsearchSecurityException(err, RestStatus.FORBIDDEN));
                return;
            }
        } catch (Throwable e) {
            log.error("Unexpected exception "+e, e);
            listener.onFailure(new ElasticsearchSecurityException("Unexpected exception " + action, RestStatus.INTERNAL_SERVER_ERROR));
            return;
        }
    }

    private static boolean isUserAdmin(User user, final AdminDNs adminDns) {
        if (user != null && adminDns.isAdmin(user)) {
            return true;
        }

        return false;
    }

    private void attachSourceFieldContext(ActionRequest request) {
        
        if(request instanceof SearchRequest && SourceFieldsContext.isNeeded((SearchRequest) request)) {            
            if(threadContext.getHeader("_opendistro_security_source_field_context") == null) {
                final String serializedSourceFieldContext = Base64Helper.serializeObject(new SourceFieldsContext((SearchRequest) request));
                threadContext.putHeader("_opendistro_security_source_field_context", serializedSourceFieldContext);
            }
        } else if (request instanceof GetRequest && SourceFieldsContext.isNeeded((GetRequest) request)) {
            if(threadContext.getHeader("_opendistro_security_source_field_context") == null) {
                final String serializedSourceFieldContext = Base64Helper.serializeObject(new SourceFieldsContext((GetRequest) request));
                threadContext.putHeader("_opendistro_security_source_field_context", serializedSourceFieldContext);
            }
        }
    }
    
    @SuppressWarnings("rawtypes")
    private boolean checkImmutableIndices(Object request, ActionListener listener) {
        final boolean isModifyIndexRequest = request instanceof DeleteRequest
                || request instanceof UpdateRequest
                || request instanceof UpdateByQueryRequest
                || request instanceof DeleteByQueryRequest
                || request instanceof DeleteIndexRequest
                || request instanceof RestoreSnapshotRequest
                || request instanceof CloseIndexRequest
                || request instanceof IndicesAliasesRequest;

        if (isModifyIndexRequest && isRequestIndexImmutable(request)) {
            listener.onFailure(new ElasticsearchSecurityException("Index is immutable", RestStatus.FORBIDDEN));
            return true;
        }
        
        if ((request instanceof IndexRequest) && isRequestIndexImmutable(request)) {
            ((IndexRequest) request).opType(OpType.CREATE);
        }
        
        return false;
    }

    private boolean isRequestIndexImmutable(Object request) {
        final IndexResolverReplacer.Resolved resolved = indexResolverReplacer.resolveRequest(request);
        final Set<String> allIndices = resolved.getAllIndices();

        return immutableIndicesMatcher.matchAny(allIndices);
    }
}
