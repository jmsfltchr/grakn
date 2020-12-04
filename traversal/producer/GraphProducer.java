/*
 * Copyright (C) 2020 Grakn Labs
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

package grakn.core.traversal.producer;

import grakn.core.common.iterator.SynchronisedIterator;
import grakn.core.graph.GraphManager;
import grakn.core.graph.vertex.Vertex;
import grakn.core.traversal.Traversal;
import grakn.core.traversal.procedure.Procedure;
import graql.lang.pattern.variable.Reference;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static grakn.core.common.concurrent.ExecutorService.forkJoinPool;
import static grakn.core.common.iterator.Iterators.synchronised;
import static java.util.concurrent.CompletableFuture.runAsync;

public class GraphProducer implements TraversalProducer {

    private final int parallelisation;
    private final Procedure procedure;
    private final Traversal.Parameters parameters;
    private final SynchronisedIterator<? extends Vertex<?, ?>> start;
    private final Map<GraphIterator, CompletableFuture<Void>> futures;
    private final AtomicBoolean isDone;

    public GraphProducer(GraphManager graphMgr, Procedure procedure, Traversal.Parameters parameters, int parallelisation) {
        assert parallelisation > 0;
        this.procedure = procedure;
        this.parameters = parameters;
        this.parallelisation = parallelisation;
        this.isDone = new AtomicBoolean(false);
        this.futures = new ConcurrentHashMap<>();
        this.start = synchronised(procedure.startVertex().iterator(graphMgr, parameters));
    }

    @Override
    public void produce(Sink<Map<Reference, Vertex<?, ?>>> sink, int count) {
        int splitCount = (int) Math.ceil((double) count / parallelisation);

        if (futures.isEmpty()) {
            if (!start.hasNext()) sink.done(this);
            int i = 0;
            for (; i < parallelisation && start.hasNext(); i++) {
                GraphIterator iterator = new GraphIterator(start.next(), procedure, parameters);
                futures.computeIfAbsent(iterator, k -> runAsync(consume(iterator, splitCount, sink), forkJoinPool()));
            }
            produce(sink, (parallelisation - i) * splitCount);
        } else {
            for (GraphIterator iterator : futures.keySet()) {
                futures.computeIfPresent(iterator, (k, v) -> v.thenRun(consume(k, splitCount, sink)));
            }
        }
    }

    private Runnable consume(GraphIterator iterator, int count, Sink<Map<Reference, Vertex<?, ?>>> sink) {
        return () -> {
            int i = 0;
            for (; i < count; i++) {
                if (iterator.hasNext()) sink.put(iterator.next());
                else break;
            }
            if (i < count) compensate(iterator, count - i, sink);
        };
    }

    private void compensate(GraphIterator completedIterator, int remaining, Sink<Map<Reference, Vertex<?, ?>>> sink) {
        futures.remove(completedIterator);
        Vertex<?, ?> next;
        if ((next = start.atomicNext()) != null) {
            GraphIterator iterator = new GraphIterator(next, procedure, parameters);
            futures.put(iterator, runAsync(consume(iterator, remaining, sink), forkJoinPool()));
        } else if (futures.isEmpty()) {
            done(sink);
        } else {
            produce(sink, remaining);
        }
    }

    private void done(Sink<Map<Reference, Vertex<?, ?>>> sink) {
        if (isDone.compareAndSet(false, true)) {
            sink.done(this);
        }
    }

    @Override
    public void recycle() {
        start.recycle();
        futures.keySet().forEach(GraphIterator::recycle);
    }
}
