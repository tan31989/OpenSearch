/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.extensions;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.opensearch.Version;
import org.opensearch.action.admin.cluster.state.ClusterStateResponse;
import org.opensearch.client.node.NodeClient;
import org.opensearch.cluster.ClusterSettingsResponse;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.io.FileSystemUtils;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.settings.SettingsModule;
import org.opensearch.common.transport.TransportAddress;

import org.opensearch.discovery.InitializeExtensionRequest;
import org.opensearch.discovery.InitializeExtensionResponse;
import org.opensearch.extensions.ExtensionsSettings.Extension;
import org.opensearch.extensions.action.ExtensionActionRequest;
import org.opensearch.extensions.action.ExtensionActionResponse;
import org.opensearch.extensions.action.ExtensionTransportActionsHandler;
import org.opensearch.extensions.action.TransportActionRequestFromExtension;
import org.opensearch.extensions.rest.RegisterRestActionsRequest;
import org.opensearch.extensions.rest.RestActionsRequestHandler;
import org.opensearch.extensions.settings.CustomSettingsRequestHandler;
import org.opensearch.extensions.settings.RegisterCustomSettingsRequest;
import org.opensearch.index.IndexModule;
import org.opensearch.index.IndexService;
import org.opensearch.index.IndicesModuleRequest;
import org.opensearch.index.IndicesModuleResponse;
import org.opensearch.index.shard.IndexEventListener;
import org.opensearch.indices.cluster.IndicesClusterStateService;
import org.opensearch.plugins.PluginInfo;
import org.opensearch.rest.RestController;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportException;
import org.opensearch.transport.TransportResponse;
import org.opensearch.transport.TransportResponseHandler;
import org.opensearch.transport.TransportService;
import org.opensearch.env.EnvironmentSettingsResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * The main class for managing Extension communication with the OpenSearch Node.
 *
 * @opensearch.internal
 */
public class ExtensionsManager {
    public static final String REQUEST_EXTENSION_ACTION_NAME = "internal:discovery/extensions";
    public static final String INDICES_EXTENSION_POINT_ACTION_NAME = "indices:internal/extensions";
    public static final String INDICES_EXTENSION_NAME_ACTION_NAME = "indices:internal/name";
    public static final String REQUEST_EXTENSION_CLUSTER_STATE = "internal:discovery/clusterstate";
    public static final String REQUEST_EXTENSION_CLUSTER_SETTINGS = "internal:discovery/clustersettings";
    public static final String REQUEST_EXTENSION_ENVIRONMENT_SETTINGS = "internal:discovery/enviornmentsettings";
    public static final String REQUEST_EXTENSION_ADD_SETTINGS_UPDATE_CONSUMER = "internal:discovery/addsettingsupdateconsumer";
    public static final String REQUEST_EXTENSION_UPDATE_SETTINGS = "internal:discovery/updatesettings";
    public static final String REQUEST_EXTENSION_REGISTER_CUSTOM_SETTINGS = "internal:discovery/registercustomsettings";
    public static final String REQUEST_EXTENSION_REGISTER_REST_ACTIONS = "internal:discovery/registerrestactions";
    public static final String REQUEST_EXTENSION_REGISTER_TRANSPORT_ACTIONS = "internal:discovery/registertransportactions";
    public static final String REQUEST_OPENSEARCH_PARSE_NAMED_WRITEABLE = "internal:discovery/parsenamedwriteable";
    public static final String REQUEST_REST_EXECUTE_ON_EXTENSION_ACTION = "internal:extensions/restexecuteonextensiontaction";
    public static final String REQUEST_EXTENSION_HANDLE_TRANSPORT_ACTION = "internal:extensions/handle-transportaction";
    public static final String TRANSPORT_ACTION_REQUEST_FROM_EXTENSION = "internal:extensions/request-transportaction-from-extension";
    public static final int EXTENSION_REQUEST_WAIT_TIMEOUT = 10;

    private static final Logger logger = LogManager.getLogger(ExtensionsManager.class);

    /**
     * Enum for Extension Requests
     *
     * @opensearch.internal
     */
    public static enum RequestType {
        REQUEST_EXTENSION_CLUSTER_STATE,
        REQUEST_EXTENSION_CLUSTER_SETTINGS,
        REQUEST_EXTENSION_REGISTER_REST_ACTIONS,
        REQUEST_EXTENSION_REGISTER_SETTINGS,
        REQUEST_EXTENSION_ENVIRONMENT_SETTINGS,
        CREATE_COMPONENT,
        ON_INDEX_MODULE,
        GET_SETTINGS
    };

    /**
     * Enum for OpenSearch Requests
     *
     * @opensearch.internal
     */
    public static enum OpenSearchRequestType {
        REQUEST_OPENSEARCH_NAMED_WRITEABLE_REGISTRY
    }

    private final Path extensionsPath;
    private ExtensionTransportActionsHandler extensionTransportActionsHandler;
    // A list of initialized extensions, a subset of the values of map below which includes all extensions
    private List<DiscoveryExtensionNode> extensions;
    private Map<String, DiscoveryExtensionNode> extensionIdMap;
    private RestActionsRequestHandler restActionsRequestHandler;
    private CustomSettingsRequestHandler customSettingsRequestHandler;
    private TransportService transportService;
    private ClusterService clusterService;
    private Settings environmentSettings;
    private AddSettingsUpdateConsumerRequestHandler addSettingsUpdateConsumerRequestHandler;
    private NodeClient client;

    public ExtensionsManager() {
        this.extensionsPath = Path.of("");
    }

    /**
     * Instantiate a new ExtensionsManager object to handle requests and responses from extensions. This is called during Node bootstrap.
     *
     * @param settings  Settings from the node the orchestrator is running on.
     * @param extensionsPath  Path to a directory containing extensions.
     * @throws IOException  If the extensions discovery file is not properly retrieved.
     */
    public ExtensionsManager(Settings settings, Path extensionsPath) throws IOException {
        logger.info("ExtensionsManager initialized");
        this.extensionsPath = extensionsPath;
        this.extensions = new ArrayList<DiscoveryExtensionNode>();
        this.extensionIdMap = new HashMap<String, DiscoveryExtensionNode>();
        // will be initialized in initializeServicesAndRestHandler which is called after the Node is initialized
        this.transportService = null;
        this.clusterService = null;
        this.client = null;
        this.extensionTransportActionsHandler = null;

        /*
         * Now Discover extensions
         */
        discover();

    }

    /**
     * Initializes the {@link RestActionsRequestHandler}, {@link TransportService}, {@link ClusterService} and environment settings. This is called during Node bootstrap.
     * Lists/maps of extensions have already been initialized but not yet populated.
     *
     * @param restController  The RestController on which to register Rest Actions.
     * @param settingsModule The module that binds the provided settings to interface.
     * @param transportService  The Node's transport service.
     * @param clusterService  The Node's cluster service.
     * @param initialEnvironmentSettings The finalized view of settings for the Environment
     * @param client The client used to make transport requests
     */
    public void initializeServicesAndRestHandler(
        RestController restController,
        SettingsModule settingsModule,
        TransportService transportService,
        ClusterService clusterService,
        Settings initialEnvironmentSettings,
        NodeClient client
    ) {
        this.restActionsRequestHandler = new RestActionsRequestHandler(restController, extensionIdMap, transportService);
        this.customSettingsRequestHandler = new CustomSettingsRequestHandler(settingsModule);
        this.transportService = transportService;
        this.clusterService = clusterService;
        this.environmentSettings = initialEnvironmentSettings;
        this.addSettingsUpdateConsumerRequestHandler = new AddSettingsUpdateConsumerRequestHandler(
            clusterService,
            transportService,
            REQUEST_EXTENSION_UPDATE_SETTINGS
        );
        this.client = client;
        this.extensionTransportActionsHandler = new ExtensionTransportActionsHandler(extensionIdMap, transportService, client);
        registerRequestHandler();
    }

    /**
     * Handles Transport Request from {@link org.opensearch.extensions.action.ExtensionTransportAction} which was invoked by an extension via {@link ExtensionTransportActionsHandler}.
     *
     * @param request which was sent by an extension.
     */
    public ExtensionActionResponse handleTransportRequest(ExtensionActionRequest request) throws InterruptedException {
        return extensionTransportActionsHandler.sendTransportRequestToExtension(request);
    }

    private void registerRequestHandler() {
        transportService.registerRequestHandler(
            REQUEST_EXTENSION_REGISTER_REST_ACTIONS,
            ThreadPool.Names.GENERIC,
            false,
            false,
            RegisterRestActionsRequest::new,
            ((request, channel, task) -> channel.sendResponse(restActionsRequestHandler.handleRegisterRestActionsRequest(request)))
        );
        transportService.registerRequestHandler(
            REQUEST_EXTENSION_REGISTER_CUSTOM_SETTINGS,
            ThreadPool.Names.GENERIC,
            false,
            false,
            RegisterCustomSettingsRequest::new,
            ((request, channel, task) -> channel.sendResponse(customSettingsRequestHandler.handleRegisterCustomSettingsRequest(request)))
        );
        transportService.registerRequestHandler(
            REQUEST_EXTENSION_CLUSTER_STATE,
            ThreadPool.Names.GENERIC,
            false,
            false,
            ExtensionRequest::new,
            ((request, channel, task) -> channel.sendResponse(handleExtensionRequest(request)))
        );
        transportService.registerRequestHandler(
            REQUEST_EXTENSION_CLUSTER_SETTINGS,
            ThreadPool.Names.GENERIC,
            false,
            false,
            ExtensionRequest::new,
            ((request, channel, task) -> channel.sendResponse(handleExtensionRequest(request)))
        );
        transportService.registerRequestHandler(
            REQUEST_EXTENSION_ENVIRONMENT_SETTINGS,
            ThreadPool.Names.GENERIC,
            false,
            false,
            ExtensionRequest::new,
            ((request, channel, task) -> channel.sendResponse(handleExtensionRequest(request)))
        );
        transportService.registerRequestHandler(
            REQUEST_EXTENSION_ADD_SETTINGS_UPDATE_CONSUMER,
            ThreadPool.Names.GENERIC,
            false,
            false,
            AddSettingsUpdateConsumerRequest::new,
            ((request, channel, task) -> channel.sendResponse(
                addSettingsUpdateConsumerRequestHandler.handleAddSettingsUpdateConsumerRequest(request)
            ))
        );
        transportService.registerRequestHandler(
            REQUEST_EXTENSION_REGISTER_TRANSPORT_ACTIONS,
            ThreadPool.Names.GENERIC,
            false,
            false,
            RegisterTransportActionsRequest::new,
            ((request, channel, task) -> channel.sendResponse(
                extensionTransportActionsHandler.handleRegisterTransportActionsRequest(request)
            ))
        );
        transportService.registerRequestHandler(
            TRANSPORT_ACTION_REQUEST_FROM_EXTENSION,
            ThreadPool.Names.GENERIC,
            false,
            false,
            TransportActionRequestFromExtension::new,
            ((request, channel, task) -> channel.sendResponse(
                extensionTransportActionsHandler.handleTransportActionRequestFromExtension(request)
            ))
        );
    }

    /*
     * Load and populate all extensions
     */
    private void discover() throws IOException {
        logger.info("Extensions Config Directory :" + extensionsPath.toString());
        if (!FileSystemUtils.isAccessibleDirectory(extensionsPath, logger)) {
            return;
        }

        List<Extension> extensions = new ArrayList<Extension>();
        if (Files.exists(extensionsPath.resolve("extensions.yml"))) {
            try {
                extensions = readFromExtensionsYml(extensionsPath.resolve("extensions.yml")).getExtensions();
            } catch (IOException e) {
                throw new IOException("Could not read from extensions.yml", e);
            }
            for (Extension extension : extensions) {
                loadExtension(extension);
            }
            if (!extensionIdMap.isEmpty()) {
                logger.info("Loaded all extensions");
            }
        } else {
            logger.info("Extensions.yml file is not present.  No extensions will be loaded.");
        }
    }

    /**
     * Loads a single extension
     * @param extension The extension to be loaded
     */
    private void loadExtension(Extension extension) throws IOException {
        if (extensionIdMap.containsKey(extension.getUniqueId())) {
            logger.info("Duplicate uniqueId " + extension.getUniqueId() + ". Did not load extension: " + extension);
        } else {
            try {
                DiscoveryExtensionNode discoveryExtensionNode = new DiscoveryExtensionNode(
                    extension.getName(),
                    extension.getUniqueId(),
                    // placeholder for ephemeral id, will change with POC discovery
                    extension.getUniqueId(),
                    extension.getHostName(),
                    extension.getHostAddress(),
                    new TransportAddress(InetAddress.getByName(extension.getHostAddress()), Integer.parseInt(extension.getPort())),
                    new HashMap<String, String>(),
                    Version.fromString(extension.getOpensearchVersion()),
                    new PluginInfo(
                        extension.getName(),
                        extension.getDescription(),
                        extension.getVersion(),
                        Version.fromString(extension.getOpensearchVersion()),
                        extension.getJavaVersion(),
                        extension.getClassName(),
                        new ArrayList<String>(),
                        Boolean.parseBoolean(extension.hasNativeController())
                    ),
                    extension.getDependencies()
                );
                extensionIdMap.put(extension.getUniqueId(), discoveryExtensionNode);
                logger.info("Loaded extension with uniqueId " + extension.getUniqueId() + ": " + extension);
            } catch (IllegalArgumentException e) {
                throw e;
            }
        }
    }

    /**
     * Iterate through all extensions and initialize them.  Initialized extensions will be added to the {@link #extensions}.
     */
    public void initialize() {
        for (DiscoveryExtensionNode extension : extensionIdMap.values()) {
            initializeExtension(extension);
        }
    }

    private void initializeExtension(DiscoveryExtensionNode extension) {

        final CompletableFuture<InitializeExtensionResponse> inProgressFuture = new CompletableFuture<>();
        final TransportResponseHandler<InitializeExtensionResponse> initializeExtensionResponseHandler = new TransportResponseHandler<
            InitializeExtensionResponse>() {

            @Override
            public InitializeExtensionResponse read(StreamInput in) throws IOException {
                return new InitializeExtensionResponse(in);
            }

            @Override
            public void handleResponse(InitializeExtensionResponse response) {
                for (DiscoveryExtensionNode extension : extensionIdMap.values()) {
                    if (extension.getName().equals(response.getName())) {
                        extensions.add(extension);
                        logger.info("Initialized extension: " + extension.getName());
                        break;
                    }
                }
                inProgressFuture.complete(response);
            }

            @Override
            public void handleException(TransportException exp) {
                logger.error(new ParameterizedMessage("Extension initialization failed"), exp);
                inProgressFuture.completeExceptionally(exp);
            }

            @Override
            public String executor() {
                return ThreadPool.Names.GENERIC;
            }
        };
        try {
            logger.info("Sending extension request type: " + REQUEST_EXTENSION_ACTION_NAME);
            transportService.connectToExtensionNode(extension);
            transportService.sendRequest(
                extension,
                REQUEST_EXTENSION_ACTION_NAME,
                new InitializeExtensionRequest(transportService.getLocalNode(), extension),
                initializeExtensionResponseHandler
            );
            // TODO: make asynchronous
            inProgressFuture.get(EXTENSION_REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS);
        } catch (Exception e) {
            try {
                throw e;
            } catch (Exception e1) {
                logger.error(e.toString());
            }
        }
    }

    /**
     * Handles an {@link ExtensionRequest}.
     *
     * @param extensionRequest  The request to handle, of a type defined in the {@link RequestType} enum.
     * @return  an Response matching the request.
     * @throws Exception if the request is not handled properly.
     */
    TransportResponse handleExtensionRequest(ExtensionRequest extensionRequest) throws Exception {
        switch (extensionRequest.getRequestType()) {
            case REQUEST_EXTENSION_CLUSTER_STATE:
                return new ClusterStateResponse(clusterService.getClusterName(), clusterService.state(), false);
            case REQUEST_EXTENSION_CLUSTER_SETTINGS:
                return new ClusterSettingsResponse(clusterService);
            case REQUEST_EXTENSION_ENVIRONMENT_SETTINGS:
                return new EnvironmentSettingsResponse(this.environmentSettings);
            default:
                throw new IllegalArgumentException("Handler not present for the provided request");
        }
    }

    public void onIndexModule(IndexModule indexModule) throws UnknownHostException {
        for (DiscoveryNode extensionNode : extensionIdMap.values()) {
            onIndexModule(indexModule, extensionNode);
        }
    }

    private void onIndexModule(IndexModule indexModule, DiscoveryNode extensionNode) throws UnknownHostException {
        logger.info("onIndexModule index:" + indexModule.getIndex());
        final CompletableFuture<IndicesModuleResponse> inProgressFuture = new CompletableFuture<>();
        final CompletableFuture<AcknowledgedResponse> inProgressIndexNameFuture = new CompletableFuture<>();
        final TransportResponseHandler<AcknowledgedResponse> acknowledgedResponseHandler = new TransportResponseHandler<
            AcknowledgedResponse>() {
            @Override
            public void handleResponse(AcknowledgedResponse response) {
                logger.info("ACK Response" + response);
                inProgressIndexNameFuture.complete(response);
            }

            @Override
            public void handleException(TransportException exp) {

            }

            @Override
            public String executor() {
                return ThreadPool.Names.GENERIC;
            }

            @Override
            public AcknowledgedResponse read(StreamInput in) throws IOException {
                return new AcknowledgedResponse(in);
            }

        };

        final TransportResponseHandler<IndicesModuleResponse> indicesModuleResponseHandler = new TransportResponseHandler<
            IndicesModuleResponse>() {

            @Override
            public IndicesModuleResponse read(StreamInput in) throws IOException {
                return new IndicesModuleResponse(in);
            }

            @Override
            public void handleResponse(IndicesModuleResponse response) {
                logger.info("received {}", response);
                if (response.getIndexEventListener() == true) {
                    indexModule.addIndexEventListener(new IndexEventListener() {
                        @Override
                        public void beforeIndexRemoved(
                            IndexService indexService,
                            IndicesClusterStateService.AllocatedIndices.IndexRemovalReason reason
                        ) {
                            logger.info("Index Event Listener is called");
                            String indexName = indexService.index().getName();
                            logger.info("Index Name" + indexName.toString());
                            try {
                                logger.info("Sending extension request type: " + INDICES_EXTENSION_NAME_ACTION_NAME);
                                transportService.sendRequest(
                                    extensionNode,
                                    INDICES_EXTENSION_NAME_ACTION_NAME,
                                    new IndicesModuleRequest(indexModule),
                                    acknowledgedResponseHandler
                                );
                                // TODO: make asynchronous
                                inProgressIndexNameFuture.get(EXTENSION_REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS);
                                logger.info("Received ack response from Extension");
                            } catch (Exception e) {
                                try {
                                    throw e;
                                } catch (Exception e1) {
                                    logger.error(e.toString());
                                }
                            }
                        }
                    });
                }
                inProgressFuture.complete(response);
            }

            @Override
            public void handleException(TransportException exp) {
                logger.error(new ParameterizedMessage("IndicesModuleRequest failed"), exp);
                inProgressFuture.completeExceptionally(exp);
            }

            @Override
            public String executor() {
                return ThreadPool.Names.GENERIC;
            }
        };

        try {
            logger.info("Sending extension request type: " + INDICES_EXTENSION_POINT_ACTION_NAME);
            transportService.sendRequest(
                extensionNode,
                INDICES_EXTENSION_POINT_ACTION_NAME,
                new IndicesModuleRequest(indexModule),
                indicesModuleResponseHandler
            );
            // TODO: make asynchronous
            inProgressFuture.get(EXTENSION_REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS);
            logger.info("Received response from Extension");
        } catch (Exception e) {
            try {
                throw e;
            } catch (Exception e1) {
                logger.error(e.toString());
            }
        }
    }

    private ExtensionsSettings readFromExtensionsYml(Path filePath) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        InputStream input = Files.newInputStream(filePath);
        ExtensionsSettings extensionSettings = objectMapper.readValue(input, ExtensionsSettings.class);
        return extensionSettings;
    }

    public static String getRequestExtensionActionName() {
        return REQUEST_EXTENSION_ACTION_NAME;
    }

    public static String getIndicesExtensionPointActionName() {
        return INDICES_EXTENSION_POINT_ACTION_NAME;
    }

    public static String getIndicesExtensionNameActionName() {
        return INDICES_EXTENSION_NAME_ACTION_NAME;
    }

    public static String getRequestExtensionClusterState() {
        return REQUEST_EXTENSION_CLUSTER_STATE;
    }

    public static String getRequestExtensionClusterSettings() {
        return REQUEST_EXTENSION_CLUSTER_SETTINGS;
    }

    public static Logger getLogger() {
        return logger;
    }

    public Path getExtensionsPath() {
        return extensionsPath;
    }

    public List<DiscoveryExtensionNode> getExtensions() {
        return extensions;
    }

    public TransportService getTransportService() {
        return transportService;
    }

    public ClusterService getClusterService() {
        return clusterService;
    }

    public static String getRequestExtensionRegisterRestActions() {
        return REQUEST_EXTENSION_REGISTER_REST_ACTIONS;
    }

    public static String getRequestOpensearchParseNamedWriteable() {
        return REQUEST_OPENSEARCH_PARSE_NAMED_WRITEABLE;
    }

    public static String getRequestRestExecuteOnExtensionAction() {
        return REQUEST_REST_EXECUTE_ON_EXTENSION_ACTION;
    }

    public Map<String, DiscoveryExtensionNode> getExtensionIdMap() {
        return extensionIdMap;
    }

    public RestActionsRequestHandler getRestActionsRequestHandler() {
        return restActionsRequestHandler;
    }

    public void setExtensions(List<DiscoveryExtensionNode> extensions) {
        this.extensions = extensions;
    }

    public void setExtensionIdMap(Map<String, DiscoveryExtensionNode> extensionIdMap) {
        this.extensionIdMap = extensionIdMap;
    }

    public void setRestActionsRequestHandler(RestActionsRequestHandler restActionsRequestHandler) {
        this.restActionsRequestHandler = restActionsRequestHandler;
    }

    public void setTransportService(TransportService transportService) {
        this.transportService = transportService;
    }

    public void setClusterService(ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    public static String getRequestExtensionRegisterTransportActions() {
        return REQUEST_EXTENSION_REGISTER_TRANSPORT_ACTIONS;
    }

    public static String getRequestExtensionRegisterCustomSettings() {
        return REQUEST_EXTENSION_REGISTER_CUSTOM_SETTINGS;
    }

    public CustomSettingsRequestHandler getCustomSettingsRequestHandler() {
        return customSettingsRequestHandler;
    }

    public void setCustomSettingsRequestHandler(CustomSettingsRequestHandler customSettingsRequestHandler) {
        this.customSettingsRequestHandler = customSettingsRequestHandler;
    }

    public static String getRequestExtensionEnvironmentSettings() {
        return REQUEST_EXTENSION_ENVIRONMENT_SETTINGS;
    }

    public static String getRequestExtensionAddSettingsUpdateConsumer() {
        return REQUEST_EXTENSION_ADD_SETTINGS_UPDATE_CONSUMER;
    }

    public static String getRequestExtensionUpdateSettings() {
        return REQUEST_EXTENSION_UPDATE_SETTINGS;
    }

    public AddSettingsUpdateConsumerRequestHandler getAddSettingsUpdateConsumerRequestHandler() {
        return addSettingsUpdateConsumerRequestHandler;
    }

    public void setAddSettingsUpdateConsumerRequestHandler(
        AddSettingsUpdateConsumerRequestHandler addSettingsUpdateConsumerRequestHandler
    ) {
        this.addSettingsUpdateConsumerRequestHandler = addSettingsUpdateConsumerRequestHandler;
    }

    public Settings getEnvironmentSettings() {
        return environmentSettings;
    }

    public void setEnvironmentSettings(Settings environmentSettings) {
        this.environmentSettings = environmentSettings;
    }

    public static String getRequestExtensionHandleTransportAction() {
        return REQUEST_EXTENSION_HANDLE_TRANSPORT_ACTION;
    }

    public static String getTransportActionRequestFromExtension() {
        return TRANSPORT_ACTION_REQUEST_FROM_EXTENSION;
    }

    public static int getExtensionRequestWaitTimeout() {
        return EXTENSION_REQUEST_WAIT_TIMEOUT;
    }

    public ExtensionTransportActionsHandler getExtensionTransportActionsHandler() {
        return extensionTransportActionsHandler;
    }

    public void setExtensionTransportActionsHandler(ExtensionTransportActionsHandler extensionTransportActionsHandler) {
        this.extensionTransportActionsHandler = extensionTransportActionsHandler;
    }

    public NodeClient getClient() {
        return client;
    }

    public void setClient(NodeClient client) {
        this.client = client;
    }

}
