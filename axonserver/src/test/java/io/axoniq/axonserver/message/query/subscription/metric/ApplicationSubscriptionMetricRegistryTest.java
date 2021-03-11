/*
 * Copyright (c) 2017-2019 AxonIQ B.V. and/or licensed to AxonIQ B.V.
 * under one or more contributor license agreements.
 *
 *  Licensed under the AxonIQ Open Source License Agreement v1.0;
 *  you may not use this file except in compliance with the license.
 *
 */

package io.axoniq.axonserver.message.query.subscription.metric;

import io.axoniq.axonserver.applicationevents.SubscriptionQueryEvents;
import io.axoniq.axonserver.grpc.query.QueryRequest;
import io.axoniq.axonserver.grpc.query.QueryUpdate;
import io.axoniq.axonserver.grpc.query.SubscriptionQuery;
import io.axoniq.axonserver.grpc.query.SubscriptionQueryResponse;
import io.axoniq.axonserver.metric.ClusterMetric;
import io.axoniq.axonserver.metric.DefaultMetricCollector;
import io.axoniq.axonserver.metric.MeterFactory;
import io.axoniq.axonserver.metric.MetricCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.*;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;


/**
 * @author Marc Gathier
 */
public class ApplicationSubscriptionMetricRegistryTest {

    private ApplicationSubscriptionMetricRegistry testSubject;
    private Map<String, ClusterMetric> clusterMetricMap = new HashMap<>();

    @Before
    public void setUp() {
        MetricCollector metricCollector = new DefaultMetricCollector();
        testSubject = new ApplicationSubscriptionMetricRegistry(new MeterFactory(new SimpleMeterRegistry(),
                                                                                 metricCollector),
                                                                metricCollector::apply);
    }

    @Test
    public void getInitial() {
        HubSubscriptionMetrics metric = testSubject.get("myComponent", "myContext");
        assertEquals(0, (long) metric.activesCount());
    }

    @Test
    public void getAfterSubscribe() {
        testSubject.on(new SubscriptionQueryEvents.SubscriptionQueryStarted("myContext",
                                                                            SubscriptionQuery.newBuilder()
                                                                                             .setSubscriptionIdentifier(
                                                                                                     "Subscription-1")
                                                                                             .setQueryRequest(
                                                                                                     QueryRequest
                                                                                                             .newBuilder()
                                                                                                             .setComponentName(
                                                                                                                     "myComponent")
                                                                                             )
                                                                                             .build(),
                                                                            null,
                                                                            null));
        HubSubscriptionMetrics metric = testSubject.get("myComponent", "myContext");
        assertEquals(1, (long) metric.activesCount());

        testSubject.on(new SubscriptionQueryEvents.SubscriptionQueryResponseReceived(SubscriptionQueryResponse
                                                                                             .newBuilder()
                                                                                             .setSubscriptionIdentifier(
                                                                                                     "Subscription-1")
                                                                                             .setUpdate(
                                                                                                     QueryUpdate
                                                                                                             .newBuilder()
                                                                                             )
                                                                                             .build()));
        metric = testSubject.get("myComponent", "myContext");
        assertEquals(1, (long) metric.activesCount());
        assertEquals(1, (long) metric.updatesCount());
    }
}