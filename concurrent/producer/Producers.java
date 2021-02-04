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

package grakn.core.concurrent.producer;

import grakn.core.common.iterator.Iterators;
import grakn.core.common.iterator.ResourceIterator;
import grakn.core.common.parameters.Arguments;

import java.util.List;
import java.util.concurrent.ExecutorService;

import static grakn.common.collection.Collections.list;

public class Producers {

    public static final int DEFAULT_BATCH_SIZE = 1;
    public static final int MAX_BATCH_SIZE = 1;

    public static <T> BaseProducer<T> empty() { return async(Iterators.empty()); }

    public static <T> Producer<T> async(ResourceIterator<ResourceIterator<T>> iterators, int parallelisation) {
        return new AsyncProducer<>(iterators, parallelisation);
    }

    public static <T> BaseProducer<T> async(ResourceIterator<T> iterator) {
        return new BaseProducer<>(iterator);
    }

    public static <T> ProducerIterator<T> produce(Producer<T> producer, Arguments.Query.Producer mode, ExecutorService executor) {
        return produce(list(producer), mode, executor);
    }

    public static <T> ProducerIterator<T> produce(Producer<T> producer, int batchSize, ExecutorService executor) {
        return produce(list(producer), batchSize, executor);
    }

    public static <T> ProducerIterator<T> produce(List<Producer<T>> producers, Arguments.Query.Producer mode, ExecutorService executor) {
        return new ProducerIterator<>(producers, mode.isIncremental() ? DEFAULT_BATCH_SIZE : MAX_BATCH_SIZE, executor);
    }

    public static <T> ProducerIterator<T> produce(List<Producer<T>> producers, int batchSize, ExecutorService executor) {
        return new ProducerIterator<>(producers, batchSize, executor);
    }
}
