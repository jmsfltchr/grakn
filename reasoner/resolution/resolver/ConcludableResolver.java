/*
 * Copyright (C) 2021 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package grakn.core.reasoner.resolution.resolver;

import grakn.core.common.exception.GraknException;
import grakn.core.common.iterator.FunctionalIterator;
import grakn.core.common.iterator.Iterators;
import grakn.core.concept.ConceptManager;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.concurrent.actor.Actor;
import grakn.core.logic.LogicManager;
import grakn.core.logic.Rule;
import grakn.core.logic.resolvable.Unifier;
import grakn.core.pattern.Conjunction;
import grakn.core.reasoner.resolution.ResolverRegistry;
import grakn.core.reasoner.resolution.answer.AnswerState.Partial;
import grakn.core.reasoner.resolution.framework.AnswerCache;
import grakn.core.reasoner.resolution.framework.Request;
import grakn.core.reasoner.resolution.framework.Resolver;
import grakn.core.reasoner.resolution.framework.Response;
import grakn.core.reasoner.resolution.framework.Response.Answer;
import grakn.core.traversal.TraversalEngine;
import grakn.core.traversal.common.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static grakn.common.util.Objects.className;
import static grakn.core.common.exception.ErrorMessage.Internal.ILLEGAL_CAST;
import static grakn.core.common.iterator.Iterators.iterate;

public class ConcludableResolver extends Resolver<ConcludableResolver> {

    private static final Logger LOG = LoggerFactory.getLogger(ConcludableResolver.class);

    private final LinkedHashMap<Driver<ConclusionResolver>, Set<Unifier>> applicableRules;
    private final Map<Driver<ConclusionResolver>, Rule> resolverRules;
    private final grakn.core.logic.resolvable.Concludable concludable;
    private final LogicManager logicMgr;
    private final Map<Driver<? extends Resolver<?>>, RecursionState> recursionStates;
    private final Map<Request, ConceptMapAnswerManager> answerManagers;
    private final Set<Identifier.Variable.Retrievable> unboundVars;
    private boolean isInitialised;
    protected final Map<Actor.Driver<? extends Resolver<?>>, CacheRegister<AnswerCache<?, ConceptMap>>> cacheRegisters;

    public ConcludableResolver(Driver<ConcludableResolver> driver, grakn.core.logic.resolvable.Concludable concludable,
                               ResolverRegistry registry, TraversalEngine traversalEngine, ConceptManager conceptMgr,
                               LogicManager logicMgr, boolean resolutionTracing) {
        super(driver, ConcludableResolver.class.getSimpleName() + "(pattern: " + concludable.pattern() + ")",
              registry, traversalEngine, conceptMgr, resolutionTracing);
        this.logicMgr = logicMgr;
        this.concludable = concludable;
        this.applicableRules = new LinkedHashMap<>();
        this.resolverRules = new HashMap<>();
        this.recursionStates = new HashMap<>();
        this.answerManagers = new HashMap<>();
        this.unboundVars = unboundVars(concludable.pattern());
        this.isInitialised = false;
        this.cacheRegisters = new HashMap<>();
    }

    @Override
    public void receiveRequest(Request fromUpstream, int iteration) {
        LOG.trace("{}: received Request: {}", name(), fromUpstream);
        if (!isInitialised) initialiseDownstreamResolvers();
        if (isTerminated()) return;

////        ConceptMapAnswerManager requestState = getOrReplaceRequestState(fromUpstream, iteration);
//        RequestStateMachine requestStateMachine = getOrReplaceRequestState(fromUpstream, iteration);
//
//        requestStateMachine.receivedIteration(iteration);
//        requestStateMachine.proceed();

        ConceptMapAnswerManager requestState = getOrReplaceRequestState(fromUpstream, iteration);
        if (iteration < requestState.iteration()) {
            // short circuit if the request came from a prior iteration
            failToUpstream(fromUpstream, iteration);
        } else {
            assert iteration == requestState.iteration();
            nextAnswer(fromUpstream, requestState, iteration);
        }
    }

    @Override
    protected void receiveAnswer(Answer fromDownstream, int iteration) {
        LOG.trace("{}: received Answer: {}", name(), fromDownstream);
        if (isTerminated()) return;

        Request toDownstream = fromDownstream.sourceRequest();
        Request fromUpstream = fromUpstream(toDownstream);
        ConceptMapAnswerManager answerManager = this.answerManagers.get(fromUpstream);

        // assert cacheRegisters.get(fromUpstream.partialAnswer().root()).isRegistered(fromUpstream.partialAnswer().conceptMap()); // TODO: This throws in "conjunctions of untyped reasoned relations are correctly resolved" but without consequence
        assert answerManager.isExploration();
        answerManager.asExploration().newAnswer(fromDownstream.answer(), fromDownstream.answer().requiresReiteration());

        if (fromDownstream.answer().asConcludable().isExplain()) {
            // TODO: We skip the cache here, which we don't elsewhere
            answerFound(fromDownstream.answer().asConcludable().toUpstreamInferred(), fromUpstream, iteration);
        } else if (iteration == answerManager.iteration()) {
            nextAnswer(fromUpstream, answerManager, iteration);
        } else {
            failToUpstream(fromUpstream, iteration);
        }
    }

    /*
    When we only require 1 answer (eg. when the conjunction is already fully bound), we can short circuit
    and prevent exploration of further rules.

    One latency optimisation we could do here, is keep track of how many N repeated requests are received,
    forward them downstream (to parallelise searching for the single answer), and when the first one finds an answer,
    we respond for all N ahead of time. Then, when the rules actually return an answer to this concludable, we do nothing.
     */
    private void answerFound(Partial.Compound<?, ?> upstreamAnswer, Request fromUpstream, int iteration) {
        ConceptMapAnswerManager answerManager = this.answerManagers.get(fromUpstream);
        if (answerManager.isExploration() && answerManager.singleAnswerRequired() && !upstreamAnswer.isExplain()) {
            answerManager.asExploration().downstreamManager().clearDownstreams();
            // TODO: Should we set the cache complete here (and is that correct?), or is that already achieved implicitly?
        }
        answerToUpstream(upstreamAnswer, fromUpstream, iteration);
    }

    @Override
    protected void receiveFail(Response.Fail fromDownstream, int iteration) {
        LOG.trace("{}: received Fail: {}", name(), fromDownstream);
        if (isTerminated()) return;

        Request toDownstream = fromDownstream.sourceRequest();
        Request fromUpstream = fromUpstream(toDownstream);
        ConceptMapAnswerManager answerManager = this.answerManagers.get(fromUpstream);

        if (iteration < answerManager.iteration()) {
            // short circuit old iteration failed messages to upstream
            failToUpstream(fromUpstream, iteration);
            return;
        }
        if (answerManager.isExploration()) answerManager.asExploration().downstreamManager().removeDownstream(fromDownstream.sourceRequest());
        nextAnswer(fromUpstream, answerManager, iteration);
    }

    @Override
    public void terminate(Throwable cause) {
        super.terminate(cause);
        answerManagers.clear();
        recursionStates.clear();
    }

    @Override
    protected void initialiseDownstreamResolvers() {
        LOG.debug("{}: initialising downstream resolvers", name());
        concludable.getApplicableRules(conceptMgr, logicMgr).forEachRemaining(rule -> concludable.getUnifiers(rule)
                .forEachRemaining(unifier -> {
                    if (isTerminated()) return;
                    try {
                        Driver<ConclusionResolver> conclusionResolver = registry.registerConclusion(rule.conclusion());
                        applicableRules.putIfAbsent(conclusionResolver, new HashSet<>());
                        applicableRules.get(conclusionResolver).add(unifier);
                        resolverRules.put(conclusionResolver, rule);
                    } catch (GraknException e) {
                        terminate(e);
                    }
                }));
        if (!isTerminated()) isInitialised = true;
    }

    private void nextAnswer(Request fromUpstream, ConceptMapAnswerManager answerManager, int iteration) {
        if (fromUpstream.partialAnswer().asConcludable().isExplain()) {
            Optional<Partial.Compound<?, ?>> upstreamAnswer = answerManager.nextAnswer().map(Partial::asCompound); // TODO: This returns a partial, but it won't contain the correct explanation yet
            if (upstreamAnswer.isPresent()) {
                answerFound(upstreamAnswer.get().asConcludable().toUpstreamInferred(), fromUpstream, iteration); // TODO: Is this small difference from the implementation below correct or necessary?
            } else {
                RuleExplorationAnswerManager exploration;
                if ((exploration = answerManager.asExploration()).downstreamManager().hasDownstream()) {
                    requestFromDownstream(exploration.downstreamManager().nextDownstream(), fromUpstream, iteration);
                } else {
                    failToUpstream(fromUpstream, iteration);
                }
            }
        } else {
            Optional<Partial.Compound<?, ?>> upstreamAnswer = answerManager.nextAnswer().map(Partial::asCompound);
            if (upstreamAnswer.isPresent()) {
                answerFound(upstreamAnswer.get(), fromUpstream, iteration);
            } else {
                RuleExplorationAnswerManager exploration;
                if (answerManager.isExploration() && !answerManager.answerCache().isComplete()) {
                    if ((exploration = answerManager.asExploration()).downstreamManager().hasDownstream()) {
                        requestFromDownstream(exploration.downstreamManager().nextDownstream(), fromUpstream, iteration);
                    } else {
                        answerManager.answerCache().setComplete(); // TODO: The cache should not be set as complete during recursion
                        failToUpstream(fromUpstream, iteration);
                    }
                } else {
                    failToUpstream(fromUpstream, iteration);
                }
            }
        }
    }

    private ConceptMapAnswerManager getOrReplaceRequestState(Request fromUpstream, int iteration) {
        if (!answerManagers.containsKey(fromUpstream)) {
            ConceptMapAnswerManager answerManager = createRequestState(fromUpstream, iteration);
            answerManagers.put(fromUpstream, answerManager);
        } else {
            ConceptMapAnswerManager answerManager = this.answerManagers.get(fromUpstream);

            if (answerManager.iteration() < iteration) {
                // when the same request for the next iteration the first time, re-initialise required state
                ConceptMapAnswerManager newAnswerManager = createRequestState(fromUpstream, iteration);
                this.answerManagers.put(fromUpstream, newAnswerManager);
            }
        }
        return answerManagers.get(fromUpstream);
    }

    protected ConceptMapAnswerManager createRequestState(Request fromUpstream, int iteration) {

//        // TODO: Move this to getOrReplaceRequestState, just here to try the UX for now
//        Consumer<AnswerState> onSendUpstream = (answer) -> answerToUpstream(answer, fromUpstream, iteration);
//        Supplier<Void> onFail = () -> { failToUpstream(fromUpstream, iteration); return null; };
//        Consumer<Request> onSearchDownstream = (nextDownstream) -> requestFromDownstream(nextDownstream, fromUpstream, iteration);
//
//        Exploration requestStateMachine = new ExplorationRequestStateMachineImpl(fromUpstream, iteration, answerCache, new DownstreamManager(),
//                                                                                 onSendUpstream, onFail, onSearchDownstream);

        // ===============================================

        LOG.debug("{}: Creating new Responses for iteration{}, request: {}", name(), iteration, fromUpstream);
        Driver<? extends Resolver<?>> root = fromUpstream.partialAnswer().root();
        recursionStates.putIfAbsent(root, new RecursionState(iteration));

        RecursionState recursionState = getOrCreateRecursionState(root, iteration);
        CacheRegister<AnswerCache<?, ConceptMap>> cacheRegister = getOrCreateCacheRegister(root, iteration);

        ConceptMap answerFromUpstream = fromUpstream.partialAnswer().conceptMap();
        boolean singleAnswerRequired;
        boolean deduplicate;
        boolean useSubsumption;
        if (fromUpstream.partialAnswer().asConcludable().isExplain()) {
            singleAnswerRequired = false;
            deduplicate = false;
            useSubsumption = false;

//            assert fromUpstream.partialAnswer().isConcludable();
//            AnswerCache<ConceptMap> answerCache = new ConceptMapCache(cacheRegister, answerFromUpstream, useSubsumption);
//            if (!answerCache.isComplete()) {
//                FunctionalIterator<ConceptMap> traversal = traversalIterator(concludable.pattern(), answerFromUpstream);
//                answerCache.cache(traversal);
//            }
//            recursionState.recordReceived(fromUpstream);
//            ConceptMapAnswerManager answerManager = new RuleExplorationAnswerManager(fromUpstream, answerCache, iteration, singleAnswerRequired, deduplicate);
//            registerRules(fromUpstream, answerManager.asExploration());
//            return answerManager;

        } else {
            singleAnswerRequired = answerFromUpstream.concepts().keySet().containsAll(unboundVars());
            deduplicate = true;
            useSubsumption = true;
        }

        assert cacheRegister.isRegistered(answerFromUpstream) == recursionState.hasReceived(answerFromUpstream); // TODO: Should be the same, and therefore can remove this part of recursionState
        if (cacheRegister.isRegistered(answerFromUpstream)) {
            AnswerCache<?, ConceptMap> answerCache = cacheRegister.get(answerFromUpstream); // TODO: It's possible the cache we get here uses subsumption, but that we shouldn't if we're explaining
            // TODO: can we always cast to a ConceptMapCache?
            return new RetrievalAnswerManager(fromUpstream, answerCache.asConceptMapCache(), iteration, singleAnswerRequired, deduplicate);
        } else {
            assert fromUpstream.partialAnswer().isConcludable();
            AnswerCache.ConceptMapCache answerCache = new AnswerCache.ConceptMapCache(cacheRegister, answerFromUpstream, useSubsumption);
            if (!answerCache.isComplete()) {
                FunctionalIterator<ConceptMap> traversal = traversalIterator(concludable.pattern(), answerFromUpstream);
                answerCache.cache(traversal);
            }
            recursionState.recordReceived(fromUpstream.partialAnswer().conceptMap());
            ConceptMapAnswerManager answerManager = new RuleExplorationAnswerManager(fromUpstream, answerCache, iteration, singleAnswerRequired, deduplicate);
            registerRules(fromUpstream, answerManager.asExploration());
            return answerManager;
        }
    }

    private CacheRegister<AnswerCache<?, ConceptMap>> getOrCreateCacheRegister(Driver<? extends Resolver<?>> root, int iteration) {
        cacheRegisters.putIfAbsent(root, new CacheRegister<>(iteration));
        CacheRegister<AnswerCache<?, ConceptMap>> cacheRegister = cacheRegisters.get(root);
        if (cacheRegister.iteration() < iteration) {
            cacheRegister.nextIteration(iteration);
        }
        return cacheRegister;
    }

    private RecursionState getOrCreateRecursionState(Driver<? extends Resolver<?>> root, int iteration) {
        recursionStates.putIfAbsent(root, new RecursionState(iteration));
        RecursionState recursionState = recursionStates.get(root);
        if (recursionState.iteration() < iteration) {
            recursionState.nextIteration(iteration);
        }
        return recursionState;
    }

    private void registerRules(Request fromUpstream, RuleExplorationAnswerManager answerManager) {
        // loop termination: when receiving a new request, we check if we have seen it before from this root query
        // if we have, we do not allow rules to be registered as possible downstreams
        Partial.Concludable<?> partialAnswer = fromUpstream.partialAnswer().asConcludable();
        for (Map.Entry<Driver<ConclusionResolver>, Set<Unifier>> entry : applicableRules.entrySet()) {
            Driver<ConclusionResolver> conclusionResolver = entry.getKey();
            for (Unifier unifier : entry.getValue()) {
                Optional<? extends Partial.Conclusion<?, ?>> unified = partialAnswer.toDownstream(unifier, resolverRules.get(conclusionResolver));
                if (unified.isPresent()) {
                    Request toDownstream = Request.create(driver(), conclusionResolver, unified.get());
                    answerManager.downstreamManager().addDownstream(toDownstream);
                }
            }
        }
    }

    private Set<Identifier.Variable.Retrievable> unboundVars() {
        return unboundVars;
    }

    private Set<Identifier.Variable.Retrievable> unboundVars(Conjunction conjunction) {
        Set<Identifier.Variable.Retrievable> missingBounds = new HashSet<>();
        iterate(conjunction.variables()).filter(var -> var.id().isRetrievable()).forEachRemaining(var -> {
            if (var.isType() && !var.asType().label().isPresent()) missingBounds.add(var.asType().id().asRetrievable());
            else if (var.isThing() && !var.asThing().iid().isPresent())
                missingBounds.add(var.asThing().id().asRetrievable());
        });
        return missingBounds;
    }

    private class ExplainingRuleExplorationAnswerManager extends CachingAnswerManager<Partial.Concludable<?>, ConceptMap> {

        private final DownstreamManager downstreamManager;

        public ExplainingRuleExplorationAnswerManager(Request fromUpstream, AnswerCache<Partial.Concludable<?>, ConceptMap> answerCache,
                                                      int iteration, boolean mayCauseReiteration) {
            super(fromUpstream, answerCache, iteration, mayCauseReiteration);
            this.downstreamManager = new DownstreamManager();
        }

        public DownstreamManager downstreamManager() { // TODO: Don't use this, move to use it from the new AnswerStateMachine
            return downstreamManager;
        }

        @Override
        protected FunctionalIterator<? extends Partial<?>> toUpstream(Partial.Concludable<?> partial) {
            Partial<?> upstreamAnswer = partial.asExplain().toUpstreamInferred();
            if (answerCache.requiresReiteration()) upstreamAnswer.setRequiresReiteration(); // TODO: Make it a responsibility of the cache to mark all answers it yields as requiresReiteration if the cache is marked as RequiresReiteration
            return Iterators.single(upstreamAnswer);
        }

        @Override
        protected boolean optionallyDeduplicate(ConceptMap conceptMap) {
            return false;
        }

        public void newAnswer(Partial.Concludable<?> partial, boolean requiresReiteration) {
            answerCache.cache(partial);
            if (requiresReiteration) answerCache.setRequiresReiteration(); // TODO: Make it a responsibility of the cache to mark all answers it yields as requiresReiteration if the cache is marked as RequiresReiteration
        }
    }

    private abstract class ConceptMapAnswerManager extends CachingAnswerManager<ConceptMap, ConceptMap> {

        private final ProducedRecorder producedRecorder;
        private final boolean singleAnswerRequired;
        private final boolean deduplicate;

        public ConceptMapAnswerManager(Request fromUpstream, AnswerCache<ConceptMap, ConceptMap> answerCache, int iteration,
                                       boolean singleAnswerRequired, boolean deduplicate, boolean mayCauseReiteration) {
            super(fromUpstream, answerCache, iteration, mayCauseReiteration);
            this.singleAnswerRequired = singleAnswerRequired;
            this.deduplicate = deduplicate;
            this.producedRecorder = new ProducedRecorder();
        }

        @Override
        protected boolean optionallyDeduplicate(ConceptMap conceptMap) {
            if (deduplicate) return producedRecorder.record(conceptMap);
            return false;
        }

        @Override
        protected FunctionalIterator<? extends Partial<?>> toUpstream(ConceptMap conceptMap) {
            Partial.Concludable<?> partial = fromUpstream.partialAnswer().asConcludable();
            assert !partial.isExplain();
            assert partial.isMatch();
            Partial.Compound<?, ?> upstreamAnswer = partial.asMatch().toUpstreamLookup(conceptMap, concludable.isInferredAnswer(conceptMap));
            if (answerCache.requiresReiteration()) upstreamAnswer.setRequiresReiteration(); // TODO: Make it a responsibility of the cache to mark all answers it yields as requiresReiteration if the cache is marked as RequiresReiteration
            return Iterators.single(upstreamAnswer);
        }

        public boolean isExploration() {
            return false;
        }

        public RuleExplorationAnswerManager asExploration() {
            throw GraknException.of(ILLEGAL_CAST, className(this.getClass()), className(RuleExplorationAnswerManager.class));
        }

        public boolean singleAnswerRequired() {
            return singleAnswerRequired;
        }
    }

    private class RuleExplorationAnswerManager extends ConceptMapAnswerManager {

        private final DownstreamManager downstreamManager;

        public RuleExplorationAnswerManager(Request fromUpstream, AnswerCache<ConceptMap, ConceptMap> answerCache,
                                            int iteration, boolean singleAnswerRequired, boolean deduplicate) {
            super(fromUpstream, answerCache, iteration, singleAnswerRequired, deduplicate, false);
            this.downstreamManager = new DownstreamManager();
        }

        @Override
        public boolean isExploration() {
            return true;
        }

        @Override
        public RuleExplorationAnswerManager asExploration() {
            return this;
        }

        public DownstreamManager downstreamManager() { // TODO: Don't use this, move to use it from the new AnswerStateMachine
            return downstreamManager;
        }

        public void newAnswer(Partial<?> partial, boolean requiresReiteration) {
            answerCache.cache(partial.conceptMap());
            if (requiresReiteration) answerCache.setRequiresReiteration();
        }
    }

    private class RetrievalAnswerManager extends ConceptMapAnswerManager {

        public RetrievalAnswerManager(Request fromUpstream, AnswerCache<ConceptMap, ConceptMap> answerCache,
                                      int iteration, boolean singleAnswerRequired, boolean deduplicate) {
            super(fromUpstream, answerCache, iteration, singleAnswerRequired, deduplicate, true);
        }

    }

    /**
     * Maintain iteration state per root query
     * This allows us to share resolvers across different queries
     * while maintaining the ability to do loop termination within a single query
     */
    private static class RecursionState {
        private Set<ConceptMap> receivedMaps;
        private int iteration;

        RecursionState(int iteration) {
            this.iteration = iteration;
            this.receivedMaps = new HashSet<>();
        }

        public int iteration() {
            return iteration;
        }

        public void nextIteration(int newIteration) {
            assert newIteration > iteration;
            iteration = newIteration;
            receivedMaps = new HashSet<>();
        }

        public void recordReceived(ConceptMap conceptMap) {
            receivedMaps.add(conceptMap);
        }

        public boolean hasReceived(ConceptMap conceptMap) {
            return receivedMaps.contains(conceptMap);
        }
    }
}
