/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.application;

import com.carrotsearch.randomizedtesting.annotations.Name;

import org.elasticsearch.client.ResponseException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.test.http.MockRequest;
import org.elasticsearch.test.http.MockResponse;
import org.elasticsearch.test.http.MockWebServer;
import org.elasticsearch.xpack.inference.services.cohere.embeddings.CohereEmbeddingType;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.oneOf;

public class CohereServiceUpgradeIT extends InferenceUpgradeTestCase {

    // TODO: replace with proper test features
    private static final String COHERE_EMBEDDINGS_ADDED_TEST_FEATURE = "gte_v8.13.0";
    private static final String COHERE_RERANK_ADDED_TEST_FEATURE = "gte_v8.14.0";
    private static final String COHERE_COMPLETIONS_ADDED_TEST_FEATURE = "gte_v8.15.0";
    private static final String COHERE_V2_API_ADDED_TEST_FEATURE = "inference.cohere.v2";

    private static MockWebServer cohereEmbeddingsServer;
    private static MockWebServer cohereRerankServer;
    private static MockWebServer cohereCompletionsServer;

    private enum ApiVersion {
        V1,
        V2
    }

    public CohereServiceUpgradeIT(@Name("upgradedNodes") int upgradedNodes) {
        super(upgradedNodes);
    }

    @BeforeClass
    public static void startWebServer() throws IOException {
        cohereEmbeddingsServer = new MockWebServer();
        cohereEmbeddingsServer.start();

        cohereRerankServer = new MockWebServer();
        cohereRerankServer.start();

        cohereCompletionsServer = new MockWebServer();
        cohereCompletionsServer.start();
    }

    @AfterClass
    public static void shutdown() {
        cohereEmbeddingsServer.close();
        cohereRerankServer.close();
        cohereCompletionsServer.close();
    }

    @SuppressWarnings("unchecked")
    public void testCohereEmbeddings() throws IOException {
        var embeddingsSupported = oldClusterHasFeature(COHERE_EMBEDDINGS_ADDED_TEST_FEATURE);
        assumeTrue("Cohere embedding service supported", embeddingsSupported);

        String oldClusterEndpointIdentifier = oldClusterHasFeature(MODELS_RENAMED_TO_ENDPOINTS_FEATURE) ? "endpoints" : "models";
        ApiVersion oldClusterApiVersion = oldClusterHasFeature(COHERE_V2_API_ADDED_TEST_FEATURE) ? ApiVersion.V2 : ApiVersion.V1;

        final String oldClusterIdInt8 = "old-cluster-embeddings-int8";
        final String oldClusterIdFloat = "old-cluster-embeddings-float";

        var testTaskType = TaskType.TEXT_EMBEDDING;

        if (isOldCluster()) {

            // queue a response as PUT will call the service
            cohereEmbeddingsServer.enqueue(new MockResponse().setResponseCode(200).setBody(embeddingResponseByte()));
            put(oldClusterIdInt8, embeddingConfigInt8(getUrl(cohereEmbeddingsServer)), testTaskType);
            // float model
            cohereEmbeddingsServer.enqueue(new MockResponse().setResponseCode(200).setBody(embeddingResponseFloat()));
            put(oldClusterIdFloat, embeddingConfigFloat(getUrl(cohereEmbeddingsServer)), testTaskType);

            {
                var configs = (List<Map<String, Object>>) get(testTaskType, oldClusterIdInt8).get(oldClusterEndpointIdentifier);
                assertThat(configs, hasSize(1));
                assertEquals("cohere", configs.get(0).get("service"));
                var serviceSettings = (Map<String, Object>) configs.get(0).get("service_settings");
                assertThat(serviceSettings, hasEntry("model_id", "embed-english-light-v3.0"));
                var embeddingType = serviceSettings.get("embedding_type");
                // An upgraded node will report the embedding type as byte, the old node int8
                assertThat(embeddingType, Matchers.is(oneOf("int8", "byte")));
                assertEmbeddingInference(oldClusterIdInt8, CohereEmbeddingType.BYTE);
            }
            {
                var configs = (List<Map<String, Object>>) get(testTaskType, oldClusterIdFloat).get(oldClusterEndpointIdentifier);
                assertThat(configs, hasSize(1));
                assertEquals("cohere", configs.get(0).get("service"));
                var serviceSettings = (Map<String, Object>) configs.get(0).get("service_settings");
                assertThat(serviceSettings, hasEntry("model_id", "embed-english-light-v3.0"));
                assertThat(serviceSettings, hasEntry("embedding_type", "float"));
                assertEmbeddingInference(oldClusterIdFloat, CohereEmbeddingType.FLOAT);
            }
        } else if (isMixedCluster()) {
            {
                var configs = getConfigsWithBreakingChangeHandling(testTaskType, oldClusterIdInt8);
                assertEquals("cohere", configs.get(0).get("service"));
                var serviceSettings = (Map<String, Object>) configs.get(0).get("service_settings");
                assertThat(serviceSettings, hasEntry("model_id", "embed-english-light-v3.0"));
                var embeddingType = serviceSettings.get("embedding_type");
                // An upgraded node will report the embedding type as byte, an old node int8
                assertThat(embeddingType, Matchers.is(oneOf("int8", "byte")));
                assertEmbeddingInference(oldClusterIdInt8, CohereEmbeddingType.BYTE);
            }
            {
                var configs = getConfigsWithBreakingChangeHandling(testTaskType, oldClusterIdFloat);
                assertEquals("cohere", configs.get(0).get("service"));
                var serviceSettings = (Map<String, Object>) configs.get(0).get("service_settings");
                assertThat(serviceSettings, hasEntry("model_id", "embed-english-light-v3.0"));
                assertThat(serviceSettings, hasEntry("embedding_type", "float"));
                assertEmbeddingInference(oldClusterIdFloat, CohereEmbeddingType.FLOAT);
            }
        } else if (isUpgradedCluster()) {
            // check old cluster model
            var configs = (List<Map<String, Object>>) get(testTaskType, oldClusterIdInt8).get("endpoints");
            var serviceSettings = (Map<String, Object>) configs.get(0).get("service_settings");
            assertThat(serviceSettings, hasEntry("model_id", "embed-english-light-v3.0"));
            assertThat(serviceSettings, hasEntry("embedding_type", "byte"));
            var taskSettings = (Map<String, Object>) configs.get(0).get("task_settings");
            assertThat(taskSettings, anyOf(nullValue(), anEmptyMap()));

            // Inference on old cluster models
            assertEmbeddingInference(oldClusterIdInt8, CohereEmbeddingType.BYTE);
            assertVersionInPath(cohereEmbeddingsServer.requests().getLast(), "embed", oldClusterApiVersion);
            assertEmbeddingInference(oldClusterIdFloat, CohereEmbeddingType.FLOAT);
            assertVersionInPath(cohereEmbeddingsServer.requests().getLast(), "embed", oldClusterApiVersion);

            {
                final String upgradedClusterIdByte = "upgraded-cluster-embeddings-byte";

                // new endpoints use the V2 API
                cohereEmbeddingsServer.enqueue(new MockResponse().setResponseCode(200).setBody(embeddingResponseByte()));
                put(upgradedClusterIdByte, embeddingConfigByte(getUrl(cohereEmbeddingsServer)), testTaskType);
                assertVersionInPath(cohereEmbeddingsServer.requests().getLast(), "embed", ApiVersion.V2);

                configs = (List<Map<String, Object>>) get(testTaskType, upgradedClusterIdByte).get("endpoints");
                serviceSettings = (Map<String, Object>) configs.get(0).get("service_settings");
                assertThat(serviceSettings, hasEntry("embedding_type", "byte"));

                assertEmbeddingInference(upgradedClusterIdByte, CohereEmbeddingType.BYTE);
                delete(upgradedClusterIdByte);
            }
            {
                final String upgradedClusterIdInt8 = "upgraded-cluster-embeddings-int8";

                // new endpoints use the V2 API
                cohereEmbeddingsServer.enqueue(new MockResponse().setResponseCode(200).setBody(embeddingResponseByte()));
                put(upgradedClusterIdInt8, embeddingConfigInt8(getUrl(cohereEmbeddingsServer)), testTaskType);
                assertVersionInPath(cohereEmbeddingsServer.requests().getLast(), "embed", ApiVersion.V2);

                configs = (List<Map<String, Object>>) get(testTaskType, upgradedClusterIdInt8).get("endpoints");
                serviceSettings = (Map<String, Object>) configs.get(0).get("service_settings");
                assertThat(serviceSettings, hasEntry("embedding_type", "byte")); // int8 rewritten to byte

                assertEmbeddingInference(upgradedClusterIdInt8, CohereEmbeddingType.INT8);
                assertVersionInPath(cohereEmbeddingsServer.requests().getLast(), "embed", ApiVersion.V2);
                delete(upgradedClusterIdInt8);
            }
            {
                final String upgradedClusterIdFloat = "upgraded-cluster-embeddings-float";
                cohereEmbeddingsServer.enqueue(new MockResponse().setResponseCode(200).setBody(embeddingResponseFloat()));
                put(upgradedClusterIdFloat, embeddingConfigFloat(getUrl(cohereEmbeddingsServer)), testTaskType);
                assertVersionInPath(cohereEmbeddingsServer.requests().getLast(), "embed", ApiVersion.V2);

                configs = (List<Map<String, Object>>) get(testTaskType, upgradedClusterIdFloat).get("endpoints");
                serviceSettings = (Map<String, Object>) configs.get(0).get("service_settings");
                assertThat(serviceSettings, hasEntry("embedding_type", "float"));

                assertEmbeddingInference(upgradedClusterIdFloat, CohereEmbeddingType.FLOAT);
                assertVersionInPath(cohereEmbeddingsServer.requests().getLast(), "embed", ApiVersion.V2);
                delete(upgradedClusterIdFloat);
            }
            {
                // new endpoints use the V2 API which require the model to be set
                final String upgradedClusterNoModel = "upgraded-cluster-missing-model-id";
                var jsonBody = Strings.format("""
                    {
                        "service": "cohere",
                        "service_settings": {
                            "url": "%s",
                            "api_key": "XXXX",
                            "embedding_type": "int8"
                        }
                    }
                    """, getUrl(cohereEmbeddingsServer));

                var e = expectThrows(ResponseException.class, () -> put(upgradedClusterNoModel, jsonBody, testTaskType));
                assertThat(
                    e.getMessage(),
                    containsString("Validation Failed: 1: The [service_settings.model_id] field is required for the Cohere V2 API.")
                );
            }

            delete(oldClusterIdFloat);
            delete(oldClusterIdInt8);
        }
    }

    private void assertVersionInPath(MockRequest request, String endpoint, ApiVersion apiVersion) {
        switch (apiVersion) {
            case V2:
                assertEquals("/v2/" + endpoint, request.getUri().getPath());
                break;
            case V1:
                assertEquals("/v1/" + endpoint, request.getUri().getPath());
                break;
        }
    }

    void assertEmbeddingInference(String inferenceId, CohereEmbeddingType type) throws IOException {
        switch (type) {
            case INT8:
            case BYTE:
                cohereEmbeddingsServer.enqueue(new MockResponse().setResponseCode(200).setBody(embeddingResponseByte()));
                break;
            case FLOAT:
                cohereEmbeddingsServer.enqueue(new MockResponse().setResponseCode(200).setBody(embeddingResponseFloat()));
        }

        var inferenceMap = inference(inferenceId, TaskType.TEXT_EMBEDDING, "some text");
        assertThat(inferenceMap.entrySet(), not(empty()));
    }

    @SuppressWarnings("unchecked")
    public void testRerank() throws IOException {
        var rerankSupported = oldClusterHasFeature(COHERE_RERANK_ADDED_TEST_FEATURE);
        assumeTrue("Cohere rerank service supported", rerankSupported);

        String old_cluster_endpoint_identifier = oldClusterHasFeature(MODELS_RENAMED_TO_ENDPOINTS_FEATURE) ? "endpoints" : "models";
        ApiVersion oldClusterApiVersion = oldClusterHasFeature(COHERE_V2_API_ADDED_TEST_FEATURE) ? ApiVersion.V2 : ApiVersion.V1;

        final String oldClusterId = "old-cluster-rerank";
        final String upgradedClusterId = "upgraded-cluster-rerank";

        var testTaskType = TaskType.RERANK;

        if (isOldCluster()) {
            cohereRerankServer.enqueue(new MockResponse().setResponseCode(200).setBody(rerankResponse()));
            put(oldClusterId, rerankConfig(getUrl(cohereRerankServer)), testTaskType);
            var configs = (List<Map<String, Object>>) get(testTaskType, oldClusterId).get(old_cluster_endpoint_identifier);
            assertThat(configs, hasSize(1));

            assertRerank(oldClusterId);
        } else if (isMixedCluster()) {
            var configs = getConfigsWithBreakingChangeHandling(testTaskType, oldClusterId);

            assertEquals("cohere", configs.get(0).get("service"));
            var serviceSettings = (Map<String, Object>) configs.get(0).get("service_settings");
            assertThat(serviceSettings, hasEntry("model_id", "rerank-english-v3.0"));
            var taskSettings = (Map<String, Object>) configs.get(0).get("task_settings");
            assertThat(taskSettings, hasEntry("top_n", 3));

            assertRerank(oldClusterId);
        } else if (isUpgradedCluster()) {
            // check old cluster model
            var configs = (List<Map<String, Object>>) get(testTaskType, oldClusterId).get("endpoints");
            assertEquals("cohere", configs.get(0).get("service"));
            var serviceSettings = (Map<String, Object>) configs.get(0).get("service_settings");
            assertThat(serviceSettings, hasEntry("model_id", "rerank-english-v3.0"));
            var taskSettings = (Map<String, Object>) configs.get(0).get("task_settings");
            assertThat(taskSettings, hasEntry("top_n", 3));

            assertRerank(oldClusterId);
            assertVersionInPath(cohereRerankServer.requests().getLast(), "rerank", oldClusterApiVersion);

            // New endpoint
            cohereRerankServer.enqueue(new MockResponse().setResponseCode(200).setBody(rerankResponse()));
            put(upgradedClusterId, rerankConfig(getUrl(cohereRerankServer)), testTaskType);
            configs = (List<Map<String, Object>>) get(upgradedClusterId).get("endpoints");
            assertThat(configs, hasSize(1));

            assertRerank(upgradedClusterId);
            assertVersionInPath(cohereRerankServer.requests().getLast(), "rerank", ApiVersion.V2);

            {
                // new endpoints use the V2 API which require the model_id to be set
                final String upgradedClusterNoModel = "upgraded-cluster-missing-model-id";
                var jsonBody = Strings.format("""
                    {
                        "service": "cohere",
                        "service_settings": {
                            "url": "%s",
                            "api_key": "XXXX"
                        }
                    }
                    """, getUrl(cohereEmbeddingsServer));

                var e = expectThrows(ResponseException.class, () -> put(upgradedClusterNoModel, jsonBody, testTaskType));
                assertThat(
                    e.getMessage(),
                    containsString("Validation Failed: 1: The [service_settings.model_id] field is required for the Cohere V2 API.")
                );
            }

            delete(oldClusterId);
            delete(upgradedClusterId);
        }
    }

    private void assertRerank(String inferenceId) throws IOException {
        cohereRerankServer.enqueue(new MockResponse().setResponseCode(200).setBody(rerankResponse()));
        var inferenceMap = rerank(
            inferenceId,
            List.of("luke", "like", "leia", "chewy", "r2d2", "star", "wars"),
            "star wars main character"
        );
        assertThat(inferenceMap.entrySet(), not(empty()));
    }

    @SuppressWarnings("unchecked")
    public void testCohereCompletions() throws IOException {
        var completionsSupported = oldClusterHasFeature(COHERE_COMPLETIONS_ADDED_TEST_FEATURE);
        assumeTrue("Cohere completions not supported", completionsSupported);

        ApiVersion oldClusterApiVersion = oldClusterHasFeature(COHERE_V2_API_ADDED_TEST_FEATURE) ? ApiVersion.V2 : ApiVersion.V1;

        final String oldClusterId = "old-cluster-completions";

        if (isOldCluster()) {
            // queue a response as PUT will call the service
            cohereCompletionsServer.enqueue(new MockResponse().setResponseCode(200).setBody(completionsResponse(oldClusterApiVersion)));
            put(oldClusterId, completionsConfig(getUrl(cohereCompletionsServer)), TaskType.COMPLETION);

            var configs = (List<Map<String, Object>>) get(TaskType.COMPLETION, oldClusterId).get("endpoints");
            assertThat(configs, hasSize(1));
            assertEquals("cohere", configs.get(0).get("service"));
            var serviceSettings = (Map<String, Object>) configs.get(0).get("service_settings");
            assertThat(serviceSettings, hasEntry("model_id", "command"));
        } else if (isMixedCluster()) {
            var configs = (List<Map<String, Object>>) get(TaskType.COMPLETION, oldClusterId).get("endpoints");
            assertThat(configs, hasSize(1));
            assertEquals("cohere", configs.get(0).get("service"));
            var serviceSettings = (Map<String, Object>) configs.get(0).get("service_settings");
            assertThat(serviceSettings, hasEntry("model_id", "command"));
        } else if (isUpgradedCluster()) {
            // check old cluster model
            var configs = (List<Map<String, Object>>) get(TaskType.COMPLETION, oldClusterId).get("endpoints");
            var serviceSettings = (Map<String, Object>) configs.get(0).get("service_settings");
            assertThat(serviceSettings, hasEntry("model_id", "command"));

            final String newClusterId = "new-cluster-completions";
            {
                cohereCompletionsServer.enqueue(new MockResponse().setResponseCode(200).setBody(completionsResponse(oldClusterApiVersion)));
                var inferenceMap = inference(oldClusterId, TaskType.COMPLETION, "some text");
                assertThat(inferenceMap.entrySet(), not(empty()));
                assertVersionInPath(cohereCompletionsServer.requests().getLast(), "chat", oldClusterApiVersion);
            }
            {
                // new cluster uses the V2 API
                cohereCompletionsServer.enqueue(new MockResponse().setResponseCode(200).setBody(completionsResponse(ApiVersion.V2)));
                put(newClusterId, completionsConfig(getUrl(cohereCompletionsServer)), TaskType.COMPLETION);

                cohereCompletionsServer.enqueue(new MockResponse().setResponseCode(200).setBody(completionsResponse(ApiVersion.V2)));
                var inferenceMap = inference(newClusterId, TaskType.COMPLETION, "some text");
                assertThat(inferenceMap.entrySet(), not(empty()));
                assertVersionInPath(cohereCompletionsServer.requests().getLast(), "chat", ApiVersion.V2);
            }

            {
                // new endpoints use the V2 API which require the model to be set
                final String upgradedClusterNoModel = "upgraded-cluster-missing-model-id";
                var jsonBody = Strings.format("""
                    {
                        "service": "cohere",
                        "service_settings": {
                            "url": "%s",
                            "api_key": "XXXX"
                        }
                    }
                    """, getUrl(cohereEmbeddingsServer));

                var e = expectThrows(ResponseException.class, () -> put(upgradedClusterNoModel, jsonBody, TaskType.COMPLETION));
                assertThat(
                    e.getMessage(),
                    containsString("Validation Failed: 1: The [service_settings.model_id] field is required for the Cohere V2 API.")
                );
            }

            delete(oldClusterId);
            delete(newClusterId);
        }
    }

    private String embeddingConfigByte(String url) {
        return embeddingConfigTemplate(url, "byte");
    }

    private String embeddingConfigInt8(String url) {
        return embeddingConfigTemplate(url, "int8");
    }

    private String embeddingConfigFloat(String url) {
        return embeddingConfigTemplate(url, "float");
    }

    private String embeddingConfigTemplate(String url, String embeddingType) {
        return Strings.format("""
            {
                "service": "cohere",
                "service_settings": {
                    "url": "%s",
                    "api_key": "XXXX",
                    "model_id": "embed-english-light-v3.0",
                    "embedding_type": "%s"
                }
            }
            """, url, embeddingType);
    }

    private String embeddingResponseByte() {
        return """
            {
                "id": "3198467e-399f-4d4a-aa2c-58af93bd6dc4",
                "texts": [
                    "hello"
                ],
                "embeddings": [
                    [
                        12,
                        56
                    ]
                ],
                "meta": {
                    "api_version": {
                        "version": "1"
                    },
                    "billed_units": {
                        "input_tokens": 1
                    }
                },
                "response_type": "embeddings_bytes"
            }
            """;
    }

    private String embeddingResponseFloat() {
        return """
            {
                "id": "3198467e-399f-4d4a-aa2c-58af93bd6dc4",
                "texts": [
                    "hello"
                ],
                "embeddings": [
                    [
                        -0.0018434525,
                        0.01777649
                    ]
                ],
                "meta": {
                    "api_version": {
                        "version": "1"
                    },
                    "billed_units": {
                        "input_tokens": 1
                    }
                },
                "response_type": "embeddings_floats"
            }
            """;
    }

    private String rerankConfig(String url) {
        return Strings.format("""
            {
                "service": "cohere",
                "service_settings": {
                    "api_key": "XXXX",
                    "model_id": "rerank-english-v3.0",
                    "url": "%s"
                },
                "task_settings": {
                    "return_documents": false,
                    "top_n": 3
                }
            }
            """, url);
    }

    private String rerankResponse() {
        return """
            {
                "index": "d0760819-5a73-4d58-b163-3956d3648b62",
                "results": [
                    {
                        "index": 2,
                        "relevance_score": 0.98005307
                    },
                    {
                        "index": 3,
                        "relevance_score": 0.27904198
                    },
                    {
                        "index": 0,
                        "relevance_score": 0.10194652
                    }
                ],
                "meta": {
                    "api_version": {
                        "version": "1"
                    },
                    "billed_units": {
                        "search_units": 1
                    }
                }
            }
            """;
    }

    private String completionsConfig(String url) {
        return Strings.format("""
            {
                "service": "cohere",
                "service_settings": {
                    "api_key": "XXXX",
                    "model_id": "command",
                    "url": "%s"
                }
            }
            """, url);
    }

    private String completionsResponse(ApiVersion version) {
        return switch (version) {
            case V1 -> v1CompletionsResponse();
            case V2 -> v2CompletionsResponse();
        };
    }

    private String v1CompletionsResponse() {
        return """
            {
                "response_id": "some id",
                "text": "result",
                "generation_id": "some id",
                "chat_history": [
                    {
                        "role": "USER",
                        "message": "some input"
                    },
                    {
                        "role": "CHATBOT",
                        "message": "v1 response from the llm"
                    }
                ],
                "finish_reason": "COMPLETE",
                "meta": {
                    "api_version": {
                        "version": "1"
                    },
                    "billed_units": {
                        "input_tokens": 4,
                        "output_tokens": 191
                    },
                    "tokens": {
                        "input_tokens": 70,
                        "output_tokens": 191
                    }
                }
            }
            """;
    }

    private String v2CompletionsResponse() {
        return """
            {
              "id": "c14c80c3-18eb-4519-9460-6c92edd8cfb4",
              "finish_reason": "COMPLETE",
              "message": {
                "role": "assistant",
                "content": [
                  {
                    "type": "text",
                    "text": "v2 response from the LLM"
                  }
                ]
              },
              "usage": {
                "billed_units": {
                  "input_tokens": 1,
                  "output_tokens": 2
                },
                "tokens": {
                  "input_tokens": 3,
                  "output_tokens": 4
                }
              }
            }
            """;
    }

}
