/*
 * Copyright (c) 2017-2019 AxonIQ B.V. and/or licensed to AxonIQ B.V.
 * under one or more contributor license agreements.
 *
 *  Licensed under the AxonIQ Open Source License Agreement v1.0;
 *  you may not use this file except in compliance with the license.
 *
 */

package io.axoniq.axonserver.localstorage.transaction;

import io.axoniq.axonserver.grpc.event.Event;
import io.axoniq.axonserver.localstorage.EventStorageEngine;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * @author Marc Gathier
 */
public class SingleInstanceTransactionManager implements StorageTransactionManager{
    private final EventStorageEngine eventStorageEngine;
    private final SequenceNumberCache sequenceNumberCache;

    public SingleInstanceTransactionManager(
            EventStorageEngine eventStorageEngine) {
        this.eventStorageEngine = eventStorageEngine;
        this.sequenceNumberCache = new SequenceNumberCache(eventStorageEngine::getLastSequenceNumber);
        eventStorageEngine.registerCloseListener(sequenceNumberCache::close);
    }

    @Override
    public Mono<Long> storeBatch(List<Event> eventList) {
        return Mono.fromFuture(eventStorageEngine.store(eventList));
    }

    @Override
    public Runnable reserveSequenceNumbers(List<Event> eventList) {
        return sequenceNumberCache.reserveSequenceNumbers(eventList, false);
    }
}
