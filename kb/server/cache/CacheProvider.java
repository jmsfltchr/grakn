/*
 * GRAKN.AI - THE KNOWLEDGE GRAPH
 * Copyright (C) 2019 Grakn Labs Ltd
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

package grakn.core.kb.server.cache;

import grakn.core.kb.graql.reasoner.cache.QueryCache;
import grakn.core.kb.graql.reasoner.cache.RuleCache;

/**
 * Implemented CacheProvider as a provider to have a idempotent `get()` methods.
 * This ensures that if the provider is shared, everyone receives the same instances of the Caches
 */
public interface CacheProvider {

    RuleCache getRuleCache();

    QueryCache getQueryCache();

    TransactionCache getTransactionCache();
}
