/*
 *  Copyright (c) 2017-2022 AxonIQ B.V. and/or licensed to AxonIQ B.V.
 *  under one or more contributor license agreements.
 *
 *  Licensed under the AxonIQ Open Source License Agreement v1.0;
 *  you may not use this file except in compliance with the license.
 *
 */

package io.axoniq.axonserver.localstorage.file;

import io.axoniq.axonserver.exception.ErrorCode;
import io.axoniq.axonserver.exception.EventStoreValidationException;
import io.axoniq.axonserver.exception.MessagingPlatformException;
import io.axoniq.axonserver.grpc.event.Event;
import io.axoniq.axonserver.grpc.event.EventWithToken;
import io.axoniq.axonserver.localstorage.EventStorageEngine;
import io.axoniq.axonserver.localstorage.EventType;
import io.axoniq.axonserver.localstorage.EventTypeContext;
import io.axoniq.axonserver.localstorage.QueryOptions;
import io.axoniq.axonserver.localstorage.Registration;
import io.axoniq.axonserver.localstorage.SerializedEvent;
import io.axoniq.axonserver.localstorage.SerializedEventWithToken;
import io.axoniq.axonserver.localstorage.SerializedTransactionWithToken;
import io.axoniq.axonserver.metric.BaseMetricName;
import io.axoniq.axonserver.metric.MeterFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.util.CloseableIterator;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.String.format;

import static io.axoniq.axonserver.localstorage.file.FileUtils.name;


/**
 * @author Marc Gathier
 * @since 4.0
 */
public abstract class SegmentBasedEventStore implements EventStorageEngine {

    public static final byte TRANSACTION_VERSION = 2;
    protected static final Logger logger = LoggerFactory.getLogger(SegmentBasedEventStore.class);
    protected static final int MAX_SEGMENTS_FOR_SEQUENCE_NUMBER_CHECK = 10;
    protected static final int VERSION_BYTES = 1;
    protected static final int FILE_OPTIONS_BYTES = 4;
    protected static final int TX_CHECKSUM_BYTES = 4;
    protected static final byte VERSION = 2;
    private static final int TRANSACTION_LENGTH_BYTES = 4;
    private static final int NUMBER_OF_EVENTS_BYTES = 2;
    protected static final int HEADER_BYTES = TRANSACTION_LENGTH_BYTES + VERSION_BYTES + NUMBER_OF_EVENTS_BYTES;
    protected static final int FILE_HEADER_SIZE = VERSION_BYTES + FILE_OPTIONS_BYTES;
    protected static final int FILE_FOOTER_SIZE = 4;
    protected static final int MAX_TRANSACTION_SIZE = Integer.MAX_VALUE - FILE_HEADER_SIZE - FILE_FOOTER_SIZE;
    protected final String context;
    protected final IndexManager indexManager;
    protected final Supplier<StorageProperties> storagePropertiesSupplier;
    protected final EventTypeContext type;
    protected final Set<Runnable> closeListeners = new CopyOnWriteArraySet<>();
    private final Timer lastSequenceReadTimer;
    protected final SegmentBasedEventStore next;
    private static final int PREFETCH_SEGMENT_FILES = 2;
    protected final Counter fileOpenMeter;
    private final DistributionSummary aggregateSegmentsCount;

    public SegmentBasedEventStore(EventTypeContext eventTypeContext, IndexManager indexManager,
                                  Supplier<StorageProperties> storagePropertiesSupplier, MeterFactory meterFactory) {
        this(eventTypeContext, indexManager, storagePropertiesSupplier, null, meterFactory);
    }

    public SegmentBasedEventStore(EventTypeContext eventTypeContext, IndexManager indexManager,
                                  Supplier<StorageProperties> storagePropertiesSupplier,
                                  SegmentBasedEventStore nextSegmentsHandler,
                                  MeterFactory meterFactory) {
        this.type = eventTypeContext;
        this.context = eventTypeContext.getContext();
        this.indexManager = indexManager;
        this.storagePropertiesSupplier = storagePropertiesSupplier;
        this.next = nextSegmentsHandler;
        Tags tags = Tags.of(MeterFactory.CONTEXT, context, "type", eventTypeContext.getEventType().name());
        this.fileOpenMeter = meterFactory.counter(BaseMetricName.AXON_SEGMENT_OPEN, tags);
        this.lastSequenceReadTimer = meterFactory.timer(BaseMetricName.AXON_LAST_SEQUENCE_READTIME, tags);
        this.aggregateSegmentsCount = meterFactory.distributionSummary
                                                          (BaseMetricName.AXON_AGGREGATE_SEGMENT_COUNT, tags);
    }

    public abstract void handover(Long segment, Runnable callback);

    public Flux<SerializedEvent> eventsPerAggregate(String aggregateId,
                                                    long firstSequence,
                                                    long lastSequence,
                                                    long minToken) {
        return Flux.defer(() -> {
                       logger.debug("Reading index entries for aggregate {} started.", aggregateId);

                       SortedMap<Long, IndexEntries> positionInfos = indexManager.lookupAggregate(aggregateId,
                                                                                                  firstSequence,
                                                                                                  lastSequence,
                                                                                                  Long.MAX_VALUE,
                                                                                                  minToken);
                       logger.debug("Reading index entries for aggregate {} finished.", aggregateId);
                       aggregateSegmentsCount.record(positionInfos.size());

                       return Flux.fromIterable(positionInfos.entrySet());
                   }).flatMapSequential(e -> eventsForPositions(e.getKey(),
                                                                e.getValue(),
                                                                storagePropertiesSupplier.get().getEventsPerSegmentPrefetch()),
                                        PREFETCH_SEGMENT_FILES,
                                        storagePropertiesSupplier.get().getEventsPerSegmentPrefetch())
                   .skipUntil(se -> se.getAggregateSequenceNumber() >= firstSequence) //todo for safe guard, remove in 4.6
                   .takeWhile(se -> se.getAggregateSequenceNumber() < lastSequence)
                   .name("event_stream")
                   .tag("context", context)
                   .tag("stream", "aggregate_events")
                   .tag("origin", "event_sources")
                   .metrics();
    }

    private Flux<SerializedEvent> eventsForPositions(long segment, IndexEntries indexEntries, int prefetch) {
        return (!containsSegment(segment) && next != null) ?
                next.eventsForPositions(segment, indexEntries, prefetch) :
                new EventSourceFlux(indexEntries,
                                    () -> eventSource(segment),
                                    segment,
                                    prefetch).get()
                                             .name("event_stream")
                                             .tag("context", context)
                                             .tag("stream",
                                                  "aggregate_events")
                                             .tag("origin",
                                                  "event_source")
                                             .metrics();
    }

    /**
     * Returns {@code true} if this instance is resposnsible to handling the specified segment, {@code false}
     * otherwise.
     *
     * @param segment the segment to check
     * @return {@code true} if this instance is resposnsible to handling the specified segment, {@code false} otherwise.
     */
    protected abstract boolean containsSegment(long segment);

    @Override
    public void processEventsPerAggregate(String aggregateId, long firstSequenceNumber, long lastSequenceNumber,
                                          long minToken, Consumer<SerializedEvent> eventConsumer) {
        SortedMap<Long, IndexEntries> positionInfos = indexManager.lookupAggregate(aggregateId,
                                                                                   firstSequenceNumber,
                                                                                   lastSequenceNumber,
                                                                                   Long.MAX_VALUE,
                                                                                   minToken);
        positionInfos.forEach((segment, positionInfo) -> retrieveEventsForAnAggregate(segment,
                                                                                      positionInfo.positions(),
                                                                                      firstSequenceNumber,
                                                                                      lastSequenceNumber,
                                                                                      eventConsumer,
                                                                                      Long.MAX_VALUE,
                                                                                      minToken));
    }

    @Override
    public void processEventsPerAggregateHighestFirst(String aggregateId, long firstSequenceNumber,
                                                      long maxSequenceNumber,
                                                      int maxResults, Consumer<SerializedEvent> eventConsumer) {
        SortedMap<Long, IndexEntries> positionInfos = indexManager.lookupAggregate(aggregateId,
                                                                                   firstSequenceNumber,
                                                                                   maxSequenceNumber,
                                                                                   maxResults,
                                                                                   0);

        List<Long> segmentsContainingAggregate = new ArrayList<>(positionInfos.keySet());
        Collections.reverse(segmentsContainingAggregate);
        for (Long segmentContainingAggregate : segmentsContainingAggregate) {
            IndexEntries entries = positionInfos.get(segmentContainingAggregate);
            List<Integer> positions = new ArrayList<>(entries.positions());
            Collections.reverse(positions);
            maxResults -= retrieveEventsForAnAggregate(segmentContainingAggregate,
                                                       positions,
                                                       firstSequenceNumber,
                                                       maxSequenceNumber,
                                                       eventConsumer,
                                                       maxResults, 0);
            if (maxResults <= 0) {
                return;
            }
        }
    }


    @Override
    public void query(QueryOptions queryOptions, Predicate<EventWithToken> consumer) {
        for (long segment : getSegments()) {
            if (segment <= queryOptions.getMaxToken()) {
                Optional<EventSource> eventSource = getEventSource(segment);
                AtomicBoolean done = new AtomicBoolean();
                boolean snapshot = EventType.SNAPSHOT.equals(type.getEventType());
                eventSource.ifPresent(e -> {
                    long minTimestampInSegment = Long.MAX_VALUE;
                    EventInformation eventWithToken;
                    EventIterator iterator = createEventIterator(e, segment, segment);
                    while (iterator.hasNext()) {
                        eventWithToken = iterator.next();
                        minTimestampInSegment = Math.min(minTimestampInSegment,
                                                         eventWithToken.getEvent().getTimestamp());
                        if (eventWithToken.getToken() > queryOptions.getMaxToken()) {
                            iterator.close();
                            return;
                        }
                        if (eventWithToken.getToken() >= queryOptions.getMinToken()
                                && eventWithToken.getEvent().getTimestamp() >= queryOptions.getMinTimestamp()
                                && !consumer.test(eventWithToken.asEventWithToken(snapshot))) {
                            iterator.close();
                            return;
                        }
                    }
                    if (queryOptions.getMinToken() > segment || minTimestampInSegment < queryOptions
                            .getMinTimestamp()) {
                        done.set(true);
                    }
                    iterator.close();
                });
                if (done.get()) {
                    return;
                }
            }
        }

        if (next != null) {
            next.query(new QueryOptions(queryOptions.getMinToken(),
                                        queryOptions.getMaxToken(),
                                        queryOptions.getMinTimestamp()), consumer);
        }
    }

    protected EventIterator createEventIterator(EventSource e, long segment, long startToken) {
        return e.createEventIterator(segment, startToken);
    }

    @Override
    public Optional<Long> getLastSequenceNumber(String aggregateIdentifier, SearchHint[] hints) {
        return getLastSequenceNumber(aggregateIdentifier, contains(hints, SearchHint.RECENT_ONLY) ?
                MAX_SEGMENTS_FOR_SEQUENCE_NUMBER_CHECK : Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    private <T> boolean contains(T[] values, T value) {
        for (T t : values) {
            if (t.equals(value)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Optional<Long> getLastSequenceNumber(String aggregateIdentifier, int maxSegmentsHint, long maxTokenHint) {
        long before = System.currentTimeMillis();
        try {
            return indexManager.getLastSequenceNumber(aggregateIdentifier, maxSegmentsHint, maxTokenHint);
        } finally {
            lastSequenceReadTimer.record(System.currentTimeMillis() - before, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public Optional<SerializedEvent> getLastEvent(String aggregateId, long minSequenceNumber, long maxSequenceNumber) {

        SegmentIndexEntries lastEventPosition = indexManager.lastIndexEntries(aggregateId, maxSequenceNumber);
        if (lastEventPosition == null) {
            return Optional.empty();
        }

        return readSerializedEvent(minSequenceNumber, maxSequenceNumber, lastEventPosition);
    }

    private Optional<SerializedEvent> readSerializedEvent(long minSequenceNumber, long maxSequenceNumber,
                                                          SegmentIndexEntries lastEventPosition) {
        Optional<EventSource> eventSource = getEventSource(lastEventPosition.segment());
        if (eventSource.isPresent()) {
            try {
                List<Integer> positions = lastEventPosition.indexEntries().positions();
                for (int i = positions.size() - 1; i >= 0; i--) {
                    SerializedEvent event = eventSource.get().readEvent(positions.get(i));
                    if (event.getAggregateSequenceNumber() >= minSequenceNumber
                            && event.getAggregateSequenceNumber() < maxSequenceNumber) {
                        return Optional.of(event);
                    }
                }

                return Optional.empty();
            } finally {
                eventSource.get().close();
            }
        }

        if (next != null) {
            return next.readSerializedEvent(minSequenceNumber, maxSequenceNumber, lastEventPosition);
        }

        return Optional.empty();
    }

    private Optional<SerializedEvent> readSerializedEvent(EventSource eventSource, long minSequenceNumber,
                                                          int lastEventPosition) {
        SerializedEvent event = eventSource.readEvent(lastEventPosition);
        if (event.getAggregateSequenceNumber() >= minSequenceNumber) {
            return Optional.of(event);
        }
        return Optional.empty();
    }

    @Override
    public void init(boolean validate, long defaultFirstToken) {
        initSegments(Long.MAX_VALUE, defaultFirstToken);
        validate(validate ? storagePropertiesSupplier.get().getValidationSegments() : 2);
    }

    @Override
    public long getFirstCompletedSegment() {
        if (getSegments().isEmpty()) {
            return -1;
        }
        return getSegments().first();
    }

    @Override
    public long getFirstToken() {
        if (next != null && !next.getSegments().isEmpty()) {
            return next.getFirstToken();
        }
        if (getSegments().isEmpty()) {
            return -1;
        }
        return getSegments().last();
    }

    @Override
    public long getTokenAt(long instant) {
        for (long segment : getSegments()) {
            Optional<EventSource> eventSource = getEventSource(segment);
            Long found = eventSource.map(es -> {
                try (EventIterator iterator = createEventIterator(es, segment, segment)) {
                    return iterator.getTokenAt(instant);
                }
            }).orElse(null);

            if (found != null) {
                return found;
            }
        }

        if (next != null) {
            return next.getTokenAt(instant);
        }
        return getSegments().isEmpty() ? -1 : getFirstToken();
    }

    @Override
    public CloseableIterator<SerializedEventWithToken> getGlobalIterator(long start) {
        throw new UnsupportedOperationException("Operation only supported on primary event store");
    }

    public void validate(int maxSegments) {
        Stream<Long> segments = getAllSegments();
        List<ValidationResult> resultList = segments.limit(maxSegments).parallel().map(this::validateSegment).collect(
                Collectors.toList());
        resultList.stream().filter(validationResult -> !validationResult.isValid()).findFirst().ifPresent(
                validationResult -> {
                    throw new MessagingPlatformException(ErrorCode.VALIDATION_FAILED, validationResult.getMessage());
                });
        resultList.sort(Comparator.comparingLong(ValidationResult::getSegment));
        for (int i = 0; i < resultList.size() - 1; i++) {
            ValidationResult thisResult = resultList.get(i);
            ValidationResult nextResult = resultList.get(i + 1);
            if (thisResult.getLastToken() != nextResult.getSegment()) {
                throw new MessagingPlatformException(ErrorCode.VALIDATION_FAILED,
                                                     format(
                                                             "Validation exception: segment %d ending at token, %d, next segment starts at token %d",
                                                             thisResult.getSegment(),
                                                             thisResult.getLastToken() - 1,
                                                             nextResult.getSegment()));
            }
        }
    }

    private Stream<Long> getAllSegments() {
        if (next == null) {
            return getSegments().stream();
        }
        return Stream.concat(getSegments().stream(), next.getSegments().stream()).distinct();
    }

    protected ValidationResult validateSegment(long segment) {
        logger.debug("{}: Validating {} segment: {}", type.getContext(), type.getEventType(), segment);
        try (TransactionIterator iterator = getTransactions(segment, segment, true)) {
            SerializedTransactionWithToken last = null;
            while (iterator.hasNext()) {
                last = iterator.next();
            }
            return new ValidationResult(segment, last == null ? segment : last.getToken() + last.getEvents().size());
        } catch (Exception ex) {
            return new ValidationResult(segment, ex.getMessage());
        }
    }

    @Override
    public EventTypeContext getType() {
        return type;
    }

    @Override
    public Registration registerCloseListener(Runnable listener) {
        closeListeners.add(listener);
        return () -> closeListeners.remove(listener);
    }

    public void initSegments(long maxValue, long defaultFirstToken) {
        initSegments(maxValue);
    }

    public void initSegments(long maxValue) {
    }

    public EventIterator getEvents(long segment, long token) {
        Optional<EventSource> reader = getEventSource(segment);
        return reader.map(eventSource -> createEventIterator(eventSource, segment, token))
                     .orElseGet(() -> {
                         if (next == null) {
                             throw new MessagingPlatformException(ErrorCode.OTHER,
                                                                  format("%s: token %d before start of event store",
                                                                         context,
                                                                         token));
                         }
                         return next.getEvents(segment, token);
                     });
    }

    public TransactionIterator getTransactions(long segment, long token) {
        return getTransactions(segment, token, false);
    }

    protected TransactionIterator getTransactions(long segment, long token, boolean validating) {
        Optional<EventSource> reader = getEventSource(segment);
        return reader.map(r -> createTransactionIterator(r, segment, token, validating))
                     .orElseGet(() -> getTransactionsFromNext(segment, token, validating));
    }

    private TransactionIterator getTransactionsFromNext(long segment, long token, boolean validating) {
        if (next == null) {
            throw new MessagingPlatformException(ErrorCode.OTHER,
                                                 format(
                                                         "%s: unable to read transactions for segment %d, requested token %d",
                                                         context,
                                                         segment,
                                                         token));
        }
        return next.getTransactions(segment, token, validating);
    }

    protected TransactionIterator createTransactionIterator(EventSource eventSource, long segment, long token,
                                                            boolean validating) {
        return eventSource.createTransactionIterator(segment, token, validating);
    }

    public long getSegmentFor(long token) {
        return getSegments().stream()
                            .filter(segment -> segment <= token)
                            .findFirst()
                            .orElse(next == null ? -1 : next.getSegmentFor(token));
    }

    @Override
    public CloseableIterator<SerializedTransactionWithToken> transactionIterator(long firstToken, long limitToken) {
        return new TransactionWithTokenIterator(firstToken, limitToken);
    }

    protected SortedSet<Long> prepareSegmentStore(long lastInitialized) {
        SortedSet<Long> segments = new ConcurrentSkipListSet<>(Comparator.reverseOrder());
        StorageProperties storageProperties = storagePropertiesSupplier.get();
        File events = new File(storageProperties.getStorage(context));
        FileUtils.checkCreateDirectory(events);
        String[] eventFiles = FileUtils.getFilesWithSuffix(events, storageProperties.getEventsSuffix());
        Arrays.stream(eventFiles)
              .map(name -> Long.valueOf(name.substring(0, name.indexOf('.'))))
              .filter(segment -> segment < lastInitialized)
              .forEach(segments::add);

        segments.forEach(this::renameFileIfNecessary);
        long firstValidIndex = segments.stream().filter(indexManager::validIndex).findFirst().orElse(-1L);
        logger.debug("First valid index: {}", firstValidIndex);
        SortedSet<Long> recreate = new TreeSet<>();
        recreate.addAll(segments.headSet(firstValidIndex));
        recreate.forEach(this::recreateIndex);
        return segments;
    }

    protected abstract void recreateIndex(long segment);

    private int retrieveEventsForAnAggregate(long segment, List<Integer> indexEntries, long minSequenceNumber,
                                             long maxSequenceNumber,
                                             Consumer<SerializedEvent> onEvent, long maxResults, long minToken) {
        Optional<EventSource> buffer = getEventSource(segment);
        int processed = 0;

        if (buffer.isPresent()) {
            EventSource eventSource = buffer.get();
            for (int i = 0; i < indexEntries.size() && i < maxResults; i++) {
                SerializedEvent event = eventSource.readEvent(indexEntries.get(i));
                if (event.getAggregateSequenceNumber() >= minSequenceNumber
                        && event.getAggregateSequenceNumber() < maxSequenceNumber) {
                    onEvent.accept(event);
                }
                processed++;
            }
            eventSource.close();
        } else {
            if (next != null) {
                processed = next.retrieveEventsForAnAggregate(segment,
                                                              indexEntries,
                                                              minSequenceNumber,
                                                              maxSequenceNumber,
                                                              onEvent,
                                                              maxResults,
                                                              minToken);
            }
        }

        return processed;
    }

    @Override
    public Stream<String> getBackupFilenames(long lastSegmentBackedUp, boolean includeActive) {
        StorageProperties storageProperties = storagePropertiesSupplier.get();
        Stream<String> filenames = Stream.concat(getSegments().stream()
                                                              .filter(s -> s > lastSegmentBackedUp)
                                                              .map(s -> name(storageProperties.dataFile(context, s))),
                                                 indexManager.getBackupFilenames(lastSegmentBackedUp));

        if (next == null) {
            return filenames;
        }
        return Stream.concat(filenames, next.getBackupFilenames(lastSegmentBackedUp, includeActive));
    }

    protected void renameFileIfNecessary(long segment) {
        StorageProperties storageProperties = storagePropertiesSupplier.get();
        File dataFile = storageProperties.oldDataFile(context, segment);
        if (dataFile.exists()) {
            if (!dataFile.renameTo(storageProperties.dataFile(context, segment))) {
                throw new MessagingPlatformException(ErrorCode.DATAFILE_READ_ERROR,
                                                     renameMessage(dataFile,
                                                                   storageProperties.dataFile(context, segment)));
            }
            File indexFile = storageProperties.oldIndex(context, segment);
            if (indexFile.exists() && !indexFile.renameTo(storageProperties.index(context, segment))) {
                throw new MessagingPlatformException(ErrorCode.DATAFILE_READ_ERROR,
                                                     renameMessage(indexFile,
                                                                   storageProperties.index(context, segment)));
            }
            File bloomFile = storageProperties.oldBloomFilter(context, segment);
            if (bloomFile.exists() && !bloomFile.renameTo(storageProperties.bloomFilter(context, segment))) {
                throw new MessagingPlatformException(ErrorCode.DATAFILE_READ_ERROR,
                                                     renameMessage(bloomFile,
                                                                   storageProperties.bloomFilter(context, segment)));
            }
        }
    }

    private String renameMessage(File from, File to) {
        return "Could not rename " + from.getAbsolutePath() + " to " + to.getAbsolutePath();
    }

    protected void recreateIndexFromIterator(long segment, EventIterator iterator) {
        Map<String, List<IndexEntry>> loadedEntries = new HashMap<>();
        while (iterator.hasNext()) {
            EventInformation event = iterator.next();
            if (event.isDomainEvent()) {
                IndexEntry indexEntry = new IndexEntry(
                        event.getEvent().getAggregateSequenceNumber(),
                        event.getPosition(),
                        event.getToken());
                loadedEntries.computeIfAbsent(event.getEvent().getAggregateIdentifier(), id -> new LinkedList<>())
                             .add(indexEntry);
            }
        }
        indexManager.addToActiveSegment(segment, loadedEntries);
        indexManager.complete(segment);
    }

    /**
     * @param segment gets an EventSource for the segment
     * @return the event source or Optional.empty() if segment not managed by this handler
     */
    public abstract Optional<EventSource> getEventSource(long segment);

    //Retrieves event source from first available layer that is responsible for given segment
    private Optional<EventSource> eventSource(long segment) {
        Optional<EventSource> eventSource = getEventSource(segment);
        if (eventSource.isPresent()) {
            return eventSource;
        }
        if (next == null) {
            return Optional.empty();
        }
        return next.eventSource(segment);
    }

    /**
     * Get all segments
     *
     * @return descending set of segment ids
     */
    protected abstract SortedSet<Long> getSegments();

    @Override
    public byte transactionVersion() {
        return TRANSACTION_VERSION;
    }

    @Override
    public long nextToken() {
        return 0;
    }

    @Override
    public void validateTransaction(long token, List<Event> eventList) {
        try (CloseableIterator<SerializedTransactionWithToken> transactionIterator =
                     transactionIterator(token,token + eventList.size())) {
            if (transactionIterator.hasNext()) {
                SerializedTransactionWithToken transaction = transactionIterator.next();
                if (!equals(transaction.getEvents(),eventList)) {
                    throw new EventStoreValidationException(format(
                            "%s: Replicated %s transaction %d does not match stored transaction",
                            context,
                            type.getEventType(),
                            token));
                }
            } else {
                throw new EventStoreValidationException(format("%s: Replicated %s transaction %d not found",
                                                               context,
                                                               type.getEventType(),
                                                               token));
            }
        }
    }

    private boolean equals(List<SerializedEvent> storedEvents, List<Event> eventList) {
        for (int i = 0; i < storedEvents.size(); i++) {
            if (!storedEvents.get(i).asEvent().equals(eventList.get(i))) {
                return false;
            }
        }
        return true;
    }

    private class TransactionWithTokenIterator implements CloseableIterator<SerializedTransactionWithToken> {

        private final Long limitToken;
        private long currentToken;
        private long currentSegment;
        private TransactionIterator currentTransactionIterator;

        TransactionWithTokenIterator(long token, Long limitToken) {
            this.currentToken = token;
            this.limitToken = limitToken;
            this.currentSegment = getSegmentFor(token);
            this.currentTransactionIterator = getTransactions(currentSegment, currentToken);
        }

        @Override
        public boolean hasNext() {
            return currentTransactionIterator.hasNext();
        }

        @Override
        public SerializedTransactionWithToken next() {
            SerializedTransactionWithToken nextTransaction = currentTransactionIterator.next();
            currentToken += nextTransaction.getEventsCount();
            checkPointers();
            return nextTransaction;
        }

        private void checkPointers() {
            if (limitToken != null && currentToken >= limitToken) {
                // we are done
                currentTransactionIterator.close();
            } else if (!currentTransactionIterator.hasNext()) {
                currentSegment = getSegmentFor(currentToken);
                currentTransactionIterator.close();
                currentTransactionIterator = getTransactions(currentSegment, currentToken);
            }
        }

        @Override
        public void close() {
            if (currentTransactionIterator != null) {
                currentTransactionIterator.close();
            }
        }
    }
}
