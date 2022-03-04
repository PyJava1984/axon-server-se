/*
 *  Copyright (c) 2017-2022 AxonIQ B.V. and/or licensed to AxonIQ B.V.
 *  under one or more contributor license agreements.
 *
 *  Licensed under the AxonIQ Open Source License Agreement v1.0;
 *  you may not use this file except in compliance with the license.
 *
 */

package io.axoniq.axonserver.rest;

import io.axoniq.axonserver.component.processor.balancing.TrackingEventProcessor;
import io.axoniq.axonserver.component.processor.balancing.strategy.LoadBalanceStrategyRepository;
import io.axoniq.axonserver.logging.AuditLog;
import io.axoniq.axonserver.serializer.Printable;
import org.slf4j.Logger;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import springfox.documentation.annotations.ApiIgnore;

import java.security.Principal;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Rest APIs related to the load balancing of tracking event processors over client applications.
 *
 * @author Sara Pellegrini
 * @since 4.1
 */
@RestController
@Transactional
@RequestMapping("v1")
public class LoadBalancingRestController {

    private static final Logger auditLog = AuditLog.getLogger();

    private final LoadBalanceStrategyRepository strategyController;

    public LoadBalancingRestController(LoadBalanceStrategyRepository strategyController) {
        this.strategyController = strategyController;
    }

    @GetMapping("processors/loadbalance/strategies")
    public Iterable<? extends Printable> getStrategies(@ApiIgnore final Principal principal) {
        auditLog.debug("[{}] Request to list load-balancing strategies.", AuditLog.username(principal));

        return strategyController.findAll();
    }

    @Deprecated
    @GetMapping("processors/loadbalance/strategies/factories")
    public Set<String> getLoadBalancingStrategyFactoryBean(@ApiIgnore final Principal principal) {
        auditLog.debug("[{}] Request to list load-balancing strategy factories.", AuditLog.username(principal));
        Set<String> names = new HashSet<>();
        strategyController.findAll().forEach(i -> names.add(i.getClass().getSimpleName()));
        return names;
    }

    /**
     * Balance the load for the specified event processor among the connected client.
     *
     * @param processor            the event processor name
     * @param context              the principal context of the event processor
     * @param tokenStoreIdentifier the token store identifier of the event processor
     * @param strategyName         the strategy to be used to balance the load
     */
    @PatchMapping("processors/{processor}/loadbalance")
    public void balanceProcessorLoad(@PathVariable("processor") String processor,
                                     @RequestParam("context") String context,
                                     @RequestParam("tokenStoreIdentifier") String tokenStoreIdentifier,
                                     @RequestParam("strategy") String strategyName,
                                     @ApiIgnore final Principal principal) {
        auditLog.debug("[{}@{}] Request to set load-balancing strategy for processor \"{}\" to \"{}\".",
                       AuditLog.username(principal), context, processor, strategyName);

        TrackingEventProcessor trackingProcessor = new TrackingEventProcessor(processor, context, tokenStoreIdentifier);
        String instructionId = UUID.randomUUID().toString();
        strategyController.findByName(strategyName).balance(trackingProcessor, strategyName).perform(instructionId);
    }
}
