/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.operators.topic;

import io.strimzi.systemtest.AbstractST;
import io.strimzi.systemtest.annotations.IsolatedSuite;
import io.strimzi.systemtest.annotations.ParallelTest;
import io.strimzi.systemtest.templates.crd.KafkaTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTopicTemplates;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaTopicUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtensionContext;

import static io.strimzi.systemtest.Constants.INFRA_NAMESPACE;
import static io.strimzi.systemtest.Constants.SCALABILITY;

@Tag(SCALABILITY)
@IsolatedSuite
public class TopicScalabilityIsolatedST extends AbstractST {

    private static final Logger LOGGER = LogManager.getLogger(TopicScalabilityIsolatedST.class);
    private static final int NUMBER_OF_TOPICS = 1000;
    private static final int SAMPLE_OFFSET = 50;
    private final String sharedClusterName = "topic-scalability-shared-cluster-name";

    @ParallelTest
    void testBigAmountOfTopicsCreatingViaK8s(ExtensionContext extensionContext) {
        final String topicName = "topic-example";

        LOGGER.info("Creating topics via Kubernetes");
        for (int i = 0; i < NUMBER_OF_TOPICS; i++) {
            String currentTopic = topicName + i;
            LOGGER.debug("Creating {} topic", currentTopic);
            resourceManager.createResource(extensionContext, false, KafkaTopicTemplates.topic(sharedClusterName, currentTopic, 3, 1, 1, INFRA_NAMESPACE).build());
        }

        for (int i = 0; i < NUMBER_OF_TOPICS; i = i + SAMPLE_OFFSET) {
            String currentTopic = topicName + i;
            LOGGER.debug("Verifying that {} topic CR has Ready status", currentTopic);

            KafkaTopicUtils.waitForKafkaTopicReady(INFRA_NAMESPACE, currentTopic);
        }

        LOGGER.info("Verifying that we created {} topics", NUMBER_OF_TOPICS);

        KafkaTopicUtils.waitForKafkaTopicsCount(INFRA_NAMESPACE, NUMBER_OF_TOPICS, sharedClusterName);
    }

    @BeforeAll
    void setup(ExtensionContext extensionContext) {
        clusterOperator.unInstall();
        clusterOperator.defaultInstallation().createInstallation().runInstallation();
        LOGGER.info("Deploying shared kafka across all test cases in {} namespace", INFRA_NAMESPACE);
        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaEphemeral(sharedClusterName, 3, 1)
            .editMetadata()
                .withNamespace(INFRA_NAMESPACE)
            .endMetadata()
            .build());
    }

}
