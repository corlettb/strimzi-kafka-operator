/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.operators;

import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.strimzi.operator.common.Annotations;
import io.strimzi.systemtest.AbstractST;
import io.strimzi.systemtest.BeforeAllOnce;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.Environment;
import io.strimzi.systemtest.annotations.IsolatedSuite;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.resources.operator.SetupClusterOperator;
import io.strimzi.systemtest.resources.crd.kafkaclients.KafkaBasicExampleClients;
import io.strimzi.systemtest.templates.crd.KafkaTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTopicTemplates;
import io.strimzi.systemtest.utils.ClientUtils;
import io.strimzi.systemtest.utils.RollingUpdateUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaTopicUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.JobUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.PodUtils;
import io.strimzi.test.annotations.IsolatedTest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.strimzi.systemtest.Constants.INFRA_NAMESPACE;
import static io.strimzi.systemtest.Constants.INTERNAL_CLIENTS_USED;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Feature Gates should give us additional options on
 * how to control and mature different behaviors in the operators.
 * https://github.com/strimzi/proposals/blob/main/022-feature-gates.md
 */
@Tag(REGRESSION)
@IsolatedSuite
public class FeatureGatesIsolatedST extends AbstractST {

    private static final Logger LOGGER = LogManager.getLogger(FeatureGatesIsolatedST.class);

    /**
     * Control Plane Listener
     * https://github.com/strimzi/proposals/blob/main/025-control-plain-listener.md
     */
    @IsolatedTest("Feature Gates test for disabled ControlPlainListener")
    @Tag(INTERNAL_CLIENTS_USED)
    public void testControlPlaneListenerFeatureGate(ExtensionContext extensionContext) {
        assumeFalse(Environment.isOlmInstall() || Environment.isHelmInstall());

        final String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());
        final String producerName = "producer-test-" + new Random().nextInt(Integer.MAX_VALUE);
        final String consumerName = "consumer-test-" + new Random().nextInt(Integer.MAX_VALUE);
        final String topicName = KafkaTopicUtils.generateRandomNameOfTopic();
        final LabelSelector kafkaSelector = KafkaResource.getLabelSelector(clusterName, KafkaResources.zookeeperStatefulSetName(clusterName));

        int messageCount = 300;
        List<EnvVar> testEnvVars = new ArrayList<>();
        int kafkaReplicas = 3;

        testEnvVars.add(new EnvVar(Environment.STRIMZI_FEATURE_GATES_ENV, "-ControlPlaneListener", null));

        clusterOperator.unInstall();
        clusterOperator = new SetupClusterOperator.SetupClusterOperatorBuilder()
            .withExtensionContext(BeforeAllOnce.getSharedExtensionContext())
            .withNamespace(INFRA_NAMESPACE)
            .withWatchingNamespaces(Constants.WATCH_ALL_NAMESPACES)
            .withExtraEnvVars(testEnvVars)
            .createInstallation()
            .runInstallation();

        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaPersistent(clusterName, kafkaReplicas).build());

        LOGGER.info("Check for presence of ContainerPort 9090/tcp (tcp-ctrlplane) in first Kafka pod.");
        final Pod kafkaPod = PodUtils.getPodsByPrefixInNameWithDynamicWait(INFRA_NAMESPACE, clusterName + "-kafka-").get(0);
        ContainerPort expectedControlPlaneContainerPort = new ContainerPort(9090, null, null, "tcp-ctrlplane", "TCP");
        List<ContainerPort> kafkaPodPorts = kafkaPod.getSpec().getContainers().get(0).getPorts();
        assertTrue(kafkaPodPorts.contains(expectedControlPlaneContainerPort));

        Map<String, String> kafkaPods = PodUtils.podSnapshot(INFRA_NAMESPACE, kafkaSelector);

        LOGGER.info("Try to send some messages to Kafka over next few minutes.");
        KafkaTopic kafkaTopic = KafkaTopicTemplates.topic(clusterName, topicName)
            .editSpec()
                .withReplicas(kafkaReplicas)
                .withPartitions(kafkaReplicas)
            .endSpec()
            .build();
        resourceManager.createResource(extensionContext, kafkaTopic);

        KafkaBasicExampleClients kafkaBasicClientJob = new KafkaBasicExampleClients.Builder()
            .withProducerName(producerName)
            .withConsumerName(consumerName)
            .withBootstrapAddress(KafkaResources.plainBootstrapAddress(clusterName))
            .withTopicName(topicName)
            .withMessageCount(messageCount)
            .withDelayMs(500)
            .withNamespaceName(INFRA_NAMESPACE)
            .build();

        resourceManager.createResource(extensionContext, kafkaBasicClientJob.producerStrimzi().build());
        resourceManager.createResource(extensionContext, kafkaBasicClientJob.consumerStrimzi().build());
        JobUtils.waitForJobRunning(consumerName, INFRA_NAMESPACE);

        LOGGER.info("Delete first found Kafka broker pod.");
        kubeClient(INFRA_NAMESPACE).deletePod(INFRA_NAMESPACE, kafkaPod);
        RollingUpdateUtils.waitForComponentAndPodsReady(kafkaSelector, kafkaReplicas);

        LOGGER.info("Force Rolling Update of Kafka via annotation.");
        kafkaPods.keySet().forEach(podName -> {
            kubeClient(INFRA_NAMESPACE).editPod(podName).edit(pod -> new PodBuilder(pod)
                    .editMetadata()
                        .addToAnnotations(Annotations.ANNO_STRIMZI_IO_MANUAL_ROLLING_UPDATE, "true")
                    .endMetadata()
                    .build());
        });
        LOGGER.info("Wait for next reconciliation to happen.");
        RollingUpdateUtils.waitTillComponentHasRolled(INFRA_NAMESPACE, kafkaSelector, kafkaReplicas, kafkaPods);

        LOGGER.info("Waiting for clients to finish sending/receiving messages.");
        ClientUtils.waitForClientSuccess(producerName, INFRA_NAMESPACE, MESSAGE_COUNT);
        ClientUtils.waitForClientSuccess(consumerName, INFRA_NAMESPACE, MESSAGE_COUNT);
    }

    /**
     * UseStrimziPodSets feature gate
     * https://github.com/strimzi/proposals/blob/main/031-statefulset-removal.md
     */
    @IsolatedTest("Feature Gates test for enabled UseStrimziPodSets gate")
    @Tag(INTERNAL_CLIENTS_USED)
    public void testStrimziPodSetsFeatureGate(ExtensionContext extensionContext) {
        assumeFalse(Environment.isOlmInstall() || Environment.isHelmInstall());

        final String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());
        final String producerName = "producer-test-" + new Random().nextInt(Integer.MAX_VALUE);
        final String consumerName = "consumer-test-" + new Random().nextInt(Integer.MAX_VALUE);
        final String topicName = KafkaTopicUtils.generateRandomNameOfTopic();

        final LabelSelector zooSelector = KafkaResource.getLabelSelector(clusterName, KafkaResources.zookeeperStatefulSetName(clusterName));
        final LabelSelector kafkaSelector = KafkaResource.getLabelSelector(clusterName, KafkaResources.kafkaStatefulSetName(clusterName));

        int messageCount = 600;
        List<EnvVar> testEnvVars = new ArrayList<>();
        int zooReplicas = 3;
        int kafkaReplicas = 3;

        testEnvVars.add(new EnvVar(Environment.STRIMZI_FEATURE_GATES_ENV, "+UseStrimziPodSets", null));

        clusterOperator.unInstall();
        clusterOperator = new SetupClusterOperator.SetupClusterOperatorBuilder()
                .withExtensionContext(BeforeAllOnce.getSharedExtensionContext())
                .withNamespace(INFRA_NAMESPACE)
                .withWatchingNamespaces(Constants.WATCH_ALL_NAMESPACES)
                .withExtraEnvVars(testEnvVars)
                .createInstallation()
                .runInstallation();

        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaPersistent(clusterName, kafkaReplicas).build());

        LOGGER.info("Try to send some messages to Kafka over next few minutes.");
        KafkaTopic kafkaTopic = KafkaTopicTemplates.topic(clusterName, topicName)
                .editSpec()
                    .withReplicas(kafkaReplicas)
                    .withPartitions(kafkaReplicas)
                .endSpec()
                .build();
        resourceManager.createResource(extensionContext, kafkaTopic);

        KafkaBasicExampleClients kafkaBasicClientJob = new KafkaBasicExampleClients.Builder()
                .withProducerName(producerName)
                .withConsumerName(consumerName)
                .withBootstrapAddress(KafkaResources.plainBootstrapAddress(clusterName))
                .withTopicName(topicName)
                .withMessageCount(messageCount)
                .withDelayMs(500)
                .withNamespaceName(INFRA_NAMESPACE)
                .build();

        resourceManager.createResource(extensionContext, kafkaBasicClientJob.producerStrimzi().build());
        resourceManager.createResource(extensionContext, kafkaBasicClientJob.consumerStrimzi().build());
        JobUtils.waitForJobRunning(consumerName, INFRA_NAMESPACE);

        // Delete one Zoo Pod
        Pod zooPod = PodUtils.getPodsByPrefixInNameWithDynamicWait(INFRA_NAMESPACE, KafkaResources.zookeeperStatefulSetName(clusterName) + "-").get(0);
        LOGGER.info("Delete first found ZooKeeper pod {}", zooPod.getMetadata().getName());
        kubeClient(INFRA_NAMESPACE).deletePod(INFRA_NAMESPACE, zooPod);
        RollingUpdateUtils.waitForComponentAndPodsReady(zooSelector, zooReplicas);

        // Delete one Kafka Pod
        Pod kafkaPod = PodUtils.getPodsByPrefixInNameWithDynamicWait(INFRA_NAMESPACE, KafkaResources.kafkaStatefulSetName(clusterName) + "-").get(0);
        LOGGER.info("Delete first found Kafka broker pod {}", kafkaPod.getMetadata().getName());
        kubeClient(INFRA_NAMESPACE).deletePod(INFRA_NAMESPACE, kafkaPod);
        RollingUpdateUtils.waitForComponentAndPodsReady(kafkaSelector, kafkaReplicas);

        // Roll Zoo
        LOGGER.info("Force Rolling Update of ZooKeeper via annotation.");
        Map<String, String> zooPods = PodUtils.podSnapshot(INFRA_NAMESPACE, zooSelector);
        zooPods.keySet().forEach(podName -> {
            kubeClient(INFRA_NAMESPACE).editPod(podName).edit(pod -> new PodBuilder(pod)
                    .editMetadata()
                        .addToAnnotations(Annotations.ANNO_STRIMZI_IO_MANUAL_ROLLING_UPDATE, "true")
                    .endMetadata()
                    .build());
        });

        LOGGER.info("Wait for next reconciliation to happen.");
        RollingUpdateUtils.waitTillComponentHasRolled(INFRA_NAMESPACE, zooSelector, zooReplicas, zooPods);

        // Roll Kafka
        LOGGER.info("Force Rolling Update of Kafka via annotation.");
        Map<String, String> kafkaPods = PodUtils.podSnapshot(INFRA_NAMESPACE, kafkaSelector);
        kafkaPods.keySet().forEach(podName -> {
            kubeClient(INFRA_NAMESPACE).editPod(podName).edit(pod -> new PodBuilder(pod)
                    .editMetadata()
                        .addToAnnotations(Annotations.ANNO_STRIMZI_IO_MANUAL_ROLLING_UPDATE, "true")
                    .endMetadata()
                    .build());
        });

        LOGGER.info("Wait for next reconciliation to happen.");
        RollingUpdateUtils.waitTillComponentHasRolled(INFRA_NAMESPACE, kafkaSelector, kafkaReplicas, kafkaPods);

        LOGGER.info("Waiting for clients to finish sending/receiving messages.");
        ClientUtils.waitForClientSuccess(producerName, INFRA_NAMESPACE, MESSAGE_COUNT);
        ClientUtils.waitForClientSuccess(consumerName, INFRA_NAMESPACE, MESSAGE_COUNT);
    }
}
