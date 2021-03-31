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

package grakn.core.reasoner.resolution.framework;

import grakn.common.collection.Either;
import grakn.core.common.exception.GraknException;
import grakn.core.common.iterator.FunctionalIterator;
import grakn.core.common.iterator.Iterators;
import grakn.core.concept.Concept;
import grakn.core.concept.ConceptManager;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.concurrent.actor.Actor;
import grakn.core.concurrent.producer.Producer;
import grakn.core.concurrent.producer.Producers;
import grakn.core.pattern.Conjunction;
import grakn.core.pattern.variable.Variable;
import grakn.core.reasoner.resolution.ResolverRegistry;
import grakn.core.reasoner.resolution.answer.AnswerState;
import grakn.core.reasoner.resolution.answer.AnswerState.Partial;
import grakn.core.reasoner.resolution.framework.Response.Answer;
import grakn.core.traversal.Traversal;
import grakn.core.traversal.TraversalEngine;
import grakn.core.traversal.common.Identifier.Variable.Retrievable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static grakn.common.collection.Collections.set;
import static grakn.core.common.exception.ErrorMessage.Internal.ILLEGAL_STATE;
import static grakn.core.common.exception.ErrorMessage.Internal.RESOURCE_CLOSED;
import static grakn.core.common.iterator.Iterators.iterate;
import static grakn.core.common.parameters.Arguments.Query.Producer.INCREMENTAL;

public abstract class Resolver<RESOLVER extends Resolver<RESOLVER>> extends Actor<RESOLVER> {
    private static final Logger LOG = LoggerFactory.getLogger(Resolver.class);

    private final Map<Request, Request> requestRouter;
    protected final ResolverRegistry registry;
    protected final TraversalEngine traversalEngine;
    protected final ConceptManager conceptMgr;
    private final boolean resolutionTracing;
    private boolean terminated;
    private static final AtomicInteger messageCount = new AtomicInteger(0);

    protected Resolver(Driver<RESOLVER> driver, String name, ResolverRegistry registry, TraversalEngine traversalEngine,
                       ConceptManager conceptMgr, boolean resolutionTracing) {
        super(driver, name);
        this.registry = registry;
        this.traversalEngine = traversalEngine;
        this.conceptMgr = conceptMgr;
        this.resolutionTracing = resolutionTracing;
        this.terminated = false;
        this.requestRouter = new HashMap<>();
        // Note: initialising downstream actors in constructor will create all actors ahead of time, so it is non-lazy
        // additionally, it can cause deadlock within ResolverRegistry as different threads initialise actors
    }

    @Override
    protected void exception(Throwable e) {
        if (e instanceof GraknException && ((GraknException) e).code().isPresent()) {
            String code = ((GraknException) e).code().get();
            if (code.equals(RESOURCE_CLOSED.code())) {
                LOG.debug("Resolver interrupted by resource close: {}", e.getMessage());
                registry.terminateResolvers(e);
                return;
            }
        }
        LOG.error("Actor exception: {}", e.getMessage());
        registry.terminateResolvers(e);
    }

    public abstract void receiveRequest(Request fromUpstream, int iteration);

    protected abstract void receiveAnswer(Response.Answer fromDownstream, int iteration);

    protected abstract void receiveFail(Response.Fail fromDownstream, int iteration);

    public void terminate(Throwable cause) {
        LOG.error("error", cause);
        this.terminated = true;
    }

    public boolean isTerminated() { return terminated; }

    protected abstract void initialiseDownstreamResolvers();

    protected Request fromUpstream(Request toDownstream) {
        assert requestRouter.containsKey(toDownstream);
        return requestRouter.get(toDownstream);
    }

    private void logMessage(int iteration) {
        int i = messageCount.incrementAndGet();
        if (i % 100 == 0) {
            LOG.info("Message count: {} (iteration {})", i, iteration);
        }
    }

    protected void requestFromDownstream(Request request, Request fromUpstream, int iteration) {
        LOG.trace("{} : Sending a new answer Request to downstream: {}", name(), request);
        if (resolutionTracing) ResolutionTracer.get().request(this.name(), request.receiver().name(), iteration,
                                                              request.partialAnswer().conceptMap().concepts().keySet().toString());
        // TODO: we may overwrite if multiple identical requests are sent, when to clean up?
        requestRouter.put(request, fromUpstream);
        Driver<? extends Resolver<?>> receiver = request.receiver();
        logMessage(iteration);
        receiver.execute(actor -> actor.receiveRequest(request, iteration));
    }

    protected void answerToUpstream(AnswerState answer, Request fromUpstream, int iteration) {
        assert answer.isPartial();
        Answer response = Answer.create(fromUpstream, answer.asPartial());
        LOG.trace("{} : Sending a new Response.Answer to upstream", name());
        if (resolutionTracing) ResolutionTracer.get().responseAnswer(
                this.name(), fromUpstream.sender().name(), iteration,
                response.asAnswer().answer().conceptMap().concepts().keySet().toString()
        );
        logMessage(iteration);
        fromUpstream.sender().execute(actor -> actor.receiveAnswer(response, iteration));
    }

    protected void failToUpstream(Request fromUpstream, int iteration) {
        Response.Fail response = new Response.Fail(fromUpstream);
        LOG.trace("{} : Sending a new Response.Answer to upstream", name());
        if (resolutionTracing) ResolutionTracer.get().responseExhausted(
                this.name(), fromUpstream.sender().name(), iteration
        );
        logMessage(iteration);
        fromUpstream.sender().execute(actor -> actor.receiveFail(response, iteration));
    }

    protected FunctionalIterator<ConceptMap> traversalIterator(Conjunction conjunction, ConceptMap bounds) {
        return compatibleBounds(conjunction, bounds).map(c -> {
            Traversal traversal = boundTraversal(conjunction.traversal(), c);
            return traversalEngine.iterator(traversal).map(conceptMgr::conceptMap);
        }).orElse(Iterators.empty());
    }

    protected Producer<ConceptMap> traversalProducer(Conjunction conjunction, ConceptMap bounds, int parallelisation) {
        return compatibleBounds(conjunction, bounds).map(b -> {
            Traversal traversal = boundTraversal(conjunction.traversal(), b);
            return traversalEngine.producer(traversal, Either.first(INCREMENTAL), parallelisation).map(conceptMgr::conceptMap);
        }).orElse(Producers.empty());
    }

    private Optional<ConceptMap> compatibleBounds(Conjunction conjunction, ConceptMap bounds) {
        Map<Retrievable, Concept> newBounds = new HashMap<>();
        for (Map.Entry<Retrievable, ? extends Concept> entry : bounds.concepts().entrySet()) {
            Retrievable id = entry.getKey();
            Concept bound = entry.getValue();
            Variable conjVariable = conjunction.variable(id);
            assert conjVariable != null;
            if (conjVariable.isThing()) {
                if (!conjVariable.asThing().iid().isPresent()) newBounds.put(id, bound);
                else if (!Arrays.equals(conjVariable.asThing().iid().get().iid(), bound.asThing().getIID())) {
                    return Optional.empty();
                }
            } else if (conjVariable.isType()) {
                if (!conjVariable.asType().label().isPresent()) newBounds.put(id, bound);
                else if (!conjVariable.asType().label().get().properLabel().equals(bound.asType().getLabel())) {
                    return Optional.empty();
                }
            } else {
                throw GraknException.of(ILLEGAL_STATE);
            }
        }
        return Optional.of(new ConceptMap(newBounds));
    }

    protected Traversal boundTraversal(Traversal traversal, ConceptMap bounds) {
        bounds.concepts().forEach((id, concept) -> {
            if (concept.isThing()) traversal.iid(id.asVariable(), concept.asThing().getIID());
            else {
                traversal.clearLabels(id.asVariable());
                traversal.labels(id.asVariable(), concept.asType().getLabel());
            }
        });
        return traversal;
    }

    protected static abstract class RequestState {

        private final int iteration;

        protected RequestState(int iteration) {this.iteration = iteration;}

        public abstract Optional<? extends Partial<?>> nextAnswer();

        public int iteration() {
            return iteration;
        }
    }

    protected abstract static class CachingRequestState<ANSWER> extends RequestState {

        protected final Request fromUpstream;
        protected final CacheTracker<ANSWER>.AnswerCache answerCache;
        protected int pointer;

        public CachingRequestState(Request fromUpstream, CacheTracker<ANSWER>.AnswerCache answerCache, int iteration) {
            super(iteration);
            this.fromUpstream = fromUpstream;
            this.answerCache = answerCache;
            this.pointer = 0;
        }

        public Optional<? extends Partial<?>> nextAnswer() {
            Optional<? extends Partial<?>> upstreamAnswer = Optional.empty();
            while (true) {
                Optional<ANSWER> answer = next();
                if (answer.isPresent()) {
                    pointer++;
                    upstreamAnswer = toUpstream(answer.get()).filter(partial -> !optionallyDeduplicate(partial.conceptMap()));
                    if (upstreamAnswer.isPresent()) break;
                } else {
                    break;
                }
            }
            return upstreamAnswer;
        }

        protected abstract Optional<? extends Partial<?>> toUpstream(ANSWER conceptMap);

        protected abstract boolean optionallyDeduplicate(ConceptMap conceptMap);

        protected abstract Optional<ANSWER> next();

        public boolean cacheComplete() {
            return answerCache.complete();
        }

        public void setCacheComplete() {
            answerCache.setComplete();
        }
    }

    public static class CacheTracker<ANSWER> {
        HashMap<ConceptMap, AnswerCache> answerCaches;
        private int iteration;
        private final SubsumptionOperation<ANSWER> subsumption;

        public CacheTracker(int iteration, SubsumptionOperation<ANSWER> subsumption) {
            this.iteration = iteration;
            this.subsumption = subsumption;
            this.answerCaches = new HashMap<>();
        }

        public boolean isTracked(ConceptMap conceptMap) {
            return answerCaches.containsKey(conceptMap);
        }

        public int iteration() {
            return iteration;
        }

        public void nextIteration(int newIteration) {
            assert newIteration > iteration;
            iteration = newIteration;
            answerCaches = new HashMap<>();
        }

        public AnswerCache getAnswerCache(ConceptMap fromUpstream) {
            return answerCaches.get(fromUpstream);
        }

        public AnswerCache createAnswerCache(ConceptMap fromUpstream, boolean useSubsumption) {
            assert !answerCaches.containsKey(fromUpstream);
            Set<ConceptMap> subsumingAnswers;
            if (useSubsumption) {
                subsumingAnswers = getSubsumingAnswers(fromUpstream);
                subsumingAnswers.remove(fromUpstream);
            } else {
                subsumingAnswers = set();
            }
            AnswerCache newCache = new AnswerCache(fromUpstream, subsumption, subsumingAnswers);
            answerCaches.put(fromUpstream, newCache);
            return newCache;
        }

        private Set<ConceptMap> getSubsumingAnswers(ConceptMap fromUpstream) {
            Set<ConceptMap> subsumingAnswers = new HashSet<>();
            powerSet(fromUpstream.concepts().entrySet()).forEach(s -> {
                HashMap<Retrievable, Concept> map = new HashMap<>();
                s.forEach(entry -> map.put(entry.getKey(), entry.getValue()));
                subsumingAnswers.add(new ConceptMap(map));
            });
            return subsumingAnswers;
        }

        private static <T> Set<Set<T>> powerSet(Set<T> set) {
            Set<Set<T>> powerSet = new HashSet<>();
            powerSet.add(set);
            set.forEach(el -> {
                Set<T> s = new HashSet<>(set);
                s.remove(el);
                powerSet.addAll(powerSet(s));
            });
            return powerSet;
        }

        public static abstract class SubsumptionOperation<ANSWER> {
            protected abstract boolean subsumes(ANSWER answer, ConceptMap contained);
        }

        public class AnswerCache {

            private final List<ANSWER> answers;
            private final Set<ANSWER> answersSet;
            private final Set<ConceptMap> subsumingAnswers;
            private boolean retrievedFromIncomplete;
            private boolean requiresReiteration;
            private FunctionalIterator<ANSWER> traversal;
            private boolean completed;
            private final ConceptMap state;
            private final SubsumptionOperation<ANSWER> subsumption;

            private AnswerCache(ConceptMap state, SubsumptionOperation<ANSWER> subsumption, Set<ConceptMap> subsumingAnswers) {
                this.state = state;
                this.subsumption = subsumption;
                this.subsumingAnswers = subsumingAnswers;
                this.traversal = Iterators.empty();
                this.answers = new ArrayList<>(); // TODO: Replace answer list and deduplication set with a bloom filter
                this.answersSet = new HashSet<>();
                this.retrievedFromIncomplete = false;
                this.requiresReiteration = false;
                this.completed = false;
            }

            public void recordNewAnswer(ANSWER newAnswer) {
                if (!complete()) newAnswer(newAnswer);
            }

            public void recordNewAnswers(Iterator<ANSWER> newAnswers) {
                if (complete()) throw GraknException.of(ILLEGAL_STATE);
                traversal = traversal.link(newAnswers);
            }

            public void setRequiresReiteration() {
                this.requiresReiteration = true;
            }

            public void setComplete() {
                completed = true;
            }

            public void setComplete(List<ANSWER> completeAnswers) {
                List<ANSWER> newAnswers = iterate(completeAnswers)
                        .filter(e -> subsumption.subsumes(e, state))
                        .filter(e -> !answersSet.contains(e)).toList();
                this.answers.addAll(newAnswers);
                this.answersSet.addAll(newAnswers);
                setComplete();
            }

            public boolean complete() {
                if (completed) return true;
                for (ConceptMap subsumingAnswer : subsumingAnswers) {
                    if (answerCaches.containsKey(subsumingAnswer)){
                        AnswerCache subsumingCache;
                        if ((subsumingCache = answerCaches.get(subsumingAnswer)).complete()) {
                            setComplete(subsumingCache.answers);
                            if (subsumingCache.requiresReiteration()) setRequiresReiteration();
                            return true;
                        }
                    }
                }
                return false;
            }

            public Optional<ANSWER> next(int index, boolean canRecordNewAnswers) {
                assert index >= 0;
                if (index < answers.size()) {
                    return Optional.of(answers.get(index));
                } else if (index == answers.size()) {
                    if (traversal.hasNext()) {
                        ANSWER newAnswer = traversal.next();
                        boolean isNewAnswer = newAnswer(newAnswer);
                        if (isNewAnswer) {
                            return Optional.of(newAnswer);
                        } else {
                            return Optional.empty();
                        }
                    }
                    if (!canRecordNewAnswers) retrievedFromIncomplete = true;
                    return Optional.empty();
                } else {
                    throw GraknException.of(ILLEGAL_STATE);
                }
            }

            private boolean newAnswer(ANSWER answer) {
                if (answersSet.contains(answer)) return false;
                answers.add(answer);
                answersSet.add(answer);
                if (retrievedFromIncomplete) this.requiresReiteration = true;
                return true;
            }

            public boolean requiresReiteration() {
                return requiresReiteration;
            }
        }
    }

    public static class ProducedRecorder {
        private final Set<ConceptMap> produced;

        public ProducedRecorder() {
            this(new HashSet<>());
        }

        public ProducedRecorder(Set<ConceptMap> produced) {
            this.produced = produced;
        }

        public boolean produced(ConceptMap conceptMap) {
            if (produced.contains(conceptMap)) return true;
            produced.add(conceptMap);
            return false;
        }

        public boolean hasProduced(ConceptMap conceptMap) { // TODO method shouldn't be needed
            return produced.contains(conceptMap);
        }

        public Set<ConceptMap> produced() {
            return produced;
        }
    }

    public static class DownstreamManager {
        private final LinkedHashSet<Request> downstreams;
        private Iterator<Request> downstreamSelector;

        public DownstreamManager() {
            this.downstreams = new LinkedHashSet<>();
            this.downstreamSelector = downstreams.iterator();
        }

        public boolean hasDownstream() {
            return !downstreams.isEmpty();
        }

        public Request nextDownstream() {
            if (!downstreamSelector.hasNext()) downstreamSelector = downstreams.iterator();
            return downstreamSelector.next();
        }

        public void addDownstream(Request request) {
            assert !(downstreams.contains(request)) : "downstream answer producer already contains this request";

            downstreams.add(request);
            downstreamSelector = downstreams.iterator();
        }

        public void removeDownstream(Request request) {
            boolean removed = downstreams.remove(request);
            // only update the iterator when removing an element, to avoid resetting and reusing first request too often
            // note: this is a large performance win when processing large batches of requests
            if (removed) downstreamSelector = downstreams.iterator();
        }

        public void clearDownstreams() {
            downstreams.clear();
            downstreamSelector = Iterators.empty();
        }

        public boolean contains(Request downstreamRequest) {
            return downstreams.contains(downstreamRequest);
        }
    }
}
