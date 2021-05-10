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
import grakn.core.common.iterator.Iterators;
import grakn.core.concept.Concept;
import grakn.core.concept.ConceptManager;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.logic.LogicManager;
import grakn.core.logic.resolvable.Concludable;
import grakn.core.logic.resolvable.Negated;
import grakn.core.logic.resolvable.Resolvable;
import grakn.core.logic.resolvable.Retrievable;
import grakn.core.pattern.Conjunction;
import grakn.core.pattern.Negation;
import grakn.core.reasoner.resolution.Planner;
import grakn.core.reasoner.resolution.ResolverRegistry;
import grakn.core.reasoner.resolution.answer.AnswerState;
import grakn.core.reasoner.resolution.answer.AnswerState.Partial;
import grakn.core.reasoner.resolution.answer.Mapping;
import grakn.core.reasoner.resolution.framework.Request;
import grakn.core.reasoner.resolution.framework.Response;
import grakn.core.traversal.TraversalEngine;
import grakn.core.traversal.common.Identifier.Variable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static grakn.core.common.exception.ErrorMessage.Internal.ILLEGAL_STATE;
import static grakn.core.common.iterator.Iterators.iterate;

public abstract class ConjunctionResolver<RESOLVER extends ConjunctionResolver<RESOLVER>> extends CompoundResolver<RESOLVER> {

    private static final Logger LOG = LoggerFactory.getLogger(ConjunctionResolver.class);

    private final Planner planner;
    final LogicManager logicMgr;
    final Set<Resolvable<?>> resolvables;
    final Set<Negated> negateds;
    final Plans plans;
    final Map<Resolvable<?>, ResolverRegistry.ResolverView> downstreamResolvers;

    public ConjunctionResolver(Driver<RESOLVER> driver, String name, ResolverRegistry registry, TraversalEngine traversalEngine,
                               ConceptManager conceptMgr, LogicManager logicMgr, Planner planner, boolean resolutionTracing) {
        super(driver, name, registry, traversalEngine, conceptMgr, resolutionTracing);
        this.logicMgr = logicMgr;
        this.planner = planner;
        this.resolvables = new HashSet<>();
        this.negateds = new HashSet<>();
        this.plans = new Plans();
        this.downstreamResolvers = new HashMap<>();
    }

    abstract Set<Concludable> concludablesTriggeringRules();

    abstract Conjunction conjunction();

    @Override
    protected abstract void nextAnswer(Request fromUpstream, RequestState requestState, int iteration);

    abstract Optional<AnswerState> toUpstreamAnswer(Partial.Compound<?, ?> fromDownstream);

    // TODO: Should be absorbed into nextAnswer
    abstract boolean tryAcceptUpstreamAnswer(AnswerState upstreamAnswer, Request fromUpstream, int iteration);

    @Override
    protected void receiveAnswer(Response.Answer fromDownstream, int iteration) {
        LOG.trace("{}: received Answer: {}", name(), fromDownstream);
        if (isTerminated()) return;

        Request toDownstream = fromDownstream.sourceRequest();
        Request fromUpstream = fromUpstream(toDownstream);
        RequestState requestState = requestStates.get(fromUpstream);

        Plans.Plan plan = plans.getActive(fromUpstream);

        // TODO: this is a bit of a hack, we want requests to a negation to be "single use", otherwise we can end up in an infinite loop
        // TODO: where the request to the negation never gets removed and we constantly re-request from it!
        // TODO: this could be either implemented with a different response type: FinalAnswer, or splitting Request into ReusableRequest vs SingleRequest
        if (plan.get(toDownstream.planIndex()).isNegated()) requestState.downstreamManager().removeDownstream(toDownstream);

        Partial.Compound<?, ?> partialAnswer = fromDownstream.answer().asCompound();
        if (plan.isLast(fromDownstream.planIndex())) {
            Optional<AnswerState> upstreamAnswer = toUpstreamAnswer(partialAnswer);
            boolean answerAccepted = upstreamAnswer.isPresent() && tryAcceptUpstreamAnswer(upstreamAnswer.get(), fromUpstream, iteration);
            if (!answerAccepted) nextAnswer(fromUpstream, requestState, iteration);
        } else {
            toNextChild(fromDownstream, iteration, fromUpstream, requestState, plan);
        }
    }

    private void toNextChild(Response.Answer fromDownstream, int iteration, Request fromUpstream, RequestState requestState, Plans.Plan plan) {
        int nextResolverIndex = fromDownstream.planIndex() + 1;
        Resolvable<?> nextResolvable = plan.get(nextResolverIndex);
        ResolverRegistry.ResolverView nextPlannedDownstream = downstreamResolvers.get(nextResolvable);
        final Partial<?> downstream = toDownstream(fromDownstream.answer().asCompound(), nextPlannedDownstream, nextResolvable);
        Request downstreamRequest = Request.create(driver(), nextPlannedDownstream.resolver(), downstream, nextResolverIndex);
        requestFromDownstream(downstreamRequest, fromUpstream, iteration);
        // negated requests can be used twice in a parallel setting, and return the same answer twice
        if (!nextResolvable.isNegated() || (nextResolvable.isNegated() && !requestState.downstreamManager().contains(downstreamRequest))) {
            requestState.downstreamManager().addDownstream(downstreamRequest);
        }
    }

    @Override
    protected void receiveFail(Response.Fail fromDownstream, int iteration) {
        LOG.trace("{}: Receiving Exhausted: {}", name(), fromDownstream);
        if (isTerminated()) return;

        Request toDownstream = fromDownstream.sourceRequest();
        Request fromUpstream = fromUpstream(toDownstream);
        RequestState requestState = this.requestStates.get(fromUpstream);

        if (iteration < requestState.iteration()) {
            // short circuit old iteration failed messages to upstream
            failToUpstream(fromUpstream, iteration);
            return;
        }

        requestState.downstreamManager().removeDownstream(fromDownstream.sourceRequest());
        nextAnswer(fromUpstream, requestState, iteration);
    }

    @Override
    protected void initialiseDownstreamResolvers() {
        LOG.debug("{}: initialising downstream resolvers", name());
        Set<Concludable> concludables = concludablesTriggeringRules();
        Set<Retrievable> retrievables = Retrievable.extractFrom(conjunction(), concludables);
        resolvables.addAll(concludables);
        resolvables.addAll(retrievables);
        iterate(resolvables).forEachRemaining(resolvable -> {
            try {
                downstreamResolvers.put(resolvable, registry.registerResolvable(resolvable));
            } catch (GraknException e) {
                terminate(e);
            }
        });
        for (Negation negation : conjunction().negations()) {
            Negated negated = new Negated(negation);
            try {
                downstreamResolvers.put(negated, registry.negated(negated, conjunction()));
                negateds.add(negated);
            } catch (GraknException e) {
                terminate(e);
            }
        }
        if (!isTerminated()) isInitialised = true;
    }

    @Override
    protected RequestState requestStateCreate(Request fromUpstream, int iteration) {
        LOG.debug("{}: Creating a new RequestState for request: {}", name(), fromUpstream);
        Plans.Plan plan = plans.create(fromUpstream, resolvables, negateds);
        assert !plan.isEmpty() && fromUpstream.partialAnswer().isCompound();
        RequestState requestState = requestStateNew(iteration);
        initialiseRequestState(requestState, fromUpstream.partialAnswer().asCompound(), plan);
        return requestState;
    }

    @Override
    protected RequestState requestStateReiterate(Request fromUpstream, RequestState requestStatePrior,
                                                  int newIteration) {
        assert newIteration > requestStatePrior.iteration();
        LOG.debug("{}: Updating RequestState for iteration '{}'", name(), newIteration);
        Plans.Plan plan = plans.create(fromUpstream, resolvables, negateds);
        assert !plan.isEmpty() && fromUpstream.partialAnswer().isCompound();
        RequestState requestStateNextIteration = requestStateForIteration(requestStatePrior, newIteration);
        initialiseRequestState(requestStateNextIteration, fromUpstream.partialAnswer().asCompound(), plan);
        return requestStateNextIteration;
    }

    private void initialiseRequestState(RequestState requestState, Partial.Compound<?, ?> partialAnswer, Plans.Plan plan) {
        ResolverRegistry.ResolverView childResolver = downstreamResolvers.get(plan.get(0));
        Partial<?> downstream = toDownstream(partialAnswer, childResolver, plan.get(0));
        Request toDownstream = Request.create(driver(), childResolver.resolver(), downstream, 0);
        requestState.downstreamManager().addDownstream(toDownstream);
    }

    private Partial<?> toDownstream(Partial.Compound<?, ?> partialAnswer, ResolverRegistry.ResolverView nextDownstream, Resolvable<?> nextResolvable) {
        assert downstreamResolvers.get(nextResolvable).equals(nextDownstream);
        if (nextDownstream.isMappedConcludable()) {
            return partialAnswer.toDownstream(Mapping.of(nextDownstream.asMappedConcludable().mapping()), nextResolvable.asConcludable());
        } else if (nextDownstream.isFilteredNegation()) {
            return partialAnswer.filterToNestable(nextDownstream.asFilteredNegation().filter());
        } else if (nextDownstream.isFilteredRetrievable()) {
            return partialAnswer.filterToRetrievable(nextDownstream.asFilteredRetrievable().filter());
        } else {
            throw GraknException.of(ILLEGAL_STATE);
        }
    }

    abstract RequestState requestStateNew(int iteration);

    abstract RequestState requestStateForIteration(RequestState requestStatePrior, int iteration);

    class Plans {
        private final Map<ConceptMap, Plan> plans;
        private final Map<Request, Plan> activePlans;
        private final Map<Resolvable<?>, Map<Map<Variable.Retrievable, Concept>, Integer>> resolvablesStatistics;

        public Plans() {
            this.plans = new HashMap<>();
            this.activePlans = new HashMap<>();
            this.resolvablesStatistics = new HashMap<>();
        }

        public Plan create(Request fromUpstream, Set<Resolvable<?>> resolvables, Set<Negated> negations) {
            ConceptMap bounds = fromUpstream.partialAnswer().conceptMap();
            Map<Resolvable<?>, Integer> statistics = updateResolvablesStatistics(resolvables, bounds);
            Plan plan = plans.computeIfAbsent(bounds, (ignored) -> {
                List<Resolvable<?>> newPlan = planner.plan(resolvables, statistics, bounds.concepts().keySet());
                newPlan.addAll(negations);
                return new Plan(newPlan);
            });
            activePlans.put(fromUpstream, plan);
            return plan;
        }

        public Plan getActive(Request fromUpstream) {
            assert activePlans.containsKey(fromUpstream);
            return activePlans.get(fromUpstream);
        }

        private Map<Resolvable<?>, Integer> updateResolvablesStatistics(Set<Resolvable<?>> resolvables, ConceptMap conceptMap) {
            Map<Resolvable<?>, Integer> resolvableCounts = new HashMap<>();
            for (Resolvable<?> resolvable : resolvables) {
                resolvablesStatistics.putIfAbsent(resolvable, new HashMap<>());
                Map<Map<Variable.Retrievable, Concept>, Integer> resolvableStatistics = resolvablesStatistics.get(resolvable);
                Map<Variable.Retrievable, Concept> filtered = filterConceptMapForResolvable(resolvable, conceptMap);
                if (!filtered.isEmpty()) {
                    resolvableStatistics.putIfAbsent(filtered, 0);
                    int newCount = resolvableStatistics.get(filtered) + 1;
                    resolvableStatistics.put(filtered, newCount);
                    resolvableCounts.put(resolvable, newCount);
                }
            }
            return resolvableCounts;
        }

        private Map<Variable.Retrievable, Concept> filterConceptMapForResolvable(Resolvable<?> resolvable, ConceptMap conceptMap) {
            Map<Variable.Retrievable, Concept> map = new HashMap<>();
            conceptMap.concepts().forEach((variable, concept) -> {
                if (resolvable.retrieves().contains(variable)) {
                    map.put(variable, concept);
                }
            });
            return map;
        }

        public class Plan {

            final List<Resolvable<?>> plan;

            private Plan(List<Resolvable<?>> plan) { this.plan = plan; }

            public Resolvable<?> get(int index) {
                return plan.get(index);
            }

            public boolean isLast(int index) {
                return index == plan.size() - 1;
            }

            public boolean isEmpty() { return plan.isEmpty(); }
        }
    }

    public static class Nested extends ConjunctionResolver<Nested> {

        private final Conjunction conjunction;

        public Nested(Driver<Nested> driver, Conjunction conjunction,
                      ResolverRegistry registry, TraversalEngine traversalEngine, ConceptManager conceptMgr,
                      LogicManager logicMgr, Planner planner, boolean resolutionTracing) {
            super(driver, Nested.class.getSimpleName() + "(pattern: " + conjunction + ")",
                  registry, traversalEngine, conceptMgr, logicMgr, planner, resolutionTracing);
            this.conjunction = conjunction;
        }

        @Override
        Set<Concludable> concludablesTriggeringRules() {
            return Iterators.iterate(Concludable.create(conjunction))
                    .filter(c -> c.getApplicableRules(conceptMgr, logicMgr).hasNext())
                    .toSet();
        }

        @Override
        Conjunction conjunction() {
            return conjunction;
        }

        @Override
        protected void nextAnswer(Request fromUpstream, RequestState requestState, int iteration) {
            if (requestState.downstreamManager().hasDownstream()) {
                requestFromDownstream(requestState.downstreamManager().nextDownstream(), fromUpstream, iteration);
            } else {
                failToUpstream(fromUpstream, iteration);
            }
        }

        @Override
        protected Optional<AnswerState> toUpstreamAnswer(Partial.Compound<?, ?> partialAnswer) {
            return Optional.of(partialAnswer.asNestable().toUpstream());
        }

        @Override
        boolean tryAcceptUpstreamAnswer(AnswerState upstreamAnswer, Request fromUpstream, int iteration) {
            RequestState requestState = requestStates.get(fromUpstream);
            if (!requestState.deduplicationSet().contains(upstreamAnswer.conceptMap())) {
                requestState.deduplicationSet().add(upstreamAnswer.conceptMap());
                answerToUpstream(upstreamAnswer, fromUpstream, iteration);
                return true;
            } else {
                return false;
            }
        }

        @Override
        RequestState requestStateNew(int iteration) {
            return new RequestState(iteration);
        }

        @Override
        RequestState requestStateForIteration(RequestState requestStatePrior, int iteration) {
            return new RequestState(iteration);
        }
    }
}
